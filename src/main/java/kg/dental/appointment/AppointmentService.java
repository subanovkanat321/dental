package kg.dental.appointment;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kg.dental.DentalProperties;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientView;
import kg.dental.shared.ConflictException;
import kg.dental.shared.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {

	/** SQLState Postgres «exclusion_violation»: сработало ограничение EXCLUDE (appointment_no_overlap). */
	private static final String EXCLUSION_VIOLATION = "23P01";

	private final AppointmentRepository appointments;

	private final PatientService patients;

	private final Clock clock;

	private final DentalProperties properties;

	private final AppointmentStatsQuery stats;

	private final RecallQuery recall;

	AppointmentService(AppointmentRepository appointments, PatientService patients, Clock clock,
			DentalProperties properties, AppointmentStatsQuery stats, RecallQuery recall) {
		this.appointments = appointments;
		this.patients = patients;
		this.clock = clock;
		this.properties = properties;
		this.stats = stats;
		this.recall = recall;
	}

	/** Кого пора позвать на профосмотр: последний визит больше {@code months} месяцев назад. */
	@Transactional(readOnly = true)
	public List<RecallView> recall(int months) {
		var now = clock.instant();
		var before = LocalDate.ofInstant(now, properties.timeZone()).minusMonths(months)
			.atStartOfDay(properties.timeZone()).toInstant();
		return recall.due(before, now);
	}

	/** Неявки и приёмы по неделям — сравнить с тем, что было до приложения (2–3 неявки в неделю). */
	@Transactional(readOnly = true)
	public List<WeekStats> weeklyStats(int weeks) {
		return stats.weeks(weeks, clock.instant(), properties.timeZone());
	}

	/** Приёмы календарного дня по Бишкеку, включая отменённые (в «Дне» они зачёркнуты). */
	@Transactional(readOnly = true)
	public List<AppointmentView> day(LocalDate date) {
		var zone = properties.timeZone();
		var from = date.atStartOfDay(zone).toInstant();
		var to = date.plusDays(1).atStartOfDay(zone).toInstant();
		return appointments.findDay(from, to).stream().map(AppointmentView::of).toList();
	}

	/**
	 * Для утренней сводки: приёмы на {@code date} и неотмеченные за 7 дней до {@code now}
	 * (R7/D9: не только «вчера» — в понедельник видно и субботу).
	 */
	@Transactional(readOnly = true)
	public DaySummary summary(LocalDate date, Instant now) {
		var zone = properties.timeZone();
		var starts = appointments.findScheduledStarts(date.atStartOfDay(zone).toInstant(),
				date.plusDays(1).atStartOfDay(zone).toInstant());
		var weekAgo = LocalDate.ofInstant(now, zone).minusDays(7).atStartOfDay(zone).toInstant();
		return new DaySummary(starts.size(), starts.isEmpty() ? null : starts.getFirst(),
				appointments.countUnmarked(weekAgo, now));
	}

	/**
	 * Карточка пациента: что впереди, что было, сколько раз пришёл, не пришёл, отменял.
	 * Прошлое — не больше 50 последних приёмов: на экране телефона больше не нужно.
	 */
	@Transactional(readOnly = true)
	public PatientCard card(long patientId) {
		var patient = patients.require(patientId);
		var all = appointments.findByPatient(patientId);
		var now = clock.instant();
		var upcoming = all.stream()
			.filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED && a.getEndAt().isAfter(now))
			.sorted(Comparator.comparing(Appointment::getStartAt))
			.map(AppointmentView::of)
			.toList();
		var past = all.stream()
			.filter(a -> !(a.getStatus() == AppointmentStatus.SCHEDULED && a.getEndAt().isAfter(now)))
			.limit(50)
			.map(AppointmentView::of)
			.toList();
		return new PatientCard(PatientView.of(patient), count(all, AppointmentStatus.DONE),
				count(all, AppointmentStatus.NO_SHOW), count(all, AppointmentStatus.CANCELLED), upcoming, past);
	}

	private static long count(List<Appointment> all, AppointmentStatus status) {
		return all.stream().filter(a -> a.getStatus() == status).count();
	}

	/** Ближайший запланированный приём каждого из пациентов — для листа ожидания. */
	@Transactional(readOnly = true)
	public Map<Long, AppointmentView> upcoming(Collection<Long> patientIds) {
		var result = new HashMap<Long, AppointmentView>();
		if (patientIds.isEmpty()) {
			return result;
		}
		for (var a : appointments.findUpcoming(patientIds, clock.instant())) {
			result.putIfAbsent(a.getPatient().getId(), AppointmentView.of(a)); // первый — ближайший
		}
		return result;
	}

	@Transactional(readOnly = true)
	public AppointmentView get(long id) {
		return AppointmentView.of(require(id));
	}

	@Transactional
	public AppointmentView create(AppointmentRequests.Create request) {
		var patient = patients.require(request.patientId());
		var appointment = new Appointment(patient, request.startAt(), Duration.ofMinutes(request.durationMinutes()),
				clock.instant());
		return save(appointment);
	}

	@Transactional
	public AppointmentView reschedule(long id, AppointmentRequests.Reschedule request) {
		var appointment = require(id, request.version());
		appointment.reschedule(request.startAt(), Duration.ofMinutes(request.durationMinutes()));
		return save(appointment);
	}

	@Transactional
	public AppointmentView changeStatus(long id, AppointmentRequests.ChangeStatus request) {
		var appointment = require(id, request.version());
		appointment.changeStatus(request.status(), startOfTomorrow());
		return save(appointment);
	}

	@Transactional
	public AppointmentView changeReply(long id, AppointmentRequests.ChangeReply request) {
		var appointment = require(id, request.version());
		appointment.changeReply(request.reply());
		return save(appointment);
	}

	@Transactional
	public AppointmentView undo(long id, AppointmentRequests.Undo request) {
		var appointment = require(id, request.version());
		appointment.undo(request.previousStatus());
		return save(appointment);
	}

	@Transactional
	public void markReminderOpened(long id) {
		if (appointments.markReminderOpened(id, clock.instant()) == 0) {
			require(id);
			throw new ConflictException(ConflictException.INVALID_TRANSITION,
					"Напоминают только о запланированном приёме");
		}
	}

	/**
	 * Записать сейчас, а не при коммите. Без flush INSERT/UPDATE ушёл бы в базу уже после
	 * выхода из метода, и ошибку пересечения увидел бы не сервис, а Spring — ответом 500.
	 */
	private AppointmentView save(Appointment appointment) {
		try {
			return AppointmentView.of(appointments.saveAndFlush(appointment));
		}
		catch (ObjectOptimisticLockingFailureException e) {
			// Между чтением и записью приём изменили в другом окне
			throw ConflictException.stale();
		}
		catch (DataIntegrityViolationException e) {
			if (EXCLUSION_VIOLATION.equals(sqlState(e))) {
				throw ConflictException.timeTaken();
			}
			throw e;
		}
	}

	private Appointment require(long id) {
		return appointments.findById(id).orElseThrow(() -> new NotFoundException("Приём не найден"));
	}

	/**
	 * Приём той версии, которую видел врач. Иначе он решает по устаревшему экрану:
	 * например, отменяет приём, который ассистент только что перенёс.
	 */
	private Appointment require(long id, long version) {
		var appointment = require(id);
		if (appointment.getVersion() != version) {
			throw ConflictException.stale();
		}
		return appointment;
	}

	private Instant startOfTomorrow() {
		var zone = properties.timeZone();
		return LocalDate.now(clock.withZone(zone)).plusDays(1).atStartOfDay(zone).toInstant();
	}

	private static String sqlState(Throwable e) {
		for (var t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}

package kg.dental.appointment;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import kg.dental.DentalProperties;
import kg.dental.doctor.DoctorService;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientView;
import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import kg.dental.patient.Patient;
import kg.dental.servicetype.ServiceType;
import org.springframework.context.ApplicationEventPublisher;
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

	private final PreNoticeQuery preNotices;

	private final ApplicationEventPublisher events;

	private final ReviewQuery reviews;

	private final DoctorService doctors;

	private final PaymentQuery payments;

	AppointmentService(AppointmentRepository appointments, PatientService patients, Clock clock,
			DentalProperties properties, AppointmentStatsQuery stats, RecallQuery recall, PreNoticeQuery preNotices,
			ApplicationEventPublisher events, ReviewQuery reviews, DoctorService doctors, PaymentQuery payments) {
		this.payments = payments;
		this.doctors = doctors;
		this.reviews = reviews;
		this.appointments = appointments;
		this.patients = patients;
		this.clock = clock;
		this.properties = properties;
		this.stats = stats;
		this.recall = recall;
		this.preNotices = preNotices;
		this.events = events;
	}

	/** Обзор дня для уведомлений врачу: только числа и время. */
	@Transactional(readOnly = true)
	public DayOverview overview(LocalDate date, Instant now, long doctorId) {
		var zone = properties.timeZone();
		var active = appointments
			.findDay(doctorId, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
			.stream()
			.filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
			.toList();
		return new DayOverview(date, active.size(),
				active.stream().map(Appointment::getStartAt).min(Comparator.naturalOrder()).orElse(null),
				active.stream().map(Appointment::getEndAt).max(Comparator.naturalOrder()).orElse(null),
				active.stream().filter(a -> a.getReply() == PatientReply.CONFIRMED).count(),
				active.stream().filter(a -> a.getReply() == PatientReply.RESCHEDULE).count(),
				active.stream().filter(a -> a.getReminderOpenedAt() != null || a.getReply() != PatientReply.NONE).count(),
				active.stream()
					.filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED && a.getEndAt().isBefore(now))
					.count());
	}

	/** Прошедшие за 7 дней приёмы без отметки «пришёл» / «не пришёл» (R7/D9). */
	@Transactional(readOnly = true)
	public long unmarkedWeek(Instant now, long doctorId) {
		var zone = properties.timeZone();
		var weekAgo = LocalDate.ofInstant(now, zone).minusDays(7).atStartOfDay(zone).toInstant();
		return appointments.countUnmarked(doctorId, weekAgo, now);
	}

	/** Приёмы, о которых пора предупредить врача; каждый — один раз (PreNoticeQuery). */
	@Transactional
	public List<PreNotice> claimPreNotices(Instant now, Duration lead) {
		return preNotices.claim(now, now.plus(lead));
	}

	/**
	 * Оплата у состоявшегося приёма: сумма в целых сомах и способ; сумма null — снять отметку.
	 * Без версии: правка суммы не мешает другим действиям в карточке.
	 */
	@Transactional
	public AppointmentView setPayment(long id, Integer amountSom, PaymentMethod method) {
		if (amountSom != null && (amountSom < 0 || amountSom > 10_000_000)) {
			throw new InvalidFieldException("amountSom", "Сумма — целое число сомов, до 10 000 000");
		}
		if (amountSom != null && method == null) {
			throw new InvalidFieldException("method", "Выберите, как заплатили");
		}
		if (payments.set(id, amountSom, amountSom == null ? null : method, clock.instant()) == 0) {
			require(id);
			throw new ConflictException(ConflictException.INVALID_TRANSITION, "Оплату отмечают у состоявшегося приёма");
		}
		return get(id);
	}

	/** @param doctorId чья выручка; null — всего кабинета */
	@Transactional(readOnly = true)
	public RevenueView revenue(LocalDate from, LocalDate to, Long doctorId) {
		return payments.revenue(from, to, properties.timeZone(), doctorId);
	}

	/**
	 * Отчёт для Excel: CSV с «;» и BOM — так русский Excel открывает его сам, без мастера импорта.
	 * Без имён и телефонов пациентов: бухгалтерии они не нужны, а файл уходит в Telegram.
	 */
	@Transactional(readOnly = true)
	public String exportCsv(LocalDate from, LocalDate to, Long doctorId) {
		var zone = properties.timeZone();
		var date = DateTimeFormatter.ofPattern("dd.MM.yyyy");
		var time = DateTimeFormatter.ofPattern("HH:mm");
		var csv = new StringBuilder("\uFEFFНомер;Дата;Время;Врач;Услуга;Статус;Сумма, сом;Оплата\r\n");
		for (var r : payments.export(from, to, zone, doctorId)) {
			var at = r.startAt().atZone(zone);
			csv.append(r.id()).append(';').append(date.format(at)).append(';').append(time.format(at)).append(';')
				.append(cell(r.doctor())).append(';').append(cell(r.service() == null ? "" : r.service())).append(';')
				.append(STATUS_TEXT.getOrDefault(r.status(), r.status())).append(';')
				.append(r.paidSom() == null ? "" : r.paidSom()).append(';')
				.append(r.method() == null ? "" : METHOD_TEXT.get(r.method())).append("\r\n");
		}
		return csv.toString();
	}

	private static final Map<String, String> STATUS_TEXT = Map.of("SCHEDULED", "Запланирован", "DONE", "Состоялся",
			"NO_SHOW", "Неявка", "CANCELLED", "Отменён");

	private static final Map<String, String> METHOD_TEXT = Map.of("CASH", "Наличные", "TRANSFER", "Перевод", "CARD",
			"Карта");

	/** Ячейка CSV: «;», кавычки и перевод строки — в кавычках, как требует формат. */
	private static String cell(String text) {
		return text.matches(".*[;\"\r\n].*") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
	}

	/** Кого спросить об оценке: пришли в этот день (по Бишкеку), записывались через бота. Помечает «спросили». */
	@Transactional
	public List<ReviewRequest> claimReviewRequests(LocalDate day) {
		var zone = properties.timeZone();
		return reviews.claim(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(),
				clock.instant());
	}

	/** Оценка кнопкой в боте; пусто — не его приём, не спрашивали или уже оценил. */
	@Transactional
	public Optional<ReviewQuery.Rated> rateFromBot(long id, long telegramId, int rating) {
		return reviews.rate(id, telegramId, rating);
	}

	/** @param doctorId чьи оценки; null — всего кабинета */
	@Transactional(readOnly = true)
	public RatingSummary ratings(int days, Long doctorId) {
		return reviews.summary(clock.instant().minus(Duration.ofDays(days)), doctorId);
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
	public List<WeekStats> weeklyStats(int weeks, Long doctorId) {
		return stats.weeks(weeks, clock.instant(), properties.timeZone(), doctorId);
	}

	/** Приёмы календарного дня по Бишкеку, включая отменённые (в «Дне» они зачёркнуты). */
	@Transactional(readOnly = true)
	public List<DayCount> dayCounts(LocalDate from, int days, long doctorId) {
		return stats.days(from, days, properties.timeZone(), doctorId);
	}

	@Transactional(readOnly = true)
	public List<AppointmentView> day(LocalDate date, long doctorId) {
		var zone = properties.timeZone();
		var from = date.atStartOfDay(zone).toInstant();
		var to = date.plusDays(1).atStartOfDay(zone).toInstant();
		return appointments.findDay(doctorId, from, to).stream().map(AppointmentView::of).toList();
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
		doctors.requireActive(request.doctorId());
		var appointment = new Appointment(patient, request.doctorId(), request.startAt(),
				Duration.ofMinutes(request.durationMinutes()), clock.instant());
		return save(appointment);
	}

	@Transactional
	public AppointmentView reschedule(long id, AppointmentRequests.Reschedule request) {
		var appointment = require(id, request.version());
		appointment.reschedule(request.startAt(), Duration.ofMinutes(request.durationMinutes()));
		var saved = save(appointment);
		preNotices.reset(id); // новое время — предупредить о нём заново
		return saved;
	}

	@Transactional
	public AppointmentView changeStatus(long id, AppointmentRequests.ChangeStatus request) {
		var appointment = require(id, request.version());
		var wasRequest = appointment.getStatus() == AppointmentStatus.REQUESTED;
		appointment.changeStatus(request.status(), startOfTomorrow());
		var saved = save(appointment);
		if (wasRequest) {
			events.publishEvent(new RequestDecided(id, request.status() == AppointmentStatus.SCHEDULED));
		}
		return saved;
	}

	/** Заявка пациента из бота (модуль booking): время проверяет база, как у любой записи. */
	@Transactional
	public AppointmentView request(Patient patient, long doctorId, Instant startAt, ServiceType serviceType) {
		return save(Appointment.requested(patient, doctorId, startAt, serviceType, clock.instant()));
	}

	/** Пациент сам переносит свою запись в боте (свободное время проверил BookingService). */
	@Transactional
	public AppointmentView rescheduleByPatient(long id, Instant startAt) {
		var appointment = require(id);
		appointment.rescheduleByPatient(startAt);
		var saved = save(appointment); // гонка за то же время — 409 от базы
		preNotices.reset(id);
		return saved;
	}

	/** Пациент сам отменяет свою заявку или запись — без версии: у него нет экрана врача. */
	@Transactional
	public AppointmentView cancelByPatient(long id) {
		var appointment = require(id);
		appointment.changeStatus(AppointmentStatus.CANCELLED, startOfTomorrow());
		return save(appointment);
	}

	@Transactional
	public AppointmentView changeReply(long id, AppointmentRequests.ChangeReply request) {
		var appointment = require(id, request.version());
		appointment.changeReply(request.reply());
		return save(appointment);
	}

	/** Итог ответа из бота: changed — ответ поменялся (врачу сообщают только тогда). */
	public record BotReply(AppointmentView appointment, boolean changed) {
	}

	/**
	 * Пациент ответил кнопкой под напоминанием в боте. Только на свою запись (тот же Telegram id),
	 * запланированную и ещё не закончившуюся; иначе — пусто: запись уже перенесли или отменили.
	 * Без версии, как и отмена пациентом: врач увидит ответ, обновив экран.
	 */
	@Transactional
	public Optional<BotReply> replyFromBot(long id, long telegramId, PatientReply reply) {
		return appointments.findById(id)
			.filter(a -> Long.valueOf(telegramId).equals(a.getPatient().getTelegramId()))
			.filter(a -> a.getStatus() == AppointmentStatus.SCHEDULED && a.getEndAt().isAfter(clock.instant()))
			.map(a -> {
				var changed = a.getReply() != reply;
				a.changeReply(reply);
				return new BotReply(save(a), changed);
			});
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

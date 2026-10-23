package kg.dental.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import kg.dental.BookingProperties;
import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.SlotService;
import kg.dental.patient.PatientService;
import kg.dental.clinic.ClinicService;
import kg.dental.doctor.DoctorService;
import kg.dental.doctor.Staff;
import kg.dental.servicetype.ServiceTypeService;
import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import kg.dental.telegram.TelegramProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Самозапись через бота. Правила:
 * <ul>
 * <li>время — только из сетки свободного (те же правила, что у врача) и не раньше чем через
 * minLead, не дальше daysAhead;</li>
 * <li>у человека одна активная запись или заявка;</li>
 * <li>телефон — только тот, которым он поделился в Telegram; нужно согласие на обработку данных.</li>
 * </ul>
 * Заявка сразу занимает время (REQUESTED), врач подтверждает или отклоняет.
 */
@Service
class BookingService {

	static final String BOOKING_OFF = "booking-off";

	static final String ALREADY_BOOKED = "already-booked";

	private final BookingProperties booking;

	private final TelegramProperties telegram;

	private final DentalProperties properties;

	private final PatientService patients;

	private final AppointmentService appointments;

	private final SlotService slots;

	private final ServiceTypeService services;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final DoctorService doctors;

	private final Staff staff;

	private final ClinicService clinics;

	BookingService(BookingProperties booking, TelegramProperties telegram, DentalProperties properties,
			PatientService patients, AppointmentService appointments, SlotService slots, ServiceTypeService services,
			ApplicationEventPublisher events, Clock clock, DoctorService doctors, Staff staff, ClinicService clinics) {
		this.clinics = clinics;
		this.doctors = doctors;
		this.staff = staff;
		this.booking = booking;
		this.telegram = telegram;
		this.properties = properties;
		this.patients = patients;
		this.appointments = appointments;
		this.slots = slots;
		this.services = services;
		this.events = events;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public BookingViews.Me me(long telegramId) {
		var profile = patients.botProfile(telegramId);
		return new BookingViews.Me(staff.isStaff(telegramId), staff.isAdmin(telegramId),
				staff.doctorIdOf(telegramId).orElse(null), booking.enabled(),
				profile.map(PatientService.BotProfile::fullName).orElse(null),
				profile.map(PatientService.BotProfile::phone).orElse(null), current(telegramId),
				booking.enabled() ? services.active() : List.of(), telegram.clinicContact(), booking.daysAhead(),
				doctors.active(), clinics.view());
	}

	@Transactional(readOnly = true)
	public BookingViews.Slots slots(LocalDate date, long doctorId, long serviceTypeId) {
		requireEnabled();
		var today = LocalDate.ofInstant(clock.instant(), properties.timeZone());
		if (date.isBefore(today) || date.isAfter(today.plusDays(booking.daysAhead()))) {
			return new BookingViews.Slots(false, List.of(), List.of(), null);
		}
		var service = services.requireActive(serviceTypeId);
		doctors.requireActive(doctorId);
		var day = slots.slots(date, doctorId, Duration.ofMinutes(service.getDurationMinutes()), null);
		var earliest = earliest();
		return new BookingViews.Slots(day.workingDay(),
				day.starts().stream().filter(start -> !start.isBefore(earliest)).toList(), day.breaks(), day.dayOff());
	}

	@Transactional
	public BookingViews.Booking book(long telegramId, long doctorId, long serviceTypeId, Instant startAt,
			String fullName, boolean consent) {
		requireEnabled();
		if (!consent) {
			throw new InvalidFieldException("consent", "Нужно согласие на обработку персональных данных");
		}
		if (current(telegramId) != null) {
			throw new ConflictException(ALREADY_BOOKED, "У вас уже есть запись — сначала отмените её");
		}
		var service = services.requireActive(serviceTypeId);
		var date = LocalDate.ofInstant(startAt, properties.timeZone());
		// Только время из сетки свободного: те же рабочие часы и шаг, что у врача
		if (!slots(date, doctorId, serviceTypeId).starts().contains(startAt)) {
			throw ConflictException.timeTaken();
		}
		var patient = patients.forSelfBooking(telegramId, fullName);
		// гонка двух заявок к одному врачу — 409 от базы
		var appointment = appointments.request(patient, doctorId, startAt, service);
		events.publishEvent(new BookingEvents.Requested(appointment.id()));
		return BookingViews.Booking.of(appointment, doctors.nameOf(doctorId));
	}

	@Transactional
	public void cancel(long telegramId) {
		var current = current(telegramId);
		if (current == null) {
			throw new NotFoundException("Записи нет");
		}
		appointments.cancelByPatient(current.id());
		events.publishEvent(new BookingEvents.CancelledByPatient(current.id()));
	}

	/** Активная запись или заявка человека, null — нет. */
	private BookingViews.Booking current(long telegramId) {
		return patients.byTelegram(telegramId)
			.map(p -> appointments.upcoming(List.of(p.getId())).get(p.getId()))
			.map(a -> BookingViews.Booking.of(a, doctors.nameOf(a.doctorId())))
			.orElse(null);
	}

	private Instant earliest() {
		return clock.instant().plus(booking.minLead());
	}

	private void requireEnabled() {
		if (!booking.enabled()) {
			throw new ConflictException(BOOKING_OFF, "Запись через бота выключена. Позвоните в клинику: "
					+ telegram.clinicContact());
		}
	}

}

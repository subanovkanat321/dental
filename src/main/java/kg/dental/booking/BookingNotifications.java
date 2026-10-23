package kg.dental.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentView;
import kg.dental.appointment.RequestDecided;
import kg.dental.clinic.ClinicService;
import kg.dental.digest.DoctorNotifier;
import kg.dental.patient.Lang;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientTexts;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Сообщения в бот о самозаписи — после коммита (AFTER_COMMIT): если запись откатилась, ни врач,
 * ни пациент не получат сообщения о том, чего нет. Врачу — только время и услуга, без имени
 * и телефона (Telegram — сторонний сервис). Пациенту — о его собственной записи.
 */
@Component
class BookingNotifications {

	private static final Locale RU = Locale.forLanguageTag("ru");

	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", RU);

	private final DoctorNotifier notifier;

	private final AppointmentService appointments;

	private final PatientService patients;

	private final TelegramProperties telegram;

	private final DentalProperties properties;

	private final ClinicService clinics;

	private final PatientTexts texts;

	BookingNotifications(DoctorNotifier notifier, AppointmentService appointments, PatientService patients,
			TelegramProperties telegram, DentalProperties properties, ClinicService clinics, PatientTexts texts) {
		this.texts = texts;
		this.clinics = clinics;
		this.notifier = notifier;
		this.appointments = appointments;
		this.patients = patients;
		this.telegram = telegram;
		this.properties = properties;
	}

	@TransactionalEventListener
	void requested(BookingEvents.Requested event) {
		if (!notifier.enabled()) {
			return;
		}
		var a = appointments.get(event.appointmentId());
		notifier.send(a.doctorId(), "Новая заявка", requestedText(a, properties.timeZone()),
				List.of(new DoctorNotifier.Link("Открыть заявку", "appointment=" + a.id())));
	}

	@TransactionalEventListener
	void cancelledByPatient(BookingEvents.CancelledByPatient event) {
		if (!notifier.enabled()) {
			return;
		}
		var a = appointments.get(event.appointmentId());
		notifier.send(a.doctorId(), "Пациент отменил", "Запись отменена пациентом: " + shortTime(a.startAt(), properties.timeZone())
				+ ". Время освободилось.", List.of(new DoctorNotifier.Link("Открыть приём", "appointment=" + a.id())));
	}

	@TransactionalEventListener
	void rescheduledByPatient(BookingEvents.RescheduledByPatient event) {
		if (!notifier.enabled()) {
			return;
		}
		var a = appointments.get(event.appointmentId());
		var zone = properties.timeZone();
		notifier.send(a.doctorId(), "Пациент перенёс", "Пациент перенёс запись: " + shortTime(event.previousStart(), zone)
				+ " → " + shortTime(a.startAt(), zone) + ". Подтвердите новое время в приложении.",
				List.of(new DoctorNotifier.Link("Открыть заявку", "appointment=" + a.id())));
	}

	@TransactionalEventListener
	void decided(RequestDecided event) {
		if (!notifier.enabled()) {
			return;
		}
		var a = appointments.get(event.appointmentId());
		var clinic = clinics.view();
		patients.telegramIdOf(a.patient().id()).ifPresent(chatId -> {
			var lang = texts.langOf(chatId);
			var text = decidedText(texts, lang, a, event.confirmed(), telegram.clinicContact(), properties.timeZone());
			if (event.confirmed() && clinic.address() != null) {
				text += "\n" + texts.get(lang, "clinic.address", clinic.address());
			}
			var map = event.confirmed() ? PatientReminders.mapButton(texts, lang, clinic) : List.<TelegramClient.Button>of();
			var button = texts.get(lang, event.confirmed() ? "decided.mine-button" : "decided.other-button");
			notifier.sendToPatient(chatId, event.confirmed() ? "Заявка подтверждена" : "Заявка отклонена", text,
					List.of(new DoctorNotifier.Link(button, null)), map);
		});
	}

	/** «Новая заявка: вт, 24 окт, 10:00 — Осмотр и консультация (30 мин).» */
	static String requestedText(AppointmentView a, ZoneId zone) {
		var minutes = Duration.between(a.startAt(), a.endAt()).toMinutes();
		return "Новая заявка: " + shortTime(a.startAt(), zone) + " — " + a.serviceName() + " (" + minutes
				+ " мин). Подтвердите или отклоните в приложении.";
	}

	/** Услугу (её название) врач вводит по-русски — она остаётся как есть и в кыргызском тексте. */
	static String decidedText(PatientTexts texts, Lang lang, AppointmentView a, boolean confirmed, String clinicContact,
			ZoneId zone) {
		var when = lang.dateTime(a.startAt().atZone(zone));
		if (confirmed) {
			var text = texts.get(lang, "decided.confirmed", when, a.serviceName());
			return clinicContact.isEmpty() ? text : text + texts.get(lang, "bot.clinic-phone", clinicContact);
		}
		return texts.get(lang, "decided.rejected", when);
	}

	private static String shortTime(Instant instant, ZoneId zone) {
		return SHORT.format(instant.atZone(zone)).replace(".", "");
	}

}

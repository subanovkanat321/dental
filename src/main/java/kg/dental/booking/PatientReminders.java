package kg.dental.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentStatus;
import kg.dental.appointment.AppointmentView;
import kg.dental.appointment.PatientReply;
import kg.dental.digest.DoctorNotifier;
import kg.dental.patient.PatientService;
import kg.dental.shared.ConflictException;
import kg.dental.telegram.BotCallbacks;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import kg.dental.telegram.TelegramProperties;
import org.springframework.stereotype.Component;

/**
 * Напоминание в бот пациенту, который записывался через бота: сообщение с кнопками
 * «Приду» / «Нужно перенести». Нажатие сразу ставит ответ в карточке приёма, а врачу
 * приходит уведомление — без имени и телефона, только время.
 */
@Component
class PatientReminders implements BotCallbacks {

	/** callback_data: «reply:42:C» — приём 42, «приду»; R — «перенести». */
	private static final Pattern DATA = Pattern.compile("reply:(\\d{1,18}):([CR])");

	private static final Locale RU = Locale.forLanguageTag("ru");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final AppointmentService appointments;

	private final PatientService patients;

	private final TelegramClient telegram;

	private final DoctorNotifier notifier;

	private final TelegramProperties telegramProperties;

	private final DentalProperties properties;

	private final Clock clock;

	PatientReminders(AppointmentService appointments, PatientService patients, TelegramClient telegram,
			DoctorNotifier notifier, TelegramProperties telegramProperties, DentalProperties properties, Clock clock) {
		this.appointments = appointments;
		this.patients = patients;
		this.telegram = telegram;
		this.notifier = notifier;
		this.telegramProperties = telegramProperties;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Кнопка «Напомнить в Telegram» у врача. Сначала отправка, потом отметка «напомнили»:
	 * не дошло — отметки нет, и врач видит ошибку, а не ложное «Ждём ответа».
	 */
	AppointmentView remind(long id) {
		var a = appointments.get(id);
		if (a.status() != AppointmentStatus.SCHEDULED || !a.endAt().isAfter(clock.instant())) {
			throw new ConflictException(ConflictException.INVALID_TRANSITION, "Напоминают только о предстоящем приёме");
		}
		var chatId = patients.telegramIdOf(a.patient().id())
			.orElseThrow(() -> new ConflictException(ConflictException.NOT_DELIVERED,
					"Пациент не записывался через бота — напомните в WhatsApp или позвоните"));
		if (!notifier.enabled()) {
			throw new ConflictException(ConflictException.NOT_DELIVERED, "Бот не настроен на сервере");
		}
		try {
			telegram.sendMessage(chatId, reminderText(a, properties.clinicSignature(), properties.timeZone(),
					LocalDate.now(clock.withZone(properties.timeZone()))),
					List.of(TelegramClient.Button.callback("Приду", "reply:" + id + ":C"),
							TelegramClient.Button.callback("Нужно перенести", "reply:" + id + ":R")));
		}
		catch (TelegramException e) {
			throw new ConflictException(ConflictException.NOT_DELIVERED,
					"Не доставлено: пациент мог удалить чат с ботом. Напомните в WhatsApp или позвоните");
		}
		appointments.markReminderOpened(id);
		return appointments.get(id);
	}

	@Override
	public Optional<Answer> handle(long fromId, String data) {
		var match = DATA.matcher(data);
		if (!match.matches()) {
			return Optional.empty();
		}
		var confirmed = "C".equals(match.group(2));
		var reply = confirmed ? PatientReply.CONFIRMED : PatientReply.RESCHEDULE;
		var result = appointments.replyFromBot(Long.parseLong(match.group(1)), fromId, reply);
		if (result.isEmpty()) {
			// Чужой id в подменённой кнопке или запись уже изменилась — ответ одинаковый
			return Optional.of(new Answer("Эта запись уже изменилась — откройте приложение", null));
		}
		var a = result.get().appointment();
		if (result.get().changed() && notifier.enabled()) {
			notifier.send("Ответ на напоминание", doctorText(a, confirmed, properties.timeZone()),
					List.of(new DoctorNotifier.Link("Открыть приём", "appointment=" + a.id())));
		}
		return Optional.of(confirmed
				? new Answer("Спасибо!", "Спасибо! Ждём вас " + when(a.startAt(), properties.timeZone(),
						LocalDate.now(clock.withZone(properties.timeZone()))) + ".")
				: new Answer("Передали врачу", rescheduleText(telegramProperties.clinicContact())));
	}

	/** «Здравствуйте, Айгуль! Напоминаем: завтра в 10:00 приём у стоматолога. …» — как в WhatsApp. */
	static String reminderText(AppointmentView a, String signature, ZoneId zone, LocalDate today) {
		var text = "Здравствуйте, " + a.patient().fullName() + "! Напоминаем: " + when(a.startAt(), zone, today)
				+ " приём у стоматолога. Нажмите кнопку ниже: «Приду» или «Нужно перенести».";
		return signature == null || signature.isBlank() ? text : text + " " + signature;
	}

	static String doctorText(AppointmentView a, boolean confirmed, ZoneId zone) {
		var at = a.startAt().atZone(zone);
		var time = DATE.format(at) + ", " + TIME.format(at);
		return confirmed ? "Ответ на напоминание: приём " + time + " — пациент придёт."
				: "Ответ на напоминание: приём " + time + " — пациент просит перенести. Откройте приём, чтобы связаться.";
	}

	static String rescheduleText(String clinicContact) {
		var text = "Хорошо, передали врачу — с вами свяжутся, чтобы подобрать другое время.";
		return clinicContact.isEmpty() ? text : text + " Телефон клиники: " + clinicContact;
	}

	/** «сегодня в 10:00», «завтра в 10:00» или «в четверг, 26 октября, в 10:00». */
	static String when(Instant startAt, ZoneId zone, LocalDate today) {
		var at = startAt.atZone(zone);
		var time = "в " + TIME.format(at);
		if (at.toLocalDate().equals(today)) {
			return "сегодня " + time;
		}
		if (at.toLocalDate().equals(today.plusDays(1))) {
			return "завтра " + time;
		}
		return DATE.format(at) + ", " + time;
	}

}

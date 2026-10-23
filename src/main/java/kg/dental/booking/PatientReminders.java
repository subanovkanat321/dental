package kg.dental.booking;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentStatus;
import kg.dental.appointment.AppointmentView;
import kg.dental.appointment.PatientReply;
import kg.dental.clinic.ClinicService;
import kg.dental.clinic.ClinicView;
import kg.dental.digest.DoctorNotifier;
import kg.dental.patient.Lang;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientTexts;
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

	private final ClinicService clinics;

	private final PatientTexts texts;

	PatientReminders(AppointmentService appointments, PatientService patients, TelegramClient telegram,
			DoctorNotifier notifier, TelegramProperties telegramProperties, DentalProperties properties, Clock clock,
			ClinicService clinics, PatientTexts texts) {
		this.texts = texts;
		this.clinics = clinics;
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
			var clinic = clinics.view();
			var lang = texts.langOf(chatId);
			var buttons = new ArrayList<>(List.of(
					TelegramClient.Button.callback(texts.get(lang, "reminder.coming"), "reply:" + id + ":C"),
					TelegramClient.Button.callback(texts.get(lang, "reminder.reschedule"), "reply:" + id + ":R")));
			buttons.addAll(mapButton(texts, lang, clinic));
			telegram.sendMessage(chatId, reminderText(lang, a, properties.clinicSignature(),
					LocalDate.now(clock.withZone(properties.timeZone())), clinic.address()), buttons);
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
		var lang = texts.langOf(fromId);
		var result = appointments.replyFromBot(Long.parseLong(match.group(1)), fromId, reply);
		if (result.isEmpty()) {
			// Чужой id в подменённой кнопке или запись уже изменилась — ответ одинаковый
			return Optional.of(new Answer(texts.get(lang, "bot.stale"), null));
		}
		var a = result.get().appointment();
		if (result.get().changed() && notifier.enabled()) {
			notifier.send(a.doctorId(), "Ответ на напоминание", doctorText(a, confirmed, properties.timeZone()),
					List.of(new DoctorNotifier.Link("Открыть приём", "appointment=" + a.id())));
		}
		var zone = properties.timeZone();
		return Optional.of(confirmed
				? new Answer(texts.get(lang, "reply.coming.popup"), texts.get(lang, "reply.coming",
						lang.when(a.startAt().atZone(zone), LocalDate.now(clock.withZone(zone)))))
				: new Answer(texts.get(lang, "reply.reschedule.popup"), rescheduleText(lang, telegramProperties.clinicContact())));
	}

	/** «Здравствуйте, Айгуль! Напоминаем: завтра в 10:00 приём у стоматолога. …» — как в WhatsApp. */
	String reminderText(Lang lang, AppointmentView a, String signature, LocalDate today, String address) {
		var text = texts.get(lang, "reminder.text", a.patient().fullName(), lang.when(a.startAt().atZone(properties.timeZone()), today));
		if (address != null) {
			text += "\n" + texts.get(lang, "clinic.address", address);
		}
		return signature == null || signature.isBlank() ? text : text + "\n" + signature;
	}

	/** «📍 Как добраться (2ГИС)» — если в «О клинике» есть ссылка; иначе без кнопки. */
	static List<TelegramClient.Button> mapButton(PatientTexts texts, Lang lang, ClinicView clinic) {
		return clinic.mapUrl() == null ? List.of()
				: List.of(TelegramClient.Button.link(texts.get(lang, "clinic.map-button"), URI.create(clinic.mapUrl())));
	}

	static String doctorText(AppointmentView a, boolean confirmed, ZoneId zone) {
		var at = a.startAt().atZone(zone);
		var time = DATE.format(at) + ", " + TIME.format(at);
		return confirmed ? "Ответ на напоминание: приём " + time + " — пациент придёт."
				: "Ответ на напоминание: приём " + time + " — пациент просит перенести. Откройте приём, чтобы связаться.";
	}

	private String rescheduleText(Lang lang, String clinicContact) {
		var text = texts.get(lang, "reply.reschedule");
		return clinicContact.isEmpty() ? text : text + texts.get(lang, "bot.clinic-phone", clinicContact);
	}

	/** «сегодня в 10:00», «завтра в 10:00» или «понедельник, 26 октября, в 10:00» — по-русски. */
	static String when(Instant startAt, ZoneId zone, LocalDate today) {
		return Lang.RU.when(startAt.atZone(zone), today);
	}

}

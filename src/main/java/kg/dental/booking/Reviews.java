package kg.dental.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.digest.DoctorNotifier;
import kg.dental.digest.NotifyProperties;
import kg.dental.patient.Lang;
import kg.dental.patient.PatientTexts;
import kg.dental.telegram.BotCallbacks;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Отзыв после приёма. На следующий день (REVIEW_TIME, по умолчанию 12:00) бот спрашивает
 * у пришедших, кто записывался через бота: «Как прошёл приём?» — пять кнопок ⭐. Оценка
 * видна в карточке приёма и в «Статистике», врачу приходит уведомление — без имени.
 */
@Configuration(proxyBeanMethods = false)
class Reviews implements SchedulingConfigurer, BotCallbacks {

	private static final Logger log = LoggerFactory.getLogger(Reviews.class);

	/** callback_data: «rate:42:5» — приём 42, пять звёзд. */
	private static final Pattern DATA = Pattern.compile("rate:(\\d{1,18}):([1-5])");

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm", Locale.forLanguageTag("ru"));

	private final AppointmentService appointments;

	private final TelegramClient telegram;

	private final DoctorNotifier notifier;

	private final NotifyProperties notify;

	private final DentalProperties properties;

	private final Clock clock;

	private final PatientTexts texts;

	Reviews(AppointmentService appointments, TelegramClient telegram, DoctorNotifier notifier, NotifyProperties notify,
			DentalProperties properties, Clock clock, PatientTexts texts) {
		this.texts = texts;
		this.appointments = appointments;
		this.telegram = telegram;
		this.notifier = notifier;
		this.notify = notify;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		if (notify.reviews()) {
			LocalTime time = notify.reviewTime();
			registrar.addTriggerTask(this::run, new CronTrigger(
					"0 %d %d * * *".formatted(time.getMinute(), time.getHour()), properties.timeZone()));
		}
	}

	void run() {
		if (notifier.enabled()) {
			ask(clock.instant());
		}
	}

	/** Спросить всех, кто пришёл вчера. @return скольким отправлено */
	int ask(Instant now) {
		var yesterday = LocalDate.ofInstant(now, properties.timeZone()).minusDays(1);
		MDC.put("jobId", "reviews-" + yesterday);
		try {
			var requests = appointments.claimReviewRequests(yesterday);
			var sent = 0;
			for (var r : requests) {
				try {
					telegram.sendKeyboard(r.telegramId(), texts.get(texts.langOf(r.telegramId()), "review.ask", r.fullName()),
							List.of(stars(r.appointmentId())));
					sent++;
				}
				catch (TelegramException e) {
					// Отметка «спросили» уже стоит: второй раз не спросим — и это правильно
					log.info("Вопрос об оценке не доставлен: {}", e.getMessage());
				}
			}
			log.info("Отзывы: спросили {} из {}", sent, requests.size());
			return sent;
		}
		finally {
			MDC.remove("jobId");
		}
	}

	@Override
	public Optional<Answer> handle(long fromId, String data) {
		var match = DATA.matcher(data);
		if (!match.matches()) {
			return Optional.empty();
		}
		var rating = Integer.parseInt(match.group(2));
		var lang = texts.langOf(fromId);
		var rated = appointments.rateFromBot(Long.parseLong(match.group(1)), fromId, rating);
		if (rated.isEmpty()) {
			return Optional.of(new Answer(texts.get(lang, "review.already"), null));
		}
		if (notifier.enabled()) {
			notifier.send(rated.get().doctorId(), "Оценка приёма",
					doctorText(rated.get().startAt(), rating, properties.timeZone()), List.of());
		}
		return Optional.of(new Answer(texts.get(lang, "review.thanks.popup"),
				texts.get(lang, rating >= 4 ? "review.thanks.good" : "review.thanks.bad")));
	}

	static String doctorText(Instant startAt, int rating, ZoneId zone) {
		return "⭐".repeat(rating) + " Оценка приёма " + WHEN.format(startAt.atZone(zone)).replace(".", "") + " — " + rating
				+ " из 5.";
	}

	private static List<TelegramClient.Button> stars(long appointmentId) {
		return IntStream.rangeClosed(1, 5)
			.mapToObj(n -> TelegramClient.Button.callback(n + " ⭐", "rate:" + appointmentId + ":" + n))
			.toList();
	}

}

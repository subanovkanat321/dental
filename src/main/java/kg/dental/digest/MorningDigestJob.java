package kg.dental.digest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import kg.dental.telegram.TelegramProperties;
import kg.dental.telegram.TelegramProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Утренняя сводка врачу в 9:00 по Бишкеку: сколько приёмов завтра, во сколько первый,
 * сколько прошедших не отмечено. Кнопка под сообщением открывает «Завтра» в Mini App —
 * вечером по нему врач напоминает пациентам.
 * <p>
 * Один экземпляр приложения, поэтому без ShedLock: дважды сводка не уйдёт.
 */
@Component
class MorningDigestJob {

	private static final Logger log = LoggerFactory.getLogger(MorningDigestJob.class);

	private final AppointmentService appointments;

	private final TelegramClient telegram;

	private final TelegramProperties telegramProperties;

	private final DentalProperties properties;

	private final Clock clock;

	MorningDigestJob(AppointmentService appointments, TelegramClient telegram, TelegramProperties telegramProperties,
			DentalProperties properties, Clock clock) {
		this.appointments = appointments;
		this.telegram = telegram;
		this.telegramProperties = telegramProperties;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 9 * * *", zone = "${dental.time-zone}")
	void run() {
		if (telegramProperties.mode() == Mode.OFF || !telegramProperties.configured()) {
			return;
		}
		send(clock.instant());
	}

	/** Отдельно от run(): тест вызывает с нужным «сейчас», не дожидаясь 9:00. */
	void send(Instant now) {
		var zone = properties.timeZone();
		var tomorrow = LocalDate.ofInstant(now, zone).plusDays(1);
		// Номер задачи в каждой строке лога, как requestId у запросов: по нему находятся
		// все строки одного запуска
		MDC.put("jobId", "digest-" + tomorrow);
		try {
			var text = DigestText.of(tomorrow, appointments.summary(tomorrow, now), zone);
			var sent = 0;
			for (var chatId : telegramProperties.allowedIds()) {
				try {
					var url = telegramProperties.publicUrl();
					if (url != null && "https".equals(url.getScheme())) {
						telegram.sendMessage(chatId, text, "Открыть завтра", URI.create(url + "?day=tomorrow"));
					}
					else {
						telegram.sendMessage(chatId, text);
					}
					sent++;
				}
				catch (TelegramException e) {
					// Один получатель не получил (например, не нажимал /start) — остальным всё равно шлём
					log.warn("Сводка не доставлена: {}", e.getMessage());
				}
			}
			log.info("Утренняя сводка: отправлено {} из {}", sent, telegramProperties.allowedIds().size());
		}
		finally {
			MDC.remove("jobId");
		}
	}

}

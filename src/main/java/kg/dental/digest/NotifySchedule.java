package kg.dental.digest;

import java.time.Duration;
import java.time.LocalTime;

import kg.dental.DentalProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Расписание уведомлений из настроек (NotifyProperties), а не зашитое в @Scheduled: время
 * меняется переменной окружения без правки кода. Без бота (TELEGRAM_MODE=off, тесты) задачи
 * ничего не делают.
 */
@Configuration(proxyBeanMethods = false)
class NotifySchedule implements SchedulingConfigurer {

	private final NotifyProperties notify;

	private final DentalProperties properties;

	private final DoctorNotifier notifier;

	private final MorningDigestJob morning;

	private final EveningDigestJob evening;

	private final PreNoticeJob preNotice;

	NotifySchedule(NotifyProperties notify, DentalProperties properties, DoctorNotifier notifier,
			MorningDigestJob morning, EveningDigestJob evening, PreNoticeJob preNotice) {
		this.notify = notify;
		this.properties = properties;
		this.notifier = notifier;
		this.morning = morning;
		this.evening = evening;
		this.preNotice = preNotice;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addTriggerTask(() -> runIfEnabled(morning::run), daily(notify.morningTime()));
		registrar.addTriggerTask(() -> runIfEnabled(evening::run), daily(notify.eveningTime()));
		if (notify.beforeMinutes() > 0) {
			registrar.addFixedDelayTask(() -> runIfEnabled(preNotice::run), Duration.ofMinutes(1));
		}
	}

	private void runIfEnabled(Runnable task) {
		if (notifier.enabled()) {
			task.run();
		}
	}

	/** Каждый день в это время по Бишкеку: cron «сек мин час * * *». */
	private CronTrigger daily(LocalTime time) {
		return new CronTrigger("0 %d %d * * *".formatted(time.getMinute(), time.getHour()), properties.timeZone());
	}

}

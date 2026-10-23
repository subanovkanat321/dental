package kg.dental.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentStatus;
import kg.dental.appointment.PatientReply;
import kg.dental.digest.DoctorNotifier;
import kg.dental.digest.NotifyProperties;
import kg.dental.shared.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Вечером (PATIENT_REMINDER_TIME, по умолчанию 18:00) бот сам напоминает о завтрашнем приёме
 * всем, кто записался через бота, — тем же сообщением с кнопками «Приду» / «Нужно перенести»,
 * что и кнопка врача. Кому уже напомнили или кто уже ответил — не трогает. В 18:30 вечерняя
 * сводка врачу их уже посчитает: напоминать вручную остаётся только остальным, в WhatsApp.
 */
@Configuration(proxyBeanMethods = false)
class AutoReminders implements SchedulingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(AutoReminders.class);

	private final PatientReminders reminders;

	private final AppointmentService appointments;

	private final DoctorNotifier notifier;

	private final NotifyProperties notify;

	private final DentalProperties properties;

	private final Clock clock;

	AutoReminders(PatientReminders reminders, AppointmentService appointments, DoctorNotifier notifier,
			NotifyProperties notify, DentalProperties properties, Clock clock) {
		this.reminders = reminders;
		this.appointments = appointments;
		this.notifier = notifier;
		this.notify = notify;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		if (notify.patientReminders()) {
			LocalTime time = notify.patientReminderTime();
			registrar.addTriggerTask(this::run, new CronTrigger(
					"0 %d %d * * *".formatted(time.getMinute(), time.getHour()), properties.timeZone()));
		}
	}

	void run() {
		if (notifier.enabled()) {
			send(clock.instant());
		}
	}

	/** @return скольким напомнили */
	int send(Instant now) {
		var tomorrow = LocalDate.ofInstant(now, properties.timeZone()).plusDays(1);
		MDC.put("jobId", "patient-reminders-" + tomorrow);
		try {
			var due = appointments.day(tomorrow)
				.stream()
				.filter(a -> a.status() == AppointmentStatus.SCHEDULED && a.patient().telegram()
						&& a.reminderOpenedAt() == null && a.reply() == PatientReply.NONE)
				.toList();
			var sent = 0;
			for (var a : due) {
				try {
					reminders.remind(a.id());
					sent++;
				}
				catch (ConflictException e) {
					// Пациент удалил чат с ботом или приём только что изменили — врач увидит «Не напомнили»
					log.info("Автонапоминание не отправлено: {}", e.getMessage());
				}
			}
			log.info("Автонапоминания: отправлено {} из {}", sent, due.size());
			return sent;
		}
		finally {
			MDC.remove("jobId");
		}
	}

}

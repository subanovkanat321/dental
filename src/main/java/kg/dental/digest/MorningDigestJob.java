package kg.dental.digest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Утренняя сводка (по умолчанию 9:00 по Бишкеку): сегодня — сколько приёмов, с какого по какое
 * время, кто подтвердил; завтра — сколько и во сколько первый; неотмеченные за неделю.
 * Расписание — NotifySchedule.
 */
@Component
class MorningDigestJob {

	private final AppointmentService appointments;

	private final DoctorNotifier notifier;

	private final DentalProperties properties;

	private final Clock clock;

	MorningDigestJob(AppointmentService appointments, DoctorNotifier notifier, DentalProperties properties,
			Clock clock) {
		this.appointments = appointments;
		this.notifier = notifier;
		this.properties = properties;
		this.clock = clock;
	}

	void run() {
		send(clock.instant());
	}

	/** Отдельно от run(): тест вызывает с нужным «сейчас». */
	void send(Instant now) {
		var zone = properties.timeZone();
		var today = LocalDate.ofInstant(now, zone);
		// Номер задачи в каждой строке лога, как requestId у запросов
		MDC.put("jobId", "morning-" + today);
		try {
			var text = DigestText.morning(appointments.overview(today, now),
					appointments.overview(today.plusDays(1), now), appointments.unmarkedWeek(now), zone);
			notifier.send("Утренняя сводка", text, List.of(new DoctorNotifier.Link("Открыть сегодня", null),
					new DoctorNotifier.Link("Открыть завтра", "day=tomorrow")));
		}
		finally {
			MDC.remove("jobId");
		}
	}

}

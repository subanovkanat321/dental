package kg.dental.digest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Вечерняя сводка (по умолчанию 18:30): кому ещё не напомнили на завтра — главное против
 * неявок — и что не отмечено сегодня — без отметок статистика неявок врёт. Всё сделано —
 * сообщения нет: напоминание о сделанном только приучило бы врача не читать бота.
 */
@Component
class EveningDigestJob {

	private final AppointmentService appointments;

	private final DoctorNotifier notifier;

	private final DentalProperties properties;

	private final Clock clock;

	EveningDigestJob(AppointmentService appointments, DoctorNotifier notifier, DentalProperties properties,
			Clock clock) {
		this.appointments = appointments;
		this.notifier = notifier;
		this.properties = properties;
		this.clock = clock;
	}

	void run() {
		send(clock.instant());
	}

	void send(Instant now) {
		var zone = properties.timeZone();
		var date = LocalDate.ofInstant(now, zone);
		MDC.put("jobId", "evening-" + date);
		try {
			var today = appointments.overview(date, now);
			var tomorrow = appointments.overview(date.plusDays(1), now);
			var text = DigestText.evening(today, tomorrow);
			if (text == null) {
				return;
			}
			var links = new ArrayList<DoctorNotifier.Link>();
			if (tomorrow.active() > tomorrow.ready()) {
				links.add(new DoctorNotifier.Link("Напомнить на завтра", "day=tomorrow"));
			}
			if (today.unmarked() > 0) {
				links.add(new DoctorNotifier.Link("Отметить сегодня", null));
			}
			notifier.send("Вечерняя сводка", text, links);
		}
		finally {
			MDC.remove("jobId");
		}
	}

}

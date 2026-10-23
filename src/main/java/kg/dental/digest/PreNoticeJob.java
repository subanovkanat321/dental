package kg.dental.digest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import kg.dental.DentalProperties;
import kg.dental.appointment.AppointmentService;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Раз в минуту: приёмы, которые начнутся в ближайшие N минут (NOTIFY_BEFORE_MINUTES), —
 * «Через 15 мин, в 10:00 — приём (30 мин). Пациент подтвердил.» Каждый приём — один раз:
 * AppointmentService.claimPreNotices забирает его атомарно.
 */
@Component
class PreNoticeJob {

	private final AppointmentService appointments;

	private final DoctorNotifier notifier;

	private final NotifyProperties notify;

	private final DentalProperties properties;

	private final Clock clock;

	PreNoticeJob(AppointmentService appointments, DoctorNotifier notifier, NotifyProperties notify,
			DentalProperties properties, Clock clock) {
		this.appointments = appointments;
		this.notifier = notifier;
		this.notify = notify;
		this.properties = properties;
		this.clock = clock;
	}

	void run() {
		send(clock.instant());
	}

	void send(Instant now) {
		for (var notice : appointments.claimPreNotices(now, Duration.ofMinutes(notify.beforeMinutes()))) {
			MDC.put("jobId", "pre-notice-" + notice.id());
			try {
				notifier.send(notice.doctorId(), "Перед приёмом", DigestText.preNotice(notice, now, properties.timeZone()),
						List.of(new DoctorNotifier.Link("Открыть приём", "appointment=" + notice.id())));
			}
			finally {
				MDC.remove("jobId");
			}
		}
	}

}

package kg.dental.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import kg.dental.DentalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Свободное время дня (DD2, DD11, ER4): рабочие часы минус занятые приёмы, без прошедшего.
 * Расчёт — в FreeTime, здесь только данные: расписание, приёмы из базы, «сейчас».
 */
@Service
public class SlotService {

	private final AppointmentRepository appointments;

	private final WorkSchedule schedule;

	private final DentalProperties properties;

	private final Clock clock;

	SlotService(AppointmentRepository appointments, WorkSchedule schedule, DentalProperties properties, Clock clock) {
		this.appointments = appointments;
		this.schedule = schedule;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @param duration длина приёма для сетки начал; null — только окна
	 * @param excludeId приём, который переносят: его старое время считается свободным
	 */
	@Transactional(readOnly = true)
	public SlotsView slots(LocalDate date, Duration duration, Long excludeId) {
		if (!schedule.isWorkingDay(date)) {
			return new SlotsView(false, schedule.start(), schedule.end(), List.of(), List.of());
		}
		var zone = properties.timeZone();
		var from = date.atTime(schedule.start()).atZone(zone).toInstant();
		var to = date.atTime(schedule.end()).atZone(zone).toInstant();
		var busy = appointments.findBusy(from, to, excludeId == null ? -1 : excludeId);
		var free = FreeTime.free(from, to, busy, clock.instant());
		var starts = duration == null ? List.<Instant>of() : FreeTime.starts(free, duration);
		return new SlotsView(true, schedule.start(), schedule.end(), free, starts);
	}

}

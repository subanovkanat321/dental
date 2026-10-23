package kg.dental.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import kg.dental.DentalProperties;
import kg.dental.timeoff.TimeOffService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Свободное время дня (DD2, DD11, ER4): рабочие часы минус занятые приёмы и перерывы, без прошедшего.
 * Расчёт — в FreeTime, здесь только данные: расписание, приёмы из базы, «сейчас».
 */
@Service
public class SlotService {

	private final AppointmentRepository appointments;

	private final WorkSchedule schedule;

	private final DentalProperties properties;

	private final Clock clock;

	private final TimeOffService timeOff;

	SlotService(AppointmentRepository appointments, WorkSchedule schedule, DentalProperties properties, Clock clock,
			TimeOffService timeOff) {
		this.appointments = appointments;
		this.schedule = schedule;
		this.properties = properties;
		this.clock = clock;
		this.timeOff = timeOff;
	}

	/**
	 * @param duration длина приёма для сетки начал; null — только окна
	 * @param excludeId приём, который переносят: его старое время считается свободным
	 */
	@Transactional(readOnly = true)
	public SlotsView slots(LocalDate date, Duration duration, Long excludeId) {
		if (!schedule.isWorkingDay(date)) {
			return new SlotsView(false, schedule.start(), schedule.end(), List.of(), List.of(), List.of(), null);
		}
		var offs = timeOff.on(date);
		var wholeDay = offs.stream().filter(t -> t.wholeDay()).findFirst();
		if (wholeDay.isPresent()) {
			var off = new SlotsView.Break(null, null, wholeDay.get().title(), wholeDay.get().emoji());
			return new SlotsView(false, schedule.start(), schedule.end(), List.of(), List.of(), List.of(), off);
		}
		var zone = properties.timeZone();
		var from = date.atTime(schedule.start()).atZone(zone).toInstant();
		var to = date.atTime(schedule.end()).atZone(zone).toInstant();
		// Перерыв — как занятое время, но только в рабочих часах: «обед 12–14» при работе с 13 — это 13–14
		var breaks = new ArrayList<SlotsView.Break>();
		for (var t : offs) {
			var start = max(date.atTime(t.startTime()).atZone(zone).toInstant(), from);
			var end = min(date.atTime(t.endTime()).atZone(zone).toInstant(), to);
			if (start.isBefore(end)) {
				breaks.add(new SlotsView.Break(start, end, t.title(), t.emoji()));
			}
		}
		var busy = new ArrayList<>(appointments.findBusy(from, to, excludeId == null ? -1 : excludeId));
		breaks.forEach(b -> busy.add(new TimeRange(b.start(), b.end())));
		var free = FreeTime.free(from, to, busy, clock.instant());
		var starts = duration == null ? List.<Instant>of() : FreeTime.starts(free, duration);
		return new SlotsView(true, schedule.start(), schedule.end(), free, starts, breaks, null);
	}

	private static Instant max(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

}

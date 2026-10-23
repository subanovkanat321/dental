package kg.dental.timeoff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Перерыв или выходной в ответах API.
 *
 * @param weekday каждую неделю в этот день; null — разово, с firstDay по lastDay
 * @param startTime null вместе с endTime — весь день
 */
public record TimeOffView(long id, DayOfWeek weekday, LocalDate firstDay, LocalDate lastDay, LocalTime startTime,
		LocalTime endTime, String title, String emoji) {

	static TimeOffView of(TimeOff t) {
		return new TimeOffView(t.getId(), t.getWeekday(), t.getFirstDay(), t.getLastDay(), t.getStartTime(),
				t.getEndTime(), t.getTitle(), t.getEmoji());
	}

	public boolean wholeDay() {
		return startTime == null;
	}

}

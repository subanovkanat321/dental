package kg.dental.appointment;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Рабочие часы и дни врача (ER5). Разбирается при старте приложения: опечатка в WORK_HOURS
 * остановит запуск с понятной ошибкой, а не всплывёт пустой сеткой у врача.
 */
public record WorkSchedule(LocalTime start, LocalTime end, Set<DayOfWeek> days) {

	public WorkSchedule {
		if (!start.isBefore(end)) {
			throw new IllegalArgumentException("Рабочие часы: начало должно быть раньше конца");
		}
		days = Set.copyOf(days);
	}

	/** «09:00-18:00» и «MON-SAT» / «MON,WED,FRI». */
	public static WorkSchedule parse(String hours, String days) {
		var range = hours.strip().split("-");
		if (range.length != 2) {
			throw new IllegalArgumentException("WORK_HOURS: ожидается «09:00-18:00», а не «" + hours + "»");
		}
		try {
			return new WorkSchedule(LocalTime.parse(range[0].strip()), LocalTime.parse(range[1].strip()),
					parseDays(days));
		}
		catch (DateTimeParseException e) {
			throw new IllegalArgumentException("WORK_HOURS: ожидается «09:00-18:00», а не «" + hours + "»", e);
		}
	}

	public boolean isWorkingDay(LocalDate date) {
		return days.contains(date.getDayOfWeek());
	}

	private static Set<DayOfWeek> parseDays(String text) {
		var result = EnumSet.noneOf(DayOfWeek.class);
		for (var part : text.strip().toUpperCase(Locale.ROOT).split(",")) {
			var range = part.strip().split("-");
			var from = day(range[0]);
			var to = range.length == 2 ? day(range[1]) : from;
			if (range.length > 2 || to.compareTo(from) < 0) {
				throw new IllegalArgumentException("WORK_DAYS: ожидается «MON-SAT» или «MON,WED,FRI», а не «" + text + "»");
			}
			result.addAll(EnumSet.range(from, to));
		}
		return result;
	}

	private static DayOfWeek day(String code) {
		for (var day : DayOfWeek.values()) {
			if (day.name().startsWith(code.strip()) && code.strip().length() == 3) {
				return day;
			}
		}
		throw new IllegalArgumentException("WORK_DAYS: неизвестный день «" + code.strip() + "» (MON, TUE, … SUN)");
	}

}

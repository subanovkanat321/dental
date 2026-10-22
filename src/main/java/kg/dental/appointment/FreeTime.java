package kg.dental.appointment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Свободное время дня — чистые функции без базы и часов, поэтому их легко проверить
 * юнит-тестами (FreeTimeTests). Один и тот же расчёт кормит и строки «свободно» в «Дне»
 * (DD2), и сетку времени в «Новой записи» (DD11) — они не могут разойтись (ER4).
 */
final class FreeTime {

	/** Шаг сетки и самое короткое окно, которое стоит показывать: короче приём не бывает. */
	static final Duration STEP = Duration.ofMinutes(15);

	private FreeTime() {
	}

	/**
	 * Свободные окна внутри [from, to) за вычетом занятого, не короче 15 минут.
	 *
	 * @param notBefore «сейчас»: прошедшее время сегодня свободным не считается
	 */
	static List<TimeRange> free(Instant from, Instant to, List<TimeRange> busy, Instant notBefore) {
		var cursor = max(from, ceilToStep(notBefore));
		var result = new ArrayList<TimeRange>();
		var sorted = busy.stream().sorted(Comparator.comparing(TimeRange::start)).toList();
		for (var b : sorted) {
			if (b.start().isAfter(cursor)) {
				add(result, cursor, min(b.start(), to));
			}
			cursor = max(cursor, b.end());
			if (!cursor.isBefore(to)) {
				return result;
			}
		}
		add(result, cursor, to);
		return result;
	}

	/** Начала приёма длиной duration с шагом 15 минут, которые целиком помещаются в свободное окно. */
	static List<Instant> starts(List<TimeRange> free, Duration duration) {
		var result = new ArrayList<Instant>();
		for (var window : free) {
			for (var s = ceilToStep(window.start()); !s.plus(duration).isAfter(window.end()); s = s.plus(STEP)) {
				result.add(s);
			}
		}
		return result;
	}

	private static void add(List<TimeRange> result, Instant start, Instant end) {
		if (Duration.between(start, end).compareTo(STEP) >= 0) {
			result.add(new TimeRange(start, end));
		}
	}

	/**
	 * Вверх до ближайших :00, :15, :30, :45. По UTC, а не по местному времени: у Бишкека
	 * сдвиг +6 ч, целые часы, поэтому четверти часа совпадают.
	 */
	static Instant ceilToStep(Instant t) {
		var step = STEP.toSeconds();
		var seconds = t.getEpochSecond() + (t.getNano() > 0 ? 1 : 0);
		return Instant.ofEpochSecond(Math.ceilDiv(seconds, step) * step);
	}

	private static Instant max(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

}

package kg.dental.appointment;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Свободное время дня для Mini App.
 *
 * @param workingDay рабочий ли день (WORK_DAYS и выходные из «Перерывов»); нет — окон и сетки нет
 * @param free свободные окна не короче 15 минут — строки «свободно» в «Дне»
 * @param starts начала приёма запрошенной длины с шагом 15 минут — сетка в «Новой записи»
 * @param breaks перерывы этого дня в рабочих часах (джума-намаз, обед) — строкой в «Дне» и подсказкой пациенту
 * @param dayOff выходной на весь день из «Перерывов» (отпуск); null — нет
 */
public record SlotsView(boolean workingDay, LocalTime workStart, LocalTime workEnd, List<TimeRange> free,
		List<Instant> starts, List<Break> breaks, Break dayOff) {

	/** Перерыв: время (у выходного на весь день — null) и подпись. */
	public record Break(Instant start, Instant end, String title, String emoji) {
	}

}

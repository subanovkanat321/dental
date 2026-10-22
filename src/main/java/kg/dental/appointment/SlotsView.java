package kg.dental.appointment;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Свободное время дня для Mini App.
 *
 * @param workingDay рабочий ли день (WORK_DAYS); нет — «Выходной», окон и сетки нет
 * @param free свободные окна не короче 15 минут — строки «свободно» в «Дне»
 * @param starts начала приёма запрошенной длины с шагом 15 минут — сетка в «Новой записи»
 */
public record SlotsView(boolean workingDay, LocalTime workStart, LocalTime workEnd, List<TimeRange> free,
		List<Instant> starts) {
}

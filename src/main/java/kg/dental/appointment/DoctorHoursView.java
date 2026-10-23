package kg.dental.appointment;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Часы работы врача для «Часов работы» в Mini App.
 *
 * @param custom свои часы; false — как у клиники (days = часы клиники)
 * @param days рабочие дни недели с часами; дня нет в списке — выходной
 */
public record DoctorHoursView(boolean custom, List<Day> days) {

	public record Day(DayOfWeek weekday, LocalTime start, LocalTime end) {
	}

}

package kg.dental.appointment;

import java.time.LocalDate;

/**
 * Неделя (пн–вс по Бишкеку) в цифрах — критерий успеха проекта: стало ли меньше неявок.
 *
 * @param booked приёмов без отменённых
 * @param unmarked прошедших без отметки «пришёл» / «не пришёл» — без них неявки не посчитать
 * @param reminded из booked: напоминание открыто или пациент ответил
 */
public record WeekStats(LocalDate weekStart, long booked, long done, long noShow, long cancelled, long unmarked,
		long reminded) {

	static WeekStats empty(LocalDate weekStart) {
		return new WeekStats(weekStart, 0, 0, 0, 0, 0, 0);
	}

}

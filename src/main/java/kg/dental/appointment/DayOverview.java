package kg.dental.appointment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * День в цифрах для уведомлений врачу — без имён и телефонов: Telegram сторонний сервис.
 *
 * @param active приёмы без отменённых
 * @param firstStart начало первого, null — приёмов нет
 * @param lastEnd конец последнего
 * @param confirmed пациент ответил «приду»
 * @param reschedule пациент просит перенос
 * @param ready напомнили или пациент уже ответил (ER3) — как «Готово X из N» в Mini App
 * @param unmarked прошедшие без отметки «пришёл» / «не пришёл»
 */
public record DayOverview(LocalDate date, long active, Instant firstStart, Instant lastEnd, long confirmed,
		long reschedule, long ready, long unmarked) {
}

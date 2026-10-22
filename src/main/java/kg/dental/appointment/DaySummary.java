package kg.dental.appointment;

import java.time.Instant;

/**
 * Сводка для бота — только числа и время, без имён и телефонов (design:134): Telegram —
 * сторонний сервис, и персональным данным пациентов в нём не место.
 *
 * @param scheduled запланированных приёмов на день
 * @param firstStart начало первого из них, null — приёмов нет
 * @param unmarkedWeek прошедших за 7 дней приёмов без отметки «пришёл» / «не пришёл»
 */
public record DaySummary(long scheduled, Instant firstStart, long unmarkedWeek) {
}

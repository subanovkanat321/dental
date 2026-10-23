package kg.dental.appointment;

import java.time.LocalDate;

/**
 * Сколько приёмов в день — для ленты дней в шапке «Дня».
 *
 * @param booked все, кроме отменённых (заявки тоже: время занято)
 * @param requested заявки пациентов, ждущие подтверждения, — их видно отдельно
 */
public record DayCount(LocalDate date, long booked, long requested) {
}

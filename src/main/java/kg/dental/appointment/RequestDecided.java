package kg.dental.appointment;

/**
 * Врач решил заявку пациента: подтвердил (SCHEDULED) или отклонил (CANCELLED). Событие, а не
 * прямой вызов бота: модуль приёмов ничего не знает о Telegram, ответ пациенту шлёт модуль
 * записи (booking) — после коммита, когда решение уже точно сохранено.
 */
public record RequestDecided(long appointmentId, boolean confirmed) {
}

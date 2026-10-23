package kg.dental.appointment;

/**
 * Жизненный цикл приёма. Отменённый и неявка время не занимают (V1, D15).
 * REQUESTED — заявка пациента из бота (V5): время уже занято, ждёт решения врача.
 */
public enum AppointmentStatus {
	REQUESTED, SCHEDULED, CANCELLED, DONE, NO_SHOW
}

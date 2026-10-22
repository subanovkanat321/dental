package kg.dental.appointment;

/** Жизненный цикл приёма. Отменённый и неявка время не занимают (V1, D15). */
public enum AppointmentStatus {
	SCHEDULED, CANCELLED, DONE, NO_SHOW
}

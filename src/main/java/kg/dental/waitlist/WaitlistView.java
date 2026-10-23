package kg.dental.waitlist;

import java.time.Instant;

import kg.dental.appointment.AppointmentView;
import kg.dental.patient.PatientView;

/**
 * Строка листа ожидания для Mini App.
 *
 * @param doctorId к какому врачу хочет; null — к любому
 * @param upcoming ближайший приём пациента, если он уже записан, — тогда в окно его
 * переносят, а не записывают второй раз; null — пока не записан
 */
public record WaitlistView(long id, Long doctorId, PatientView patient, int durationMinutes, Instant createdAt,
		AppointmentView upcoming) {
}

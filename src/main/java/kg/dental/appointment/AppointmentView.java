package kg.dental.appointment;

import java.time.Instant;

import kg.dental.patient.PatientView;

/** Приём в ответах API. {@code version} Mini App присылает обратно в каждом изменении. */
public record AppointmentView(long id, PatientView patient, Instant startAt, Instant endAt,
		AppointmentStatus status, PatientReply reply, Instant reminderOpenedAt, long version) {

	static AppointmentView of(Appointment a) {
		return new AppointmentView(a.getId(), PatientView.of(a.getPatient()), a.getStartAt(), a.getEndAt(),
				a.getStatus(), a.getReply(), a.getReminderOpenedAt(), a.getVersion());
	}

}

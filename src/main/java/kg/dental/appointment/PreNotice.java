package kg.dental.appointment;

import java.time.Instant;

/** Приём, о котором пора предупредить врача: без пациента — только время и ответ. */
public record PreNotice(long id, long doctorId, Instant startAt, Instant endAt, PatientReply reply,
		Instant reminderOpenedAt) {
}

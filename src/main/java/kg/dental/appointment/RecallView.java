package kg.dental.appointment;

import java.time.Instant;

import kg.dental.patient.PatientView;

/**
 * Пациент, которого пора позвать на профосмотр.
 *
 * @param lastVisit начало последнего приёма со статусом «Пришёл»
 * @param invitedAt когда последний раз позвали; null — ещё не звали
 */
public record RecallView(PatientView patient, Instant lastVisit, Instant invitedAt) {
}

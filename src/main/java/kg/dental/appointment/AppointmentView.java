package kg.dental.appointment;

import java.time.Instant;

import kg.dental.patient.PatientView;

/**
 * Приём в ответах API. {@code version} Mini App присылает обратно в каждом изменении.
 *
 * @param source кто записал: DOCTOR или PATIENT (самозапись через бота)
 * @param serviceName услуга самозаписи, null — записал врач
 * @param rating оценка пациента 1–5 после приёма, null — оценки нет
 * @param paidSom оплата в целых сомах, null — не отмечена
 */
public record AppointmentView(long id, long doctorId, PatientView patient, Instant startAt, Instant endAt,
		AppointmentStatus status, PatientReply reply, Instant reminderOpenedAt, long version,
		Appointment.Source source, String serviceName, Short rating, Integer paidSom, PaymentMethod paymentMethod) {

	static AppointmentView of(Appointment a) {
		return new AppointmentView(a.getId(), a.getDoctorId(), PatientView.of(a.getPatient()), a.getStartAt(), a.getEndAt(),
				a.getStatus(), a.getReply(), a.getReminderOpenedAt(), a.getVersion(), a.getSource(),
				a.getServiceType() == null ? null : a.getServiceType().getName(), a.getRating(), a.getPaidSom(),
				a.getPaymentMethod());
	}

}

package kg.dental.booking;

import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentView;
import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Кнопка «Напомнить в Telegram»: свой приём — врач, любой — админ (Staff.requireCanEdit). */
@RestController
class PatientReminderController {

	private final PatientReminders reminders;

	private final AppointmentService appointments;

	private final Staff staff;

	PatientReminderController(PatientReminders reminders, AppointmentService appointments, Staff staff) {
		this.reminders = reminders;
		this.appointments = appointments;
		this.staff = staff;
	}

	@PostMapping("/api/appointments/{id}/remind-telegram")
	AppointmentView remind(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		staff.requireCanEdit(me, appointments.get(id).doctorId());
		return reminders.remind(id);
	}

}

package kg.dental.booking;

import kg.dental.appointment.AppointmentView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Кнопка врача «Напомнить в Telegram»: /api/appointments — только для ALLOWED_TELEGRAM_IDS. */
@RestController
class PatientReminderController {

	private final PatientReminders reminders;

	PatientReminderController(PatientReminders reminders) {
		this.reminders = reminders;
	}

	@PostMapping("/api/appointments/{id}/remind-telegram")
	AppointmentView remind(@PathVariable long id) {
		return reminders.remind(id);
	}

}

package kg.dental.appointment;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Приёмы. Каждое действие карточки — своя команда ({@code /reschedule}, {@code /status},
 * {@code /reply}, {@code /undo}), а не общий PATCH: у каждой свои правила и своё тело,
 * и сервер не гадает, что значит запрос, где изменилось сразу три поля.
 */
@RestController
@RequestMapping("/api/appointments")
class AppointmentController {

	private final AppointmentService service;

	AppointmentController(AppointmentService service) {
		this.service = service;
	}

	/** {@code ?date=2027-06-01} — день по Бишкеку. */
	@GetMapping
	List<AppointmentView> day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.day(date);
	}

	@GetMapping("/{id}")
	AppointmentView get(@PathVariable long id) {
		return service.get(id);
	}

	/** Карточка пациента живёт здесь, а не в модуле пациентов: пациенты о приёмах не знают. */
	@GetMapping("/by-patient/{patientId}")
	PatientCard card(@PathVariable long patientId) {
		return service.card(patientId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AppointmentView create(@Valid @RequestBody AppointmentRequests.Create request) {
		return service.create(request);
	}

	@PostMapping("/{id}/reschedule")
	AppointmentView reschedule(@PathVariable long id, @Valid @RequestBody AppointmentRequests.Reschedule request) {
		return service.reschedule(id, request);
	}

	@PostMapping("/{id}/status")
	AppointmentView changeStatus(@PathVariable long id, @Valid @RequestBody AppointmentRequests.ChangeStatus request) {
		return service.changeStatus(id, request);
	}

	@PostMapping("/{id}/reply")
	AppointmentView changeReply(@PathVariable long id, @Valid @RequestBody AppointmentRequests.ChangeReply request) {
		return service.changeReply(id, request);
	}

	@PostMapping("/{id}/undo")
	AppointmentView undo(@PathVariable long id, @Valid @RequestBody AppointmentRequests.Undo request) {
		return service.undo(id, request);
	}

	/** Кнопка WhatsApp: шлётся с keepalive до перехода в WhatsApp, ответ не нужен. */
	@PostMapping("/{id}/reminder-opened")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void reminderOpened(@PathVariable long id) {
		service.markReminderOpened(id);
	}

}

package kg.dental.waitlist;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import kg.dental.appointment.AppointmentView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waitlist")
class WaitlistController {

	static final String DURATION_MESSAGE = "Длительность от 15 минут до 4 часов";

	record Add(@NotNull(message = "Выберите пациента") Long patientId, Long doctorId,
			@NotNull(message = DURATION_MESSAGE) @Min(value = 15, message = DURATION_MESSAGE)
			@Max(value = 240, message = DURATION_MESSAGE) Integer durationMinutes) {
	}

	record Book(@NotNull(message = "Выберите врача") Long doctorId, @NotNull(message = "Выберите время") Instant startAt) {
	}

	private final WaitlistService service;

	WaitlistController(WaitlistService service) {
		this.service = service;
	}

	@GetMapping
	List<WaitlistView> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	WaitlistView add(@Valid @RequestBody Add request) {
		return service.add(request.patientId(), request.doctorId(), request.durationMinutes());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void remove(@PathVariable long id) {
		service.remove(id);
	}

	/** Записать (или перенести, если уже записан) в освободившееся окно и убрать из листа. */
	@PostMapping("/{id}/book")
	AppointmentView book(@PathVariable long id, @Valid @RequestBody Book request) {
		return service.book(id, request.doctorId(), request.startAt());
	}

}

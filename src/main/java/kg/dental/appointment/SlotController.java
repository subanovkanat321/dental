package kg.dental.appointment;

import java.time.Duration;
import java.time.LocalDate;

import kg.dental.shared.InvalidFieldException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/slots?date=2099-06-01&duration=30&exclude=5}: окна дня и сетка начал. */
@RestController
class SlotController {

	private final SlotService service;

	SlotController(SlotService service) {
		this.service = service;
	}

	@GetMapping("/api/slots")
	SlotsView slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(required = false) Integer duration, @RequestParam(required = false) Long exclude) {
		if (duration != null && (duration < 15 || duration > 240)) {
			throw new InvalidFieldException("durationMinutes", AppointmentRequests.DURATION_MESSAGE);
		}
		return service.slots(date, duration == null ? null : Duration.ofMinutes(duration), exclude);
	}

}

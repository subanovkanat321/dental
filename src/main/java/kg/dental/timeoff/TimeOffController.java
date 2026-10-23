package kg.dental.timeoff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** «Меню → Перерывы и выходные» — только врач (/api/** закрыт списком ALLOWED_TELEGRAM_IDS). */
@RestController
@RequestMapping("/api/time-off")
class TimeOffController {

	record Request(Long doctorId, DayOfWeek weekday, LocalDate firstDay, LocalDate lastDay, LocalTime startTime, LocalTime endTime,
			String title, String emoji) {
	}

	private final TimeOffService service;

	TimeOffController(TimeOffService service) {
		this.service = service;
	}

	@GetMapping
	List<TimeOffView> all() {
		return service.all();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TimeOffView create(@RequestBody Request r) {
		return service.create(r.doctorId(), r.weekday(), r.firstDay(), r.lastDay(), r.startTime(), r.endTime(), r.title(), r.emoji());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable long id) {
		service.delete(id);
	}

}

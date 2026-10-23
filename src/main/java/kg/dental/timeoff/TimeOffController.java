package kg.dental.timeoff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import kg.dental.shared.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
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

	private final Staff staff;

	TimeOffController(TimeOffService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	/** Свой перерыв — врач; общий (для всех врачей) и чужой — только админ. */
	private void requireCanEdit(long me, Long doctorId) {
		if (doctorId == null) {
			if (!staff.isAdmin(me)) {
				throw new ForbiddenException("Перерыв для всех врачей задаёт администратор");
			}
			return;
		}
		staff.requireCanEdit(me, doctorId);
	}

	@GetMapping
	List<TimeOffView> all() {
		return service.all();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TimeOffView create(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody Request r) {
		requireCanEdit(me, r.doctorId());
		return service.create(r.doctorId(), r.weekday(), r.firstDay(), r.lastDay(), r.startTime(), r.endTime(), r.title(), r.emoji());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		requireCanEdit(me, service.doctorIdOf(id));
		service.delete(id);
	}

}

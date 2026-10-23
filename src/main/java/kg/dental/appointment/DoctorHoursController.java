package kg.dental.appointment;

import java.util.List;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Часы работы врача: смотреть — сотрудники, менять — сам врач или админ. */
@RestController
class DoctorHoursController {

	record Request(List<DoctorHoursView.Day> days) {
	}

	private final DoctorHoursService service;

	private final Staff staff;

	DoctorHoursController(DoctorHoursService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	@GetMapping("/api/doctors/{id}/hours")
	DoctorHoursView view(@PathVariable long id) {
		return service.view(id);
	}

	@PutMapping("/api/doctors/{id}/hours")
	DoctorHoursView update(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@RequestBody Request request) {
		staff.requireCanEdit(me, id);
		return service.update(id, request.days() == null ? List.of() : request.days());
	}

}

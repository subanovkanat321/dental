package kg.dental.clinic;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import kg.dental.shared.ForbiddenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** «Меню → О клинике»: читать — сотрудники, менять — админ. Пациенты получают это в /api/patient/me. */
@RestController
class ClinicController {

	record Request(String address, String mapUrl, String supportTelegram, String supportPhone) {
	}

	private final ClinicService service;

	private final Staff staff;

	ClinicController(ClinicService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	@GetMapping("/api/clinic")
	ClinicView view() {
		return service.view();
	}

	@PutMapping("/api/clinic")
	ClinicView update(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody Request r) {
		if (!staff.isAdmin(me)) {
			throw new ForbiddenException("Адрес и поддержку меняет администратор");
		}
		return service.update(r.address(), r.mapUrl(), r.supportTelegram(), r.supportPhone());
	}

}

package kg.dental.supply;

import java.util.List;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import kg.dental.shared.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** «Меню → Склад»: смотреть, списывать и принимать поставку — все сотрудники; удалить материал — админ. */
@RestController
class SupplyController {

	record Create(String name, String unit, Integer quantity, Integer minQuantity) {
	}

	record Update(String name, String unit, Integer minQuantity) {
	}

	record Move(int delta, String reason) {
	}

	private final SupplyService service;

	private final Staff staff;

	SupplyController(SupplyService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	@GetMapping("/api/supplies")
	List<SupplyView> all() {
		return service.all();
	}

	@GetMapping("/api/supplies/{id}/history")
	List<MovementView> history(@PathVariable long id) {
		return service.history(id);
	}

	@PostMapping("/api/supplies")
	@ResponseStatus(HttpStatus.CREATED)
	SupplyView create(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody Create r) {
		return service.create(r.name(), r.unit(), r.quantity(), r.minQuantity(), staff.doctorIdOf(me).orElse(null));
	}

	record Added(int added) {
	}

	/** «Добавить типовой набор» — чтобы сразу показать склад и попробовать, не вводя всё руками. */
	@PostMapping("/api/supplies/samples")
	Added samples(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me) {
		return new Added(service.addSamples(staff.doctorIdOf(me).orElse(null)));
	}

	@PutMapping("/api/supplies/{id}")
	SupplyView update(@PathVariable long id, @RequestBody Update r) {
		return service.update(id, r.name(), r.unit(), r.minQuantity());
	}

	/** Кто списал — из подписанной initData (врач), не из тела запроса. */
	@PostMapping("/api/supplies/{id}/move")
	SupplyView move(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id, @RequestBody Move r) {
		return service.move(id, r.delta(), r.reason(), staff.doctorIdOf(me).orElse(null));
	}

	@DeleteMapping("/api/supplies/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		if (!staff.isAdmin(me)) {
			throw new ForbiddenException("Удалить материал может администратор");
		}
		service.delete(id);
	}

}

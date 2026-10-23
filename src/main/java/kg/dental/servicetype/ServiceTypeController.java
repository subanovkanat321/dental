package kg.dental.servicetype;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Услуги — для врача («Меню → Услуги»). Пациенты видят активные через /api/patient/me. */
@RestController
@RequestMapping("/api/service-types")
class ServiceTypeController {

	static final String DURATION_MESSAGE = "Длительность от 15 минут до 4 часов";

	record Request(@NotBlank(message = "Укажите название") @Size(max = 100, message = "Не длиннее 100 символов") String name,
			@NotNull(message = DURATION_MESSAGE) @Min(value = 15, message = DURATION_MESSAGE)
			@Max(value = 240, message = DURATION_MESSAGE) Integer durationMinutes,
			Boolean active) {
	}

	private final ServiceTypeService service;

	ServiceTypeController(ServiceTypeService service) {
		this.service = service;
	}

	@GetMapping
	List<ServiceTypeView> all() {
		return service.all();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ServiceTypeView create(@Valid @RequestBody Request request) {
		return service.create(request.name(), request.durationMinutes());
	}

	@PutMapping("/{id}")
	ServiceTypeView update(@PathVariable long id, @Valid @RequestBody Request request) {
		return service.update(id, request.name(), request.durationMinutes(), request.active() == null || request.active());
	}

}

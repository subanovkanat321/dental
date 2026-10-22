package kg.dental.patient;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Пациенты. Поиск — POST с телом, а не GET {@code ?q=}: в строке поиска бывает номер
 * телефона, а персональные данные не должны попадать в URL (их пишут access-логи nginx,
 * история браузера, заголовок Referer).
 */
@RestController
@RequestMapping("/api/patients")
class PatientController {

	private final PatientService service;

	PatientController(PatientService service) {
		this.service = service;
	}

	@PostMapping("/search")
	List<PatientView> search(@RequestBody SearchPatientsRequest request) {
		return service.search(request.query());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	PatientView create(@Valid @RequestBody PatientRequest request) {
		return service.create(request);
	}

	/** Кнопка WhatsApp «пора на осмотр»: шлётся с keepalive до перехода в WhatsApp. */
	@PostMapping("/{id}/recall-invited")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void recallInvited(@PathVariable long id) {
		service.markRecallInvited(id);
	}

	@PutMapping("/{id}")
	PatientView update(@PathVariable long id, @Valid @RequestBody PatientRequest request) {
		return service.update(id, request);
	}

}

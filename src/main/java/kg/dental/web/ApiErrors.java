package kg.dental.web;

import java.util.List;
import java.util.Map;

import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Все ошибки API — в одном формате Problem Details (RFC 9457), как в store.
 * Базовый класс Spring уже умеет это для своих исключений (404 на неизвестный путь,
 * 400 на кривой JSON); здесь — ошибки полей и исключения сервисов.
 * Сервисы бросают свои исключения (пакет shared) и ничего не знают про HTTP.
 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

	/** Ключ списка ошибок полей: {@code "errors": [{"field": "phone", "message": "..."}]}. */
	static final String ERRORS = "errors";

	/**
	 * Bean Validation ({@code @Valid}) нашла ошибки: 422 и список полей. Не 400: запрос
	 * разобран, просто данные не подходят.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var problem = invalid();
		problem.setProperty(ERRORS, ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList());
		return ResponseEntity.unprocessableContent().body(problem);
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> invalidField(InvalidFieldException ex) {
		var problem = invalid();
		problem.setProperty(ERRORS, List.of(Map.of("field", ex.field(), "message", ex.getMessage())));
		return ResponseEntity.unprocessableContent().body(problem);
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> conflict(ConflictException ex) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setProperty("code", ex.code());
		ex.properties().forEach(problem::setProperty);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> notFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage()));
	}

	private static ProblemDetail invalid() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Проверьте поля формы");
	}

}

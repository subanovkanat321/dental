package kg.dental.shared;

/**
 * Поле запроса не прошло проверку, которую не выразить аннотацией (например, формат
 * телефона) — ответ 422, как у {@code @Valid}: Mini App покажет текст под этим полем.
 * Само значение в сообщение не попадает: в поле может быть телефон.
 */
public class InvalidFieldException extends RuntimeException {

	private final String field;

	public InvalidFieldException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}

}

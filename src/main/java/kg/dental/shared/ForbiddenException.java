package kg.dental.shared;

/** Сотрудник вошёл, но на это действие прав нет (например, завести врача может только админ) — 403. */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}

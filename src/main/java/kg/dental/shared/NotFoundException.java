package kg.dental.shared;

/** Запрошенной записи нет — ответ 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}

package kg.dental.shared;

import java.util.Map;

/**
 * Действие не выполнено из-за текущего состояния данных — ответ 409. {@code code} нужен
 * Mini App, чтобы различать случаи: «Время занято» показывается под полем времени, а
 * «Приём изменён» — с предложением обновить экран.
 */
public class ConflictException extends RuntimeException {

	public static final String TIME_TAKEN = "time-taken";
	public static final String STALE = "stale";
	public static final String INVALID_TRANSITION = "invalid-transition";
	public static final String DUPLICATE_PATIENT = "duplicate-patient";

	private final String code;

	private final Map<String, Object> properties;

	public ConflictException(String code, String message) {
		this(code, message, Map.of());
	}

	public ConflictException(String code, String message, Map<String, Object> properties) {
		super(message);
		this.code = code;
		this.properties = properties;
	}

	public static ConflictException timeTaken() {
		return new ConflictException(TIME_TAKEN, "Время занято");
	}

	public static ConflictException stale() {
		return new ConflictException(STALE, "Приём изменён, обновите");
	}

	public String code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}

}

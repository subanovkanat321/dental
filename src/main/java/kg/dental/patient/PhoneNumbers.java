package kg.dental.patient;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Приводит телефон к виду, который хранится в базе и подходит для ссылки
 * {@code wa.me/<цифры>}: только цифры с кодом страны, без «+» (R5, D7).
 * <ul>
 * <li>кыргызский: {@code 0555 12 34 56}, {@code +996 555 123456}, {@code 996555123456}
 * → {@code 996555123456} (ровно 12 цифр);</li>
 * <li>любой другой с «+» и 8–15 цифрами (E.164): {@code +7 701 234 56 78} → {@code 77012345678}.</li>
 * </ul>
 * Остальное — пусто: сервис ответит 422. Существует ли номер, здесь не проверить.
 */
public final class PhoneNumbers {

	/** Разделители, которые люди ставят в номерах: пробелы, дефисы, скобки, точки. */
	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

	private static final Pattern DIGITS = Pattern.compile("[0-9]+");

	private static final String KG = "996";

	private static final int KG_LENGTH = 12;

	private PhoneNumbers() {
	}

	public static Optional<String> normalize(String input) {
		if (input == null) {
			return Optional.empty();
		}
		var compact = SEPARATORS.matcher(input.strip()).replaceAll("");
		var international = compact.startsWith("+");
		var digits = international ? compact.substring(1) : compact;
		if (!DIGITS.matcher(digits).matches()) {
			return Optional.empty();
		}
		if (digits.startsWith(KG)) {
			return digits.length() == KG_LENGTH ? Optional.of(digits) : Optional.empty();
		}
		if (international) {
			return digits.length() >= 8 && digits.length() <= 15 ? Optional.of(digits) : Optional.empty();
		}
		// Местный формат: 0 и 9 цифр номера
		if (digits.startsWith("0") && digits.length() == 10) {
			return Optional.of(KG + digits.substring(1));
		}
		return Optional.empty();
	}

}

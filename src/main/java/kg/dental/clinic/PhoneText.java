package kg.dental.clinic;

/** Номер для людей: 996555123456 → «+996 555 123 456» (как formatPhone в Mini App). */
public final class PhoneText {

	private PhoneText() {
	}

	public static String format(String digits) {
		if (digits.matches("996\\d{9}")) {
			return "+996 " + digits.substring(3, 6) + " " + digits.substring(6, 9) + " " + digits.substring(9);
		}
		return "+" + digits;
	}

}

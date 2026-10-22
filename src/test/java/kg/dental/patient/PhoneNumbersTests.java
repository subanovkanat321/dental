package kg.dental.patient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			0555 12 34 56      | 996555123456
			0555123456         | 996555123456
			+996 555 123456    | 996555123456
			996555123456       | 996555123456
			+996 (555) 12-34-56| 996555123456
			  0700 123 456     | 996700123456
			+7 701 234 56 78   | 77012345678
			+90 532 123 45 67  | 905321234567
			+49 30 1234567     | 49301234567
			""")
	void normalizesKyrgyzAndInternationalNumbers(String input, String expected) {
		assertThat(PhoneNumbers.normalize(input)).hasValue(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "abc", "555 12 34 56", "7", "+7", "+1234567", "+1234567890123456",
			"99655512345", "9965551234567", "+996 555 12345", "05551234567", "055512345", "8 701 234 56 78",
			"+7 701 234 56 78 доб. 12", "++77012345678" })
	void rejectsEverythingElse(String input) {
		assertThat(PhoneNumbers.normalize(input)).isEmpty();
	}

}

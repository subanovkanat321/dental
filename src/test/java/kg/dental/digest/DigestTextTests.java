package kg.dental.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import kg.dental.appointment.DaySummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DigestTextTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	static final LocalDate TUESDAY = LocalDate.of(2099, 6, 2);

	@Test
	void countAndFirstTimeInBishkek() {
		var text = DigestText.of(TUESDAY, new DaySummary(8, Instant.parse("2099-06-02T03:30:00Z"), 0), BISHKEK);

		assertThat(text).isEqualTo("Завтра, вт, 2 июн: 8 приёмов, первый в 09:30.");
	}

	@Test
	void emptyDayStillSaysSo() {
		assertThat(DigestText.of(TUESDAY, new DaySummary(0, null, 0), BISHKEK)).isEqualTo("Завтра приёмов нет.");
	}

	@Test
	void unmarkedLineOnlyWhenThereAreSome() {
		var text = DigestText.of(TUESDAY, new DaySummary(0, null, 2), BISHKEK);

		assertThat(text).isEqualTo("Завтра приёмов нет.\nНе отмечено за неделю: 2 — отметьте «Пришёл» или «Не пришёл».");
	}

	@ParameterizedTest
	@CsvSource({ "1, приём", "2, приёма", "4, приёма", "5, приёмов", "11, приёмов", "12, приёмов", "14, приёмов",
			"21, приём", "22, приёма", "25, приёмов", "111, приёмов" })
	void russianPlural(long n, String word) {
		assertThat(DigestText.appointments(n)).isEqualTo(word);
	}

}

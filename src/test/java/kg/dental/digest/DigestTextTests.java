package kg.dental.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import kg.dental.appointment.DayOverview;
import kg.dental.appointment.PatientReply;
import kg.dental.appointment.PreNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DigestTextTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	static final LocalDate MONDAY = LocalDate.of(2099, 6, 1);

	static final LocalDate TUESDAY = LocalDate.of(2099, 6, 2);

	static DayOverview day(LocalDate date, long active, String first, String last, long confirmed, long reschedule,
			long ready, long unmarked) {
		return new DayOverview(date, active, first == null ? null : Instant.parse(first),
				last == null ? null : Instant.parse(last), confirmed, reschedule, ready, unmarked);
	}

	static DayOverview empty(LocalDate date) {
		return day(date, 0, null, null, 0, 0, 0, 0);
	}

	@Test
	void morningTodayThenTomorrow() {
		var today = day(MONDAY, 5, "2099-06-01T04:00:00Z", "2099-06-01T11:30:00Z", 3, 1, 4, 0);
		var tomorrow = day(TUESDAY, 8, "2099-06-02T03:30:00Z", "2099-06-02T12:00:00Z", 0, 0, 0, 0);

		assertThat(DigestText.morning(today, tomorrow, 1, BISHKEK)).isEqualTo("""
				Сегодня, пн, 1 июн: 5 приёмов, 10:00–17:30.
				Подтвердили: 3 · просят перенос: 1 · без ответа: 1.
				Завтра, вт, 2 июн: 8 приёмов, первый в 09:30.
				Не отмечено за неделю: 1 — отметьте «Приём состоялся» или «Неявка».""");
	}

	@Test
	void morningWithEmptyDaysStillSaysSo() {
		assertThat(DigestText.morning(empty(MONDAY), empty(TUESDAY), 0, BISHKEK))
			.isEqualTo("Сегодня приёмов нет.\nЗавтра приёмов нет.");
	}

	@Test
	void morningNobodyAnsweredYet() {
		var today = day(MONDAY, 1, "2099-06-01T04:00:00Z", "2099-06-01T04:30:00Z", 0, 0, 0, 0);

		assertThat(DigestText.morning(today, empty(TUESDAY), 0, BISHKEK))
			.startsWith("Сегодня, пн, 1 июн: 1 приём, 10:00–10:30.\nБез ответа: 1.");
	}

	@Test
	void eveningOnlyWhenSomethingIsLeft() {
		var tomorrow = day(TUESDAY, 8, "2099-06-02T03:30:00Z", "2099-06-02T12:00:00Z", 2, 0, 3, 0);
		var today = day(MONDAY, 5, "2099-06-01T04:00:00Z", "2099-06-01T11:30:00Z", 0, 0, 5, 2);

		assertThat(DigestText.evening(today, tomorrow)).isEqualTo("""
				Завтра не напомнили: 5 из 8 — откройте обход.
				Сегодня не отмечено «явка / неявка»: 2.""");
		var allDone = day(TUESDAY, 8, "2099-06-02T03:30:00Z", "2099-06-02T12:00:00Z", 8, 0, 8, 0);
		assertThat(DigestText.evening(empty(MONDAY), allDone)).isNull();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			CONFIRMED  | false | Ответ пациента: придёт.
			RESCHEDULE | true  | Ответ пациента: просит перенос.
			NO_ANSWER  | true  | На напоминание ответа нет.
			NONE       | true  | Ответа пока нет.
			NONE       | false | Напоминание не отправляли.
			""")
	void preNoticeSaysWhenAndPatientsAnswer(PatientReply reply, boolean reminded, String answer) {
		var notice = new PreNotice(7, 1, Instant.parse("2099-06-01T04:00:00Z"), Instant.parse("2099-06-01T04:30:00Z"),
				reply, reminded ? Instant.parse("2099-05-31T12:00:00Z") : null);

		assertThat(DigestText.preNotice(notice, Instant.parse("2099-06-01T03:45:10Z"), BISHKEK))
			.isEqualTo("Через 15 мин, в 10:00 — приём (30 мин). " + answer);
	}

	@ParameterizedTest
	@CsvSource({ "1, приём", "2, приёма", "5, приёмов", "11, приёмов", "21, приём", "22, приёма", "111, приёмов" })
	void russianPlural(long n, String word) {
		assertThat(DigestText.appointments(n)).isEqualTo(word);
	}

}

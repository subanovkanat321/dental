package kg.dental.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Отзыв после приёма: вопрос на следующий день, оценка кнопкой, сводка в статистике. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class ReviewsTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	/** Вторник 2099-06-02, 12:00 по Бишкеку; вчера — понедельник 1 июня. */
	static final Instant NOON = LocalDate.of(2099, 6, 2).atTime(12, 0).atZone(BISHKEK).toInstant();

	@Autowired
	Reviews reviews;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long botPatient;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		botPatient = patient("Бакыт", TestInitData.STRANGER_ID);
		clearInvocations(telegram);
	}

	@Test
	void asksYesterdaysBotPatientsOnceWithFiveStarsInOneRow() {
		var id = appointment(botPatient, 1, "DONE");
		appointment(patient("Айгуль", null), 1, "DONE");
		appointment(patient("Нурлан", 555L), 1, "NO_SHOW");
		appointment(patient("Мээрим", 556L), 0, "DONE"); // вчера — нет, сегодня

		assertThat(reviews.ask(NOON)).isEqualTo(1);
		assertThat(reviews.ask(NOON)).isZero();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<List<TelegramClient.Button>>> rows = ArgumentCaptor.forClass(List.class);
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram, times(1)).sendKeyboard(eq(TestInitData.STRANGER_ID), text.capture(), rows.capture());
		assertThat(text.getValue()).startsWith("Здравствуйте, Бакыт! Как прошёл вчерашний приём");
		assertThat(rows.getValue()).hasSize(1);
		assertThat(rows.getValue().get(0)).extracting(TelegramClient.Button::callbackData)
			.containsExactly("rate:" + id + ":1", "rate:" + id + ":2", "rate:" + id + ":3", "rate:" + id + ":4",
					"rate:" + id + ":5");
	}

	@Test
	void ratingIsSavedOnceDoctorHearsWithoutNameAndSeesInCardAndStats() {
		var id = appointment(botPatient, 1, "DONE");
		reviews.ask(NOON);

		press(TestInitData.STRANGER_ID, "rate:" + id + ":5");

		assertThat(jdbc.queryForObject("select rating from appointment where id = ?", Integer.class, id)).isEqualTo(5);
		verify(telegram).answerCallbackQuery("cb-1", "Спасибо за оценку!");
		var doctor = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), doctor.capture(), anyList());
		assertThat(doctor.getValue()).startsWith("⭐⭐⭐⭐⭐ Оценка приёма ").endsWith("— 5 из 5.").doesNotContain("Бакыт");

		press(TestInitData.STRANGER_ID, "rate:" + id + ":1");
		assertThat(jdbc.queryForObject("select rating from appointment where id = ?", Integer.class, id)).isEqualTo(5);
		verify(telegram).answerCallbackQuery("cb-1", "Оценка уже сохранена");

		assertThat(mvc.get().uri("/api/appointments/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.bodyJson().extractingPath("$.rating").isEqualTo(5);
	}

	@Test
	void someoneElseOrNotAskedCannotRate() {
		var asked = appointment(botPatient, 1, "DONE");
		reviews.ask(NOON);
		var notAsked = appointment(botPatient, 3, "DONE");

		press(TestInitData.DOCTOR_ID, "rate:" + asked + ":1");
		press(TestInitData.STRANGER_ID, "rate:" + notAsked + ":1");

		assertThat(jdbc.queryForObject("select count(rating) from appointment", Integer.class)).isZero();
		verify(telegram, never()).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(), anyList());
	}

	@Test
	void summaryAverageAndStars() {
		var hour = 0;
		for (var rating : new int[] { 5, 5, 4, 2 }) {
			var id = appointment(botPatient, 1, "DONE");
			// Оценки за последние 90 дней: двигаем приёмы во вчера, по часу друг от друга
			jdbc.update("update appointment set rating = ?, review_asked_at = now(), "
					+ "start_at = now() - interval '1 day' - ? * interval '1 hour', "
					+ "end_at = now() - interval '1 day' - ? * interval '1 hour' + interval '30 minutes' where id = ?",
					rating, hour, hour, id);
			hour++;
		}

		var json = assertThat(mvc.get().uri("/api/appointments/ratings?days=90")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk().bodyJson();
		json.extractingPath("$.count").isEqualTo(4);
		json.extractingPath("$.average").isEqualTo(4.0);
		json.extractingPath("$.byStars").asArray().containsExactly(0, 1, 0, 1, 2);
	}

	private void press(long fromId, String data) {
		mvc.post().uri("/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"update_id": 4, "callback_query": {"id": "cb-1", "data": "%s",
					 "from": {"id": %d, "is_bot": false, "first_name": "Test"},
					 "message": {"message_id": 78, "date": 1767225600, "chat": {"id": %d, "type": "private"}}}}"""
				.formatted(data, fromId, fromId))
			.exchange();
	}

	private long patient(String name, Long telegramId) {
		return jdbc.queryForObject("insert into patient (full_name, phone, telegram_id) values (?, ?, ?) returning id",
				Long.class, name, "99670" + String.format("%07d", Math.abs(name.hashCode()) % 10_000_000), telegramId);
	}

	/** Приём в 10:00 по Бишкеку, daysAgo дней назад от NOON. */
	private long appointment(long patientId, int daysAgo, String status) {
		var start = LocalDate.of(2099, 6, 2).minusDays(daysAgo).atTime(10, 0).atZone(BISHKEK).toInstant()
			.plusSeconds(60L * 30 * jdbc.queryForObject("select count(*) from appointment", Integer.class));
		return jdbc.queryForObject("""
				insert into appointment (patient_id, start_at, end_at, status) values (?, ?, ?, ?) returning id""",
				Long.class, patientId, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)), status);
	}

}

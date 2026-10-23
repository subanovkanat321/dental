package kg.dental.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.patient.Lang;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Кыргызский для пациента: язык из Telegram, выбор в Mini App, тексты бота на этом языке. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class KyrgyzTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact, chat_language restart identity cascade");
	}

	@Test
	void startFromKyrgyzTelegramGreetsInKyrgyzAndRemembers() {
		webhook(start("ky"));

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), text.capture(), buttons.capture());
		assertThat(text.getValue()).startsWith("Саламатсызбы! Бул — тиш дарыгерине жазылуу боту.")
			.endsWith("Клиниканын телефону: +996 555 000 000");
		assertThat(buttons.getValue().get(0).text()).isEqualTo("Кабыл алууга жазылуу");
		assertThat(lang()).isEqualTo("ky");
	}

	@Test
	void choiceInMiniAppWinsOverTelegramLanguage() {
		assertThat(mvc.put().uri("/api/patient/language").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lang\": \"ru\"}")).hasStatus(HttpStatus.NO_CONTENT);

		webhook(start("ky"));

		assertThat(lang()).isEqualTo("ru");
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID),
				org.mockito.ArgumentMatchers.startsWith("Здравствуйте!"), anyList());
		assertThat(mvc.get().uri("/api/patient/me").header(HttpHeaders.AUTHORIZATION, stranger())).bodyJson()
			.extractingPath("$.lang").isEqualTo("ru");
	}

	@Test
	void unknownLanguageIs422() {
		assertThat(mvc.put().uri("/api/patient/language").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON).content("{\"lang\": \"en\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void reminderAndAnswerInKyrgyz() {
		jdbc.update("insert into chat_language (telegram_id, lang) values (?, 'ky')", TestInitData.STRANGER_ID);
		var id = bookAndConfirm();
		clearInvocations(telegram); // подтверждение записи — отдельное сообщение, его проверяет тест ниже

		assertThat(mvc.post().uri("/api/appointments/" + id + "/remind-telegram")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk();

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), text.capture(), buttons.capture());
		assertThat(text.getValue()).startsWith("Саламатсызбы, Бакыт! Эскертебиз: ").contains("саат 10:00 тиш дарыгеринин кабыл алуусу");
		assertThat(buttons.getValue()).extracting(TelegramClient.Button::text).startsWith("Келем", "Жылдыруу керек");

		clearInvocations(telegram);
		webhook("""
				{"update_id": 4, "callback_query": {"id": "cb-1", "data": "reply:%d:C",
				 "from": {"id": %d, "is_bot": false, "first_name": "Test"},
				 "message": {"message_id": 77, "date": 1767225600, "chat": {"id": %d, "type": "private"}}}}"""
			.formatted(id, TestInitData.STRANGER_ID, TestInitData.STRANGER_ID));
		verify(telegram).answerCallbackQuery("cb-1", "Рахмат!");
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), org.mockito.ArgumentMatchers.startsWith("Рахмат! Сизди "));
	}

	@Test
	void confirmationInKyrgyzWithKyrgyzDate() {
		jdbc.update("insert into chat_language (telegram_id, lang) values (?, 'ky')", TestInitData.STRANGER_ID);
		bookAndConfirm();

		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), text.capture(), anyList());
		assertThat(text.getValue()).startsWith("Тиш дарыгерине жазылууңуз ырасталды: ").contains(", саат 10:00 — Осмотр и консультация.");
	}

	@Test
	void kyrgyzDates() {
		var at = LocalDate.of(2026, 10, 26).atTime(10, 0).atZone(BISHKEK);

		assertThat(Lang.KY.dateTime(at)).isEqualTo("26-октябрь, дүйшөмбү, саат 10:00");
		assertThat(Lang.RU.dateTime(at)).isEqualTo("понедельник, 26 октября, в 10:00");
		assertThat(Lang.KY.when(at, at.toLocalDate().minusDays(1))).isEqualTo("эртең саат 10:00");
		assertThat(Lang.of("ky-KG")).isEqualTo(Lang.KY);
		assertThat(Lang.of("en")).isEqualTo(Lang.RU);
	}

	private long bookAndConfirm() {
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		var start = LocalDate.now(BISHKEK).plusDays(3).atTime(LocalTime.of(10, 0)).atZone(BISHKEK).toInstant();
		var body = mvc.post().uri("/api/patient/booking").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт", "consent": true}"""
				.formatted(serviceId, start))
			.exchange().getResponse();
		long id;
		try {
			id = ((Number) JsonPath.read(body.getContentAsString(), "$.id")).longValue();
		}
		catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
		mvc.post().uri("/api/appointments/" + id + "/status").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0, \"status\": \"SCHEDULED\"}").exchange();
		return id;
	}

	private String lang() {
		return jdbc.queryForList("select lang from chat_language where telegram_id = ?", String.class, TestInitData.STRANGER_ID)
			.stream().findFirst().orElse(null);
	}

	private MvcTestResult webhook(String body) {
		return mvc.post().uri("/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String start(String languageCode) {
		return """
				{"update_id": 1, "message": {"message_id": 1, "date": 1767225600, "text": "/start",
				 "from": {"id": %d, "is_bot": false, "first_name": "Test", "language_code": "%s"},
				 "chat": {"id": %d, "type": "private"}}}""".formatted(TestInitData.STRANGER_ID, languageCode, TestInitData.STRANGER_ID);
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

}

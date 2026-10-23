package kg.dental.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.shared.InvalidFieldException;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.AfterEach;
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

/** Адрес со ссылкой 2ГИС и техподдержка: «О клинике», /help, адрес в сообщениях пациенту. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class ClinicApiTests {

	static final String MAP = "https://2gis.kg/bishkek/firm/70000001012345678";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		clearInvocations(telegram);
	}

	@AfterEach
	void reset() {
		jdbc.update("update clinic_settings set address = null, map_url = null, support_telegram = null, support_phone = null");
	}

	@Test
	void adminSavesAddressMapAndSupportPatientSeesThem() {
		assertThat(update(TestInitData.DOCTOR_ID, """
				{"address": " Бишкек, ул. Киевская 95, 2 этаж ", "mapUrl": "%s",
				 "supportTelegram": "https://t.me/kanat_dev", "supportPhone": "0555 123 456"}""".formatted(MAP)))
			.hasStatusOk();

		var me = assertThat(mvc.get().uri("/api/patient/me").header(HttpHeaders.AUTHORIZATION, stranger())).bodyJson();
		me.extractingPath("$.clinic.address").isEqualTo("Бишкек, ул. Киевская 95, 2 этаж");
		me.extractingPath("$.clinic.mapUrl").isEqualTo(MAP);
		me.extractingPath("$.clinic.supportTelegram").isEqualTo("kanat_dev");
		me.extractingPath("$.clinic.supportUrl").isEqualTo("https://t.me/kanat_dev");
		me.extractingPath("$.clinic.supportPhone").isEqualTo("996555123456");
	}

	@Test
	void onlyTwoGisLinksAndValidSupport() {
		assertThat(update(TestInitData.DOCTOR_ID, "{\"mapUrl\": \"https://evil.example/2gis.kg\"}"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson().extractingPath("$.errors[0].field").isEqualTo("mapUrl");
		assertThat(update(TestInitData.DOCTOR_ID, "{\"mapUrl\": \"http://2gis.kg/bishkek\"}"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(update(TestInitData.DOCTOR_ID, "{\"supportTelegram\": \"не ник\"}")).bodyJson()
			.extractingPath("$.errors[0].field").isEqualTo("supportTelegram");
		assertThat(ClinicService.mapUrl("https://go.2gis.com/abc12")).isEqualTo("https://go.2gis.com/abc12");
		assertThat(ClinicService.telegram("@kanat_dev")).isEqualTo("kanat_dev");
		assertThatThrownBy(() -> ClinicService.mapUrl("https://2gis.kg.evil.com/x")).isInstanceOf(InvalidFieldException.class);
	}

	@Test
	void doctorWhoIsNotAdminCannotChange() {
		jdbc.update("update doctor set telegram_id = 222 where id = 1");
		try {
			assertThat(update(222, "{\"address\": \"x\"}")).hasStatus(HttpStatus.FORBIDDEN);
		}
		finally {
			jdbc.update("update doctor set telegram_id = null where id = 1");
		}
	}

	@Test
	void helpCommandTellsClinicAndSupportWithButtons() {
		update(TestInitData.DOCTOR_ID, """
				{"address": "ул. Киевская 95", "mapUrl": "%s", "supportTelegram": "kanat_dev", "supportPhone": "0555123456"}"""
			.formatted(MAP));

		webhook("""
				{"update_id": 9, "message": {"message_id": 5, "date": 1767225600, "text": "/help",
				 "from": {"id": 999, "is_bot": false, "first_name": "A"}, "chat": {"id": 999, "type": "private"}}}""");

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(999L), text.capture(), buttons.capture());
		assertThat(text.getValue()).contains("Адрес: ул. Киевская 95", "техподдержка: @kanat_dev, +996 555 123 456",
				"телефон клиники: +996 555 000 000");
		assertThat(buttons.getValue()).containsExactly(
				TelegramClient.Button.link("🛟 Написать в техподдержку", URI.create("https://t.me/kanat_dev")),
				TelegramClient.Button.link("📍 Как добраться (2ГИС)", URI.create(MAP)));
	}

	@Test
	void reminderInBotHasAddressAndMapButton() {
		update(TestInitData.DOCTOR_ID, "{\"address\": \"ул. Киевская 95\", \"mapUrl\": \"%s\"}".formatted(MAP));
		var zone = ZoneId.of("Asia/Bishkek");
		var start = LocalDate.now(zone).plusDays(2).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant();
		var patient = jdbc.queryForObject(
				"insert into patient (full_name, phone, telegram_id) values ('Бакыт', '996700112233', 999) returning id", Long.class);
		var id = jdbc.queryForObject("insert into appointment (doctor_id, patient_id, start_at, end_at) values (1, ?, ?, ?) returning id",
				Long.class, patient, java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(1800)));

		assertThat(mvc.post().uri("/api/appointments/" + id + "/remind-telegram")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk();

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(999L), text.capture(), buttons.capture());
		assertThat(text.getValue()).contains("\n📍 Адрес: ул. Киевская 95");
		assertThat(buttons.getValue()).extracting(TelegramClient.Button::text)
			.containsExactly("Приду", "Нужно перенести", "📍 Как добраться (2ГИС)");
	}

	private MvcTestResult update(long userId, String body) {
		return mvc.put().uri("/api/clinic").header(HttpHeaders.AUTHORIZATION, "tma " + TestInitData.signed(userId, Instant.now()))
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private void webhook(String body) {
		mvc.post().uri("/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

}

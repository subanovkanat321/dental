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

import com.jayway.jsonpath.JsonPath;
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

/** Сообщения о самозаписи: бот включён (webhook), TelegramClient подменён. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class BookingNotificationsTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long appointmentId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		var start = LocalDate.now(BISHKEK).plusDays(3).atTime(LocalTime.of(10, 0)).atZone(BISHKEK).toInstant();
		clearInvocations(telegram);
		var body = mvc.post().uri("/api/patient/booking").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт Маматов", "consent": true}"""
				.formatted(serviceId, start))
			.exchange().getResponse().getContentAsString();
		appointmentId = ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	@Test
	void doctorGetsRequestWithoutPatientsNameOrPhone() {
		var text = sentTo(TestInitData.DOCTOR_ID);

		assertThat(text).startsWith("Новая заявка: ").contains("10:00 — Осмотр и консультация (30 мин)");
		assertThat(text).doesNotContain("Бакыт", "Маматов", "996700112233", "700112233");
	}

	@Test
	void patientHearsConfirmation() {
		clearInvocations(telegram);
		decide("SCHEDULED");

		assertThat(sentTo(TestInitData.STRANGER_ID)).startsWith("Ваша запись к стоматологу подтверждена: ")
			.contains("в 10:00 — Осмотр и консультация")
			.contains("Телефон клиники: +996 555 000 000");
	}

	@Test
	void patientHearsRejection() {
		clearInvocations(telegram);
		decide("CANCELLED");

		assertThat(sentTo(TestInitData.STRANGER_ID)).startsWith("К сожалению, время ").endsWith("недоступно. Выберите, пожалуйста, другое.");
	}

	@Test
	void doctorHearsPatientCancelled() {
		clearInvocations(telegram);
		mvc.post().uri("/api/patient/booking/cancel").header(HttpHeaders.AUTHORIZATION, stranger()).exchange();

		assertThat(sentTo(TestInitData.DOCTOR_ID)).startsWith("Пациент отменил запись: ").endsWith("Время освободилось.");
	}

	private void decide(String status) {
		mvc.post().uri("/api/appointments/" + appointmentId + "/status")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"version": 0, "status": "%s"}""".formatted(status))
			.exchange();
	}

	private String sentTo(long chatId) {
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(chatId), text.capture(), anyList());
		return text.getValue();
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

}

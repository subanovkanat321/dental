package kg.dental.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
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

/** Напоминание в бот и ответ пациента кнопкой: бот включён (webhook), TelegramClient подменён. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class PatientRemindersTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	static final String SECRET = "test-webhook-secret";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long appointmentId;

	/** Пациент записался через бота, врач подтвердил. */
	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		var start = LocalDate.now(BISHKEK).plusDays(3).atTime(LocalTime.of(10, 0)).atZone(BISHKEK).toInstant();
		var body = mvc.post().uri("/api/patient/booking").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт Маматов", "consent": true}"""
				.formatted(serviceId, start))
			.exchange().getResponse().getContentAsString();
		appointmentId = ((Number) JsonPath.read(body, "$.id")).longValue();
		mvc.post().uri("/api/appointments/" + appointmentId + "/status")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\": 0, \"status\": \"SCHEDULED\"}")
			.exchange();
		clearInvocations(telegram);
	}

	@Test
	void cardShowsThatPatientCanBeRemindedInTelegram() {
		assertThat(mvc.get().uri("/api/appointments/" + appointmentId).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.bodyJson().extractingPath("$.patient.telegram").isEqualTo(true);
	}

	@Test
	void reminderGoesToPatientsChatWithAnswerButtonsAndMarksReminded() {
		assertThat(remind()).hasStatusOk().bodyJson().extractingPath("$.reminderOpenedAt").isNotNull();

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), text.capture(), buttons.capture());
		assertThat(text.getValue()).startsWith("Здравствуйте, Бакыт Маматов! Напоминаем: ").contains("в 10:00 приём у стоматолога");
		assertThat(buttons.getValue()).containsExactly(
				TelegramClient.Button.callback("Приду", "reply:" + appointmentId + ":C"),
				TelegramClient.Button.callback("Нужно перенести", "reply:" + appointmentId + ":R"));
	}

	@Test
	void notDeliveredIs409AndNotMarked() {
		doThrow(TelegramException.class).when(telegram).sendMessage(eq(TestInitData.STRANGER_ID), anyString(), anyList());

		assertThat(remind()).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("not-delivered");
		assertThat(jdbc.queryForObject("select reminder_opened_at from appointment where id = ?", Instant.class, appointmentId))
			.isNull();
	}

	@Test
	void patientWithoutTelegramIs409() {
		jdbc.update("update patient set telegram_id = null");

		assertThat(remind()).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("not-delivered");
		verify(telegram, never()).sendMessage(anyLong(), anyString(), anyList());
	}

	@Test
	void patientOnlyCannotRemind() {
		assertThat(mvc.post().uri("/api/appointments/" + appointmentId + "/remind-telegram")
			.header(HttpHeaders.AUTHORIZATION, stranger())).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void comingButtonSetsReplyThanksPatientAndTellsDoctorWithoutName() {
		assertThat(webhook(callback(TestInitData.STRANGER_ID, "reply:" + appointmentId + ":C"))).hasStatusOk();

		assertThat(reply()).isEqualTo("CONFIRMED");
		verify(telegram).answerCallbackQuery("cb-1", "Спасибо!");
		verify(telegram).removeButtons(TestInitData.STRANGER_ID, 77);
		var thanks = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), thanks.capture());
		assertThat(thanks.getValue()).startsWith("Спасибо! Ждём вас ").endsWith("в 10:00.");
		var doctor = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), doctor.capture(), anyList());
		assertThat(doctor.getValue()).startsWith("Ответ на напоминание: приём ").endsWith("10:00 — пациент придёт.")
			.doesNotContain("Бакыт", "Маматов", "700112233");
	}

	@Test
	void sameAnswerTwiceTellsDoctorOnce() {
		webhook(callback(TestInitData.STRANGER_ID, "reply:" + appointmentId + ":C"));
		webhook(callback(TestInitData.STRANGER_ID, "reply:" + appointmentId + ":C"));

		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(), anyList());
	}

	@Test
	void rescheduleButtonSetsReplyAndPromisesCall() {
		webhook(callback(TestInitData.STRANGER_ID, "reply:" + appointmentId + ":R"));

		assertThat(reply()).isEqualTo("RESCHEDULE");
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), text.capture());
		assertThat(text.getValue()).startsWith("Хорошо, передали врачу").contains("+996 555 000 000");
	}

	@Test
	void someoneElsesButtonChangesNothing() {
		// callback_data подделать легко; кто нажал — знает только Telegram
		webhook(callback(TestInitData.DOCTOR_ID, "reply:" + appointmentId + ":C"));

		assertThat(reply()).isEqualTo("NONE");
		verify(telegram).answerCallbackQuery("cb-1", "Эта запись уже изменилась — откройте приложение");
	}

	@Test
	void cancelledAppointmentIgnoresAnswer() {
		jdbc.update("update appointment set status = 'CANCELLED'");

		webhook(callback(TestInitData.STRANGER_ID, "reply:" + appointmentId + ":C"));

		assertThat(reply()).isEqualTo("NONE");
		verify(telegram, never()).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(), anyList());
	}

	@Test
	void garbageDataIsAnsweredToo() {
		assertThat(webhook(callback(TestInitData.STRANGER_ID, "reply:99999999999999999999:C"))).hasStatusOk();

		verify(telegram).answerCallbackQuery("cb-1", "Эта запись уже изменилась — откройте приложение");
	}

	@Test
	void whenTextIsRelative() {
		var today = LocalDate.of(2026, 10, 23);
		var at = today.atTime(10, 0).atZone(BISHKEK).toInstant();

		assertThat(PatientReminders.when(at, BISHKEK, today)).isEqualTo("сегодня в 10:00");
		assertThat(PatientReminders.when(at.plusSeconds(86400), BISHKEK, today)).isEqualTo("завтра в 10:00");
		assertThat(PatientReminders.when(at.plusSeconds(3 * 86400), BISHKEK, today)).isEqualTo("понедельник, 26 октября, в 10:00");
	}

	private MvcTestResult remind() {
		return mvc.post().uri("/api/appointments/" + appointmentId + "/remind-telegram")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private String reply() {
		return jdbc.queryForObject("select reply from appointment where id = ?", String.class, appointmentId);
	}

	private MvcTestResult webhook(String body) {
		return mvc.post().uri("/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", SECRET)
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String callback(long fromId, String data) {
		return """
				{"update_id": 3, "callback_query": {"id": "cb-1", "data": "%s",
				 "from": {"id": %d, "is_bot": false, "first_name": "Test"},
				 "message": {"message_id": 77, "date": 1767225600,
				  "chat": {"id": %d, "type": "private"}}}}""".formatted(data, fromId, fromId);
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

}

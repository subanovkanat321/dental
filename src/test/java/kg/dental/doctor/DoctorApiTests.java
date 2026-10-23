package kg.dental.doctor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Несколько врачей в одном кабинете. Врач №1 — из миграции (без Telegram: его уведомления —
 * админу 111). Врач №2 заводится в тесте с Telegram 222. 2099-06-01T04:00Z — 10:00 по Бишкеку.
 */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class DoctorApiTests {

	static final long ADMIN = TestInitData.DOCTOR_ID;

	static final long SECOND = 222;

	static final String TEN = "2099-06-01T04:00:00Z";

	/** Начало настоящего JPEG — сервер узнаёт тип по первым байтам. */
	static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long second;

	long patient;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		var body = send(ADMIN, "POST", "/api/doctors", """
				{"fullName": "Айгерим Садыкова", "about": "Ортодонт", "telegramId": 222}""")
			.getResponse().getContentAsString();
		second = ((Number) JsonPath.read(body, "$.id")).longValue();
		patient = jdbc.queryForObject("insert into patient (full_name, phone) values ('Пациент', '996555000000') returning id",
				Long.class);
		clearInvocations(telegram);
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("truncate waitlist_entry, appointment restart identity cascade");
		jdbc.update("delete from time_off where doctor_id is not null");
		jdbc.update("delete from doctor where id <> 1");
		jdbc.update("update doctor set full_name = 'Врач', about = null, telegram_id = null, photo = null, "
				+ "photo_type = null, photo_version = 0, active = true where id = 1");
	}

	@Test
	void secondDoctorGetsInAndSeesBothDoctors() {
		assertThat(send(SECOND, "GET", "/api/doctors", null)).hasStatusOk().bodyJson()
			.extractingPath("$[*].fullName").asArray().containsExactly("Врач", "Айгерим Садыкова");
		var me = assertThat(send(SECOND, "GET", "/api/patient/me", null)).bodyJson();
		me.extractingPath("$.doctor").isEqualTo(true);
		me.extractingPath("$.admin").isEqualTo(false);
		me.extractingPath("$.doctorId").isEqualTo((int) second);
	}

	@Test
	void onlyAdminManagesDoctorsAndTelegramIdIsHiddenFromOthers() {
		assertThat(send(SECOND, "POST", "/api/doctors", "{\"fullName\": \"Кто-то\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(SECOND, "GET", "/api/doctors/admin", null)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(SECOND, "GET", "/api/doctors", null)).bodyJson().doesNotHavePath("$[1].telegramId");
		assertThat(send(ADMIN, "GET", "/api/doctors/admin", null)).bodyJson().extractingPath("$[1].telegramId")
			.isEqualTo((int) SECOND);
		assertThat(send(ADMIN, "POST", "/api/doctors", "{\"fullName\": \"Дубль\", \"telegramId\": 222}"))
			.hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("telegram-taken");
	}

	@Test
	void doctorEditsOwnProfileButNotColleagues() {
		assertThat(send(SECOND, "PUT", "/api/doctors/" + second + "/profile", """
				{"fullName": "Айгерим С.", "about": "Брекеты и элайнеры"}""")).hasStatusOk()
			.bodyJson().extractingPath("$.about").isEqualTo("Брекеты и элайнеры");
		assertThat(send(SECOND, "PUT", "/api/doctors/1/profile", "{\"fullName\": \"x\"}")).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void removedDoctorLosesAccessAtOnce() {
		send(ADMIN, "PUT", "/api/doctors/" + second, "{\"fullName\": \"Айгерим Садыкова\", \"telegramId\": 222, \"active\": false}");

		assertThat(send(SECOND, "GET", "/api/doctors", null)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(TestInitData.STRANGER_ID, "GET", "/api/patient/me", null)).bodyJson()
			.extractingPath("$.doctors[*].fullName").asArray().containsExactly("Врач");
	}

	@Test
	void sameTimeForTwoDoctorsButNotTwiceForOne() {
		assertThat(create(1, TEN)).hasStatus(HttpStatus.CREATED);
		assertThat(create(second, TEN)).hasStatus(HttpStatus.CREATED);
		assertThat(create(second, TEN)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
			.isEqualTo("time-taken");

		assertThat(send(ADMIN, "GET", "/api/appointments?date=2099-06-01&doctorId=" + second, null)).bodyJson()
			.extractingPath("$[*].doctorId").asArray().containsExactly((int) second);
		// Чужая занятость не убирает время из сетки
		assertThat(send(ADMIN, "GET", "/api/slots?date=2099-06-01&duration=30&doctorId=" + second, null)).bodyJson()
			.extractingPath("$.starts").asArray().doesNotContain(TEN);
		assertThat(create(1, "2099-06-01T05:00:00Z")).hasStatus(HttpStatus.CREATED);
		assertThat(send(ADMIN, "GET", "/api/slots?date=2099-06-01&duration=30&doctorId=" + second, null)).bodyJson()
			.extractingPath("$.starts").asArray().contains("2099-06-01T05:00:00Z");
	}

	@Test
	void timeOffOfOneDoctorDoesNotCloseTheOther() {
		send(ADMIN, "POST", "/api/time-off", """
				{"doctorId": %d, "firstDay": "2099-06-01", "title": "Отпуск", "emoji": "🏖️"}""".formatted(second));

		assertThat(send(ADMIN, "GET", "/api/slots?date=2099-06-01&doctorId=" + second, null)).bodyJson()
			.extractingPath("$.dayOff.title").isEqualTo("Отпуск");
		assertThat(send(ADMIN, "GET", "/api/slots?date=2099-06-01&doctorId=1", null)).bodyJson()
			.extractingPath("$.workingDay").isEqualTo(true);
	}

	@Test
	void patientBooksChosenDoctorAndOnlyThatDoctorHears() {
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		var zone = ZoneId.of("Asia/Bishkek");
		var start = LocalDate.now(zone).plusDays(3).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant();
		clearInvocations(telegram);

		var booked = send(TestInitData.STRANGER_ID, "POST", "/api/patient/booking", """
				{"doctorId": %d, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт", "consent": true}"""
			.formatted(second, serviceId, start));

		assertThat(booked).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.doctorName")
			.isEqualTo("Айгерим Садыкова");
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(SECOND), text.capture(), anyList());
		assertThat(text.getValue()).startsWith("Новая заявка: ");
		verify(telegram, never()).sendMessage(eq(ADMIN), anyString(), anyList());
	}

	@Test
	void unlinkedDoctorsNewsGoToAdminWithDoctorsName() {
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		var zone = ZoneId.of("Asia/Bishkek");
		var start = LocalDate.now(zone).plusDays(3).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant();
		clearInvocations(telegram);

		send(TestInitData.STRANGER_ID, "POST", "/api/patient/booking", """
				{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт", "consent": true}"""
			.formatted(serviceId, start));

		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(ADMIN), text.capture(), anyList());
		assertThat(text.getValue()).startsWith("🧑‍⚕️ Врач\nНовая заявка: ");
		verify(telegram, never()).sendMessage(eq(SECOND), anyString(), anyList());
	}

	@Test
	void photoUploadedByDoctorServedWithoutLogin() {
		var uploaded = mvc.post().uri("/api/doctors/" + second + "/photo").header(HttpHeaders.AUTHORIZATION, tma(SECOND))
			.multipart().file(new MockMultipartFile("photo", "me.jpg", "image/jpeg", JPEG)).exchange();
		assertThat(uploaded).hasStatusOk().bodyJson().extractingPath("$.photoUrl")
			.isEqualTo("/doctors/" + second + "/photo?v=1");

		var photo = mvc.get().uri("/doctors/" + second + "/photo?v=1").exchange();
		assertThat(photo).hasStatusOk().hasContentType("image/jpeg");
		assertThat(photo.getResponse().getContentAsByteArray()).isEqualTo(JPEG);

		var notImage = mvc.post().uri("/api/doctors/" + second + "/photo").header(HttpHeaders.AUTHORIZATION, tma(SECOND))
			.multipart().file(new MockMultipartFile("photo", "x.jpg", "image/jpeg", "<script>".getBytes())).exchange();
		assertThat(notImage).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void imageTypeByMagicBytes() {
		assertThat(DoctorService.imageType(JPEG)).contains("image/jpeg");
		assertThat(DoctorService.imageType(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A })).contains("image/png");
		assertThat(DoctorService.imageType("RIFF0000WEBPVP8 ".getBytes())).contains("image/webp");
		assertThat(DoctorService.imageType("GIF89a".getBytes())).isEmpty();
	}

	@Test
	void botGreetsLinkedDoctorAsStaff() {
		mvc.post().uri("/telegram/webhook").header("X-Telegram-Bot-Api-Secret-Token", "test-webhook-secret")
			.contentType(MediaType.APPLICATION_JSON).content("""
					{"update_id": 1, "message": {"message_id": 5, "date": 1767225600, "text": "/start",
					 "from": {"id": 222, "is_bot": false, "first_name": "A"}, "chat": {"id": 222, "type": "private"}}}""")
			.exchange();

		verify(telegram).setChatMenuButton(eq(SECOND), eq("Открыть день"), org.mockito.ArgumentMatchers.any());
		verify(telegram, never()).sendMessage(anyLong(), anyString(), anyList());
	}

	@Test
	void doctorChangesOwnAppointmentsButOnlyViewsColleagues() throws Exception {
		var own = id(send(SECOND, "POST", "/api/appointments", body(second, TEN)));
		var colleagues = id(create(1, TEN));

		// Своё — можно
		assertThat(send(SECOND, "POST", "/api/appointments/" + own + "/reply", "{\"version\": 0, \"reply\": \"CONFIRMED\"}"))
			.hasStatusOk();
		// Чужое — смотреть можно, менять нельзя
		assertThat(send(SECOND, "GET", "/api/appointments/" + colleagues, null)).hasStatusOk();
		assertThat(send(SECOND, "GET", "/api/appointments?date=2099-06-01&doctorId=1", null)).hasStatusOk();
		for (var action : new String[] { "reply", "status", "reschedule", "undo" }) {
			assertThat(send(SECOND, "POST", "/api/appointments/" + colleagues + "/" + action, """
					{"version": 0, "reply": "CONFIRMED", "status": "CANCELLED", "startAt": "2099-06-01T06:00:00Z",
					 "durationMinutes": 30, "previousStatus": "SCHEDULED"}""")).as(action).hasStatus(HttpStatus.FORBIDDEN);
		}
		assertThat(send(SECOND, "POST", "/api/appointments/" + colleagues + "/reminder-opened", null)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(SECOND, "POST", "/api/appointments/" + colleagues + "/remind-telegram", null)).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(SECOND, "POST", "/api/appointments", body(1, "2099-06-01T07:00:00Z"))).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(jdbc.queryForObject("select status from appointment where id = ?", String.class, colleagues))
			.isEqualTo("SCHEDULED");

		// Админ — всё
		assertThat(send(ADMIN, "POST", "/api/appointments/" + own + "/status", "{\"version\": 1, \"status\": \"CANCELLED\"}"))
			.hasStatusOk();
	}

	@Test
	void doctorSetsOwnTimeOffOnlyCommonAndColleaguesAreAdmins() throws Exception {
		assertThat(send(SECOND, "POST", "/api/time-off", """
				{"doctorId": %d, "firstDay": "2099-06-01", "title": "Отпуск"}""".formatted(second))).hasStatus(HttpStatus.CREATED);
		assertThat(send(SECOND, "POST", "/api/time-off", "{\"firstDay\": \"2099-06-01\", \"title\": \"Всем\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(SECOND, "POST", "/api/time-off", "{\"doctorId\": 1, \"firstDay\": \"2099-06-01\", \"title\": \"Ему\"}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		var juma = jdbc.queryForObject("select id from time_off where title = 'Джума-намаз'", Long.class);
		assertThat(mvc.delete().uri("/api/time-off/" + juma).header(HttpHeaders.AUTHORIZATION, tma(SECOND)))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	private String body(long doctorId, String startAt) {
		return """
				{"doctorId": %d, "patientId": %d, "startAt": "%s", "durationMinutes": 30}""".formatted(doctorId, patient, startAt);
	}

	private static long id(MvcTestResult result) throws Exception {
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private MvcTestResult create(long doctorId, String startAt) {
		return send(ADMIN, "POST", "/api/appointments", """
				{"doctorId": %d, "patientId": %d, "startAt": "%s", "durationMinutes": 30}""".formatted(doctorId, patient, startAt));
	}

	private MvcTestResult send(long userId, String method, String uri, String body) {
		var request = switch (method) {
			case "GET" -> mvc.get().uri(uri);
			case "PUT" -> mvc.put().uri(uri);
			default -> mvc.post().uri(uri);
		};
		request.header(HttpHeaders.AUTHORIZATION, tma(userId));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return request.exchange();
	}

	private static String tma(long userId) {
		return "tma " + TestInitData.signed(userId, Instant.now());
	}

}

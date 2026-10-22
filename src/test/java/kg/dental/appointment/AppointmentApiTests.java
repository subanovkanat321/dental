package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * API приёмов на настоящем Postgres. Будущие приёмы — в 2099 году, прошедшие — в 2020,
 * чтобы тесты не зависели от сегодняшней даты. 2099-06-01T04:00:00Z — 10:00 по Бишкеку.
 */
@IntegrationTest
class AppointmentApiTests {

	static final String FUTURE = "2099-06-01T04:00:00Z";

	static final String PAST = "2020-06-01T04:00:00Z";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long patientId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate appointment, patient restart identity cascade");
		patientId = jdbc.queryForObject(
				"insert into patient (full_name, phone) values ('Тестовый пациент', '996555000000') returning id",
				Long.class);
	}

	// --- запись и двойная запись (T1) ---

	@Test
	void createsScheduledAppointment() {
		var result = create(FUTURE, 30);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		var json = assertThat(result).bodyJson();
		json.extractingPath("$.endAt").isEqualTo("2099-06-01T04:30:00Z");
		json.extractingPath("$.status").isEqualTo("SCHEDULED");
		json.extractingPath("$.reply").isEqualTo("NONE");
		json.extractingPath("$.version").isEqualTo(0);
		json.extractingPath("$.patient.phone").isEqualTo("996555000000");
	}

	@Test
	void secondBookingOfSameTimeIs409TimeTaken() {
		create(FUTURE, 30);

		var second = create("2099-06-01T04:15:00Z", 30);

		assertThat(second).hasStatus(HttpStatus.CONFLICT);
		assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("time-taken");
		assertThat(second).bodyJson().extractingPath("$.detail").isEqualTo("Время занято");
		assertThat(count()).isEqualTo(1);
	}

	@Test
	void backToBackBookingIsAllowed() {
		create(FUTURE, 30);

		assertThat(create("2099-06-01T04:30:00Z", 30)).hasStatus(HttpStatus.CREATED);
	}

	@Test
	void durationOutsideFifteenMinutesToFourHoursIs422() {
		assertThat(create(FUTURE, 10)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.errors[0].field")
			.isEqualTo("durationMinutes");
		assertThat(create(FUTURE, 241)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void unknownPatientIs404() {
		var result = mvc.post().uri("/api/appointments").contentType(MediaType.APPLICATION_JSON).content("""
				{"patientId": 999999, "startAt": "%s", "durationMinutes": 30}""".formatted(FUTURE)).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.detail")
			.isEqualTo("Пациент не найден");
	}

	// --- перенос ---

	@Test
	void rescheduleResetsReplyAndReminder() {
		var id = id(create(FUTURE, 30));
		post(id, "reminder-opened", "");
		var confirmed = post(id, "reply", """
				{"version": 0, "reply": "CONFIRMED"}""");
		assertThat(confirmed).bodyJson().extractingPath("$.reply").isEqualTo("CONFIRMED");

		var moved = post(id, "reschedule", """
				{"version": 1, "startAt": "2099-06-02T05:00:00Z", "durationMinutes": 45}""");

		assertThat(moved).hasStatusOk();
		var json = assertThat(moved).bodyJson();
		json.extractingPath("$.startAt").isEqualTo("2099-06-02T05:00:00Z");
		json.extractingPath("$.endAt").isEqualTo("2099-06-02T05:45:00Z");
		json.extractingPath("$.reply").isEqualTo("NONE");
		json.extractingPath("$.reminderOpenedAt").isNull();
		json.extractingPath("$.version").isEqualTo(2);
	}

	@Test
	void rescheduleOntoTakenTimeIs409AndKeepsOldTime() {
		create(FUTURE, 30);
		var id = id(create("2099-06-01T05:00:00Z", 30));

		assertThat(post(id, "reschedule", """
				{"version": 0, "startAt": "2099-06-01T04:10:00Z", "durationMinutes": 30}"""))
			.hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("time-taken");
		assertThat(startAt(id)).isEqualTo(Instant.parse("2099-06-01T05:00:00Z"));
	}

	@Test
	void rescheduleOverItsOwnOldTimeIsAllowed() {
		var id = id(create(FUTURE, 30));

		assertThat(post(id, "reschedule", """
				{"version": 0, "startAt": "2099-06-01T04:15:00Z", "durationMinutes": 30}""")).hasStatusOk();
	}

	// --- версия (D18) ---

	@Test
	void staleVersionIs409AndChangesNothing() {
		var id = id(create(FUTURE, 30));
		post(id, "reply", """
				{"version": 0, "reply": "CONFIRMED"}""");

		// Второе окно ещё видит версию 0
		var stale = post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");

		assertThat(stale).hasStatus(HttpStatus.CONFLICT);
		assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("stale");
		assertThat(stale).bodyJson().extractingPath("$.detail").isEqualTo("Приём изменён, обновите");
		assertThat(status(id)).isEqualTo("SCHEDULED");
	}

	@Test
	void missingVersionIs422() {
		var id = id(create(FUTURE, 30));

		assertThat(post(id, "status", """
				{"status": "CANCELLED"}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	// --- статусы (R6, D8, O5) ---

	@Test
	void cancelledAppointmentFreesTimeAndCanBeRestoredWhenFree() {
		var id = id(create(FUTURE, 30));
		post(id, "reply", """
				{"version": 0, "reply": "CONFIRMED"}""");
		assertThat(post(id, "status", """
				{"version": 1, "status": "CANCELLED"}""")).hasStatusOk();

		var restored = post(id, "status", """
				{"version": 2, "status": "SCHEDULED"}""");

		assertThat(restored).hasStatusOk();
		// «Восстановить» сбрасывает ответ: о приёме нужно напомнить заново (в отличие от undo)
		assertThat(restored).bodyJson().extractingPath("$.reply").isEqualTo("NONE");
	}

	@Test
	void restoringCancelledOntoTakenTimeIs409() {
		var id = id(create(FUTURE, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");
		assertThat(create(FUTURE, 30)).hasStatus(HttpStatus.CREATED);

		assertThat(post(id, "status", """
				{"version": 1, "status": "SCHEDULED"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("time-taken");
		assertThat(status(id)).isEqualTo("CANCELLED");
	}

	@Test
	void invalidTransitionIs409() {
		var id = id(create(PAST, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");

		assertThat(post(id, "status", """
				{"version": 1, "status": "DONE"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("invalid-transition");
	}

	@Test
	void futureAppointmentCannotBeMarkedDoneOrNoShow() {
		var id = id(create(FUTURE, 30));

		assertThat(post(id, "status", """
				{"version": 0, "status": "DONE"}""")).hasStatus(HttpStatus.CONFLICT);
		assertThat(post(id, "status", """
				{"version": 0, "status": "NO_SHOW"}""")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void pastAppointmentCanBeMarkedAndCorrectedDoneNoShow() {
		var id = id(create(PAST, 30));

		assertThat(post(id, "status", """
				{"version": 0, "status": "NO_SHOW"}""")).hasStatusOk();
		assertThat(post(id, "status", """
				{"version": 1, "status": "DONE"}""")).hasStatusOk();
		assertThat(post(id, "status", """
				{"version": 2, "status": "NO_SHOW"}""")).hasStatusOk();
		// Из неявки обратно в «запланирован» — только через undo
		assertThat(post(id, "status", """
				{"version": 3, "status": "SCHEDULED"}""")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void noShowFreesTimeForWalkInAndCorrectingItBackIs409() {
		var id = id(create(PAST, 30));
		post(id, "status", """
				{"version": 0, "status": "NO_SHOW"}""");

		// Пришедший без записи в освободившееся окно (D15)
		assertThat(create("2020-06-01T04:15:00Z", 30)).hasStatus(HttpStatus.CREATED);

		assertThat(post(id, "status", """
				{"version": 1, "status": "DONE"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("time-taken");
	}

	// --- ответ пациента и напоминание ---

	@Test
	void replyOnlyForScheduled() {
		var id = id(create(FUTURE, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");

		assertThat(post(id, "reply", """
				{"version": 1, "reply": "CONFIRMED"}""")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void reminderOpenedIsMarkedWithoutChangingVersion() {
		var id = id(create(FUTURE, 30));

		assertThat(post(id, "reminder-opened", "")).hasStatus(HttpStatus.NO_CONTENT);

		var json = assertThat(mvc.get().uri("/api/appointments/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk().bodyJson();
		json.extractingPath("$.reminderOpenedAt").isNotNull();
		json.extractingPath("$.version").isEqualTo(0);
		// Карточка, открытая до нажатия WhatsApp, по-прежнему может менять приём
		assertThat(post(id, "reply", """
				{"version": 0, "reply": "CONFIRMED"}""")).hasStatusOk();
	}

	@Test
	void reminderForUnknownAppointmentIs404AndForCancelledIs409() {
		assertThat(post(999999, "reminder-opened", "")).hasStatus(HttpStatus.NOT_FOUND);

		var id = id(create(FUTURE, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");
		assertThat(post(id, "reminder-opened", "")).hasStatus(HttpStatus.CONFLICT);
	}

	// --- undo (ER1) ---

	@Test
	void undoCancelKeepsReplyAndReminder() {
		var id = id(create(FUTURE, 30));
		post(id, "reminder-opened", "");
		post(id, "reply", """
				{"version": 0, "reply": "CONFIRMED"}""");
		post(id, "status", """
				{"version": 1, "status": "CANCELLED"}""");

		var undone = post(id, "undo", """
				{"version": 2, "previousStatus": "SCHEDULED"}""");

		assertThat(undone).hasStatusOk();
		var json = assertThat(undone).bodyJson();
		json.extractingPath("$.status").isEqualTo("SCHEDULED");
		json.extractingPath("$.reply").isEqualTo("CONFIRMED");
		json.extractingPath("$.reminderOpenedAt").isNotNull();
	}

	@Test
	void undoNoShowReturnsPreviousStatus() {
		var id = id(create(PAST, 30));
		post(id, "status", """
				{"version": 0, "status": "DONE"}""");
		post(id, "status", """
				{"version": 1, "status": "NO_SHOW"}""");

		assertThat(post(id, "undo", """
				{"version": 2, "previousStatus": "DONE"}""")).hasStatusOk()
			.bodyJson().extractingPath("$.status").isEqualTo("DONE");
	}

	@Test
	void undoOntoTakenTimeIs409() {
		var id = id(create(FUTURE, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");
		create(FUTURE, 30);

		assertThat(post(id, "undo", """
				{"version": 1, "previousStatus": "SCHEDULED"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("time-taken");
	}

	@Test
	void undoWithStaleVersionIs409() {
		var id = id(create(FUTURE, 30));
		post(id, "status", """
				{"version": 0, "status": "CANCELLED"}""");

		assertThat(post(id, "undo", """
				{"version": 0, "previousStatus": "SCHEDULED"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("stale");
	}

	@Test
	void undoOfNothingIs409() {
		var id = id(create(FUTURE, 30));

		assertThat(post(id, "undo", """
				{"version": 0, "previousStatus": "CANCELLED"}""")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("invalid-transition");
	}

	// --- день ---

	@Test
	void dayIsCalendarDayInBishkekWithCancelledIncluded() {
		// 2099-05-31T18:00Z = 1 июня 00:00 по Бишкеку (UTC+6) — уже 1 июня
		create("2099-05-31T18:00:00Z", 30);
		// 2099-05-31T17:30Z = 31 мая 23:30 по Бишкеку — ещё 31 мая
		create("2099-05-31T17:30:00Z", 30);
		var cancelled = id(create("2099-06-01T05:00:00Z", 30));
		post(cancelled, "status", """
				{"version": 0, "status": "CANCELLED"}""");
		// 2099-06-01T18:00Z — уже 2 июня
		create("2099-06-01T18:00:00Z", 30);

		var json = assertThat(mvc.get().uri("/api/appointments?date=2099-06-01").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk().bodyJson();
		json.extractingPath("$[*].startAt").asArray()
			.containsExactly("2099-05-31T18:00:00Z", "2099-06-01T05:00:00Z");
		json.extractingPath("$[1].status").isEqualTo("CANCELLED");
		json.extractingPath("$[0].patient.fullName").isEqualTo("Тестовый пациент");
	}

	private MvcTestResult create(String startAt, int durationMinutes) {
		return mvc.post().uri("/api/appointments").contentType(MediaType.APPLICATION_JSON).content("""
				{"patientId": %d, "startAt": "%s", "durationMinutes": %d}"""
			.formatted(patientId, startAt, durationMinutes)).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private MvcTestResult post(long id, String action, String body) {
		var request = mvc.post().uri("/api/appointments/{id}/{action}", id, action);
		return (body.isEmpty() ? request : request.contentType(MediaType.APPLICATION_JSON).content(body)).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private static long id(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return ((Number) JsonPath.read(contentOf(result), "$.id")).longValue();
	}

	private static String contentOf(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private int count() {
		return jdbc.queryForObject("select count(*) from appointment", Integer.class);
	}

	private String status(long id) {
		return jdbc.queryForObject("select status from appointment where id = ?", String.class, id);
	}

	private Instant startAt(long id) {
		return jdbc.queryForObject("select start_at from appointment where id = ?", Timestamp.class, id).toInstant();
	}

}

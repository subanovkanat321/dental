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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Пациент сам переносит свою запись: у того же врача, по сетке свободного, снова на подтверждение. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class PatientRescheduleTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	LocalDate day;

	long appointmentId;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
		var serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
		day = LocalDate.now(BISHKEK).plusDays(3);
		var body = patient("POST", "/api/patient/booking", """
				{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "Бакыт", "consent": true}"""
			.formatted(serviceId, at(10, 0))).getResponse().getContentAsString();
		appointmentId = ((Number) JsonPath.read(body, "$.id")).longValue();
		mvc.post().uri("/api/appointments/" + appointmentId + "/status").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0, \"status\": \"SCHEDULED\"}").exchange();
		clearInvocations(telegram);
	}

	@Test
	void movesToFreeTimeWaitsForDoctorAndDoctorHears() {
		var moved = patient("POST", "/api/patient/booking/reschedule", "{\"startAt\": \"%s\"}".formatted(at(12, 0)));

		assertThat(moved).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("REQUESTED");
		assertThat(jdbc.queryForObject("select start_at from appointment where id = ?", Instant.class, appointmentId))
			.isEqualTo(at(12, 0));
		assertThat(jdbc.queryForObject("select end_at from appointment where id = ?", Instant.class, appointmentId))
			.isEqualTo(at(12, 30));
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), text.capture(), anyList());
		assertThat(text.getValue()).startsWith("Пациент перенёс запись: ").contains("10:00 → ").contains("12:00")
			.doesNotContain("Бакыт");
	}

	@Test
	void ownOldTimeIsFreeForMeButTakenTimeAndOffGridAre409() {
		assertThat(patient("GET", "/api/patient/booking/slots?date=" + day, null)).bodyJson()
			.extractingPath("$.starts").asArray().contains(at(10, 0).toString());

		var other = jdbc.queryForObject("insert into patient (full_name, phone) values ('Другой', '996555000001') returning id", Long.class);
		jdbc.update("insert into appointment (doctor_id, patient_id, start_at, end_at) values (1, ?, ?, ?)", other,
				java.sql.Timestamp.from(at(15, 0)), java.sql.Timestamp.from(at(15, 30)));

		assertThat(patient("POST", "/api/patient/booking/reschedule", "{\"startAt\": \"%s\"}".formatted(at(15, 0))))
			.hasStatus(HttpStatus.CONFLICT);
		assertThat(patient("POST", "/api/patient/booking/reschedule", "{\"startAt\": \"%s\"}".formatted(at(12, 7))))
			.hasStatus(HttpStatus.CONFLICT);
		assertThat(jdbc.queryForObject("select status from appointment where id = ?", String.class, appointmentId))
			.isEqualTo("SCHEDULED");
	}

	@Test
	void withoutBookingIs404() {
		jdbc.update("update appointment set status = 'CANCELLED'");

		assertThat(patient("POST", "/api/patient/booking/reschedule", "{\"startAt\": \"%s\"}".formatted(at(12, 0))))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	private Instant at(int hour, int minute) {
		return day.atTime(LocalTime.of(hour, minute)).atZone(BISHKEK).toInstant();
	}

	private MvcTestResult patient(String method, String uri, String body) {
		var request = "GET".equals(method) ? mvc.get().uri(uri) : mvc.post().uri(uri);
		request.header(HttpHeaders.AUTHORIZATION, "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now()));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return request.exchange();
	}

}

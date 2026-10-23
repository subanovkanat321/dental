package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.AfterEach;
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
 * Часы работы у каждого врача. 2099-06-01 — понедельник, 2099-06-02 — вторник, 2099-06-03 — среда.
 * Часы клиники по умолчанию — каждый день 09:00–18:00 (WORK_DAYS=MON-SUN; UTC+6: 03:00–12:00 UTC).
 */
@IntegrationTest
class DoctorHoursApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long second;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment restart identity cascade");
		second = jdbc.queryForObject("insert into doctor (full_name, telegram_id) values ('Айгерим', 222) returning id", Long.class);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("delete from doctor_hours");
		jdbc.update("delete from doctor where id <> 1");
	}

	@Test
	void withoutOwnHoursDoctorWorksClinicHours() {
		var json = assertThat(send(TestInitData.DOCTOR_ID, "GET", "/api/doctors/" + second + "/hours", null)).hasStatusOk().bodyJson();
		json.extractingPath("$.custom").isEqualTo(false);
		json.extractingPath("$.days[*].weekday").asArray()
			.containsExactly("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
		json.extractingPath("$.days[0].start").isEqualTo("09:00:00");
	}

	@Test
	void ownHoursChangeOnlyThisDoctorsGrid() {
		assertThat(send(222, "PUT", "/api/doctors/" + second + "/hours", """
				{"days": [{"weekday": "MONDAY", "start": "09:00", "end": "15:00"},
				          {"weekday": "WEDNESDAY", "start": "12:00", "end": "19:00"}]}""")).hasStatusOk()
			.bodyJson().extractingPath("$.custom").isEqualTo(true);

		var monday = assertThat(slots(second, "2099-06-01")).bodyJson();
		monday.extractingPath("$.workEnd").isEqualTo("15:00:00");
		monday.extractingPath("$.free[0].end").isEqualTo("2099-06-01T09:00:00Z");
		assertThat(slots(second, "2099-06-02")).bodyJson().extractingPath("$.workingDay").isEqualTo(false);
		assertThat(slots(second, "2099-06-03")).bodyJson().extractingPath("$.free[0].start").isEqualTo("2099-06-03T06:00:00Z");
		// У врача №1 — по-прежнему часы клиники
		assertThat(slots(1, "2099-06-02")).bodyJson().extractingPath("$.free[0].end").isEqualTo("2099-06-02T12:00:00Z");
	}

	@Test
	void emptyListReturnsToClinicHours() {
		send(222, "PUT", "/api/doctors/" + second + "/hours", "{\"days\": [{\"weekday\": \"MONDAY\", \"start\": \"09:00\", \"end\": \"12:00\"}]}");

		assertThat(send(222, "PUT", "/api/doctors/" + second + "/hours", "{\"days\": []}")).bodyJson()
			.extractingPath("$.custom").isEqualTo(false);
		assertThat(slots(second, "2099-06-02")).bodyJson().extractingPath("$.workingDay").isEqualTo(true);
	}

	@Test
	void colleagueCannotChangeAndBadHoursAre422() {
		assertThat(send(222, "PUT", "/api/doctors/1/hours", "{\"days\": []}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(send(222, "PUT", "/api/doctors/" + second + "/hours",
				"{\"days\": [{\"weekday\": \"MONDAY\", \"start\": \"15:00\", \"end\": \"09:00\"}]}"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(send(TestInitData.DOCTOR_ID, "PUT", "/api/doctors/" + second + "/hours",
				"{\"days\": [{\"weekday\": \"MONDAY\", \"start\": \"10:00\", \"end\": \"14:00\"}]}")).hasStatusOk();
	}

	private MvcTestResult slots(long doctorId, String date) {
		return send(TestInitData.DOCTOR_ID, "GET", "/api/slots?date=" + date + "&doctorId=" + doctorId, null);
	}

	private MvcTestResult send(long userId, String method, String uri, String body) {
		var request = "GET".equals(method) ? mvc.get().uri(uri) : mvc.put().uri(uri);
		request.header(HttpHeaders.AUTHORIZATION, "tma " + TestInitData.signed(userId, Instant.now()));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return request.exchange();
	}

}

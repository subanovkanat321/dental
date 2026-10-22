package kg.dental.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
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

@IntegrationTest
class WaitlistApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long aigul;

	long bakyt;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
		aigul = patient("Айгуль Т.", "996555123456");
		bakyt = patient("Бакыт М.", "996700112233");
	}

	@Test
	void addListsOldestFirstWithUpcomingAppointment() {
		var future = appointment(bakyt, "2099-06-10T04:00:00Z", "SCHEDULED");

		assertThat(add(aigul, 30)).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.upcoming").isNull();
		assertThat(add(bakyt, 45)).hasStatus(HttpStatus.CREATED);

		var json = assertThat(get("/api/waitlist")).hasStatusOk().bodyJson();
		json.extractingPath("$[*].patient.fullName").asArray().containsExactly("Айгуль Т.", "Бакыт М.");
		json.extractingPath("$[1].durationMinutes").isEqualTo(45);
		json.extractingPath("$[1].upcoming.id").isEqualTo((int) future);
	}

	@Test
	void secondEntryForSamePatientIs409AndValidation() {
		add(aigul, 30);

		assertThat(add(aigul, 60)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
			.isEqualTo("already-waiting");
		assertThat(add(bakyt, 10)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(add(999999, 30)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void bookingNotYetBookedPatientCreatesAppointmentAndLeavesList() {
		var entry = id(add(aigul, 45));

		var booked = book(entry, "2099-06-01T05:00:00Z");

		assertThat(booked).hasStatusOk();
		assertThat(booked).bodyJson().extractingPath("$.endAt").isEqualTo("2099-06-01T05:45:00Z");
		assertThat(count("appointment")).isEqualTo(1);
		assertThat(count("waitlist_entry")).isZero();
	}

	@Test
	void bookingAlreadyBookedPatientMovesTheAppointmentInsteadOfDuplicating() {
		var existing = appointment(aigul, "2099-06-10T04:00:00Z", "SCHEDULED");
		jdbc.update("update appointment set reply = 'CONFIRMED' where id = ?", existing);
		var entry = id(add(aigul, 30));

		var booked = book(entry, "2099-06-01T05:00:00Z");

		assertThat(booked).hasStatusOk();
		var json = assertThat(booked).bodyJson();
		json.extractingPath("$.id").isEqualTo((int) existing);
		json.extractingPath("$.startAt").isEqualTo("2099-06-01T05:00:00Z");
		// Как при обычном переносе: о новом времени пациента нужно предупредить заново
		json.extractingPath("$.reply").isEqualTo("NONE");
		assertThat(count("appointment")).isEqualTo(1);
		assertThat(count("waitlist_entry")).isZero();
	}

	@Test
	void takenTimeIs409AndPatientStaysInList() {
		appointment(bakyt, "2099-06-01T05:00:00Z", "SCHEDULED");
		var entry = id(add(aigul, 30));

		assertThat(book(entry, "2099-06-01T05:15:00Z")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("time-taken");
		assertThat(count("waitlist_entry")).isEqualTo(1);
		assertThat(count("appointment")).isEqualTo(1);
	}

	@Test
	void removeAndRemoveAgain() {
		var entry = id(add(aigul, 30));

		assertThat(mvc.delete().uri("/api/waitlist/" + entry).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.delete().uri("/api/waitlist/" + entry).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NOT_FOUND);
		assertThat(get("/api/waitlist")).bodyJson().extractingPath("$").asArray().isEmpty();
	}

	private MvcTestResult add(long patientId, int duration) {
		return post("/api/waitlist", """
				{"patientId": %d, "durationMinutes": %d}""".formatted(patientId, duration));
	}

	private MvcTestResult book(long entry, String startAt) {
		return post("/api/waitlist/" + entry + "/book", """
				{"startAt": "%s"}""".formatted(startAt));
	}

	private MvcTestResult post(String uri, String body) {
		return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult get(String uri) {
		return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private static long id(MvcTestResult result) {
		try {
			return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id"))
				.longValue();
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private long patient(String name, String phone) {
		return jdbc.queryForObject("insert into patient (full_name, phone) values (?, ?) returning id", Long.class,
				name, phone);
	}

	private long appointment(long patient, String start, String status) {
		var startAt = Instant.parse(start);
		return jdbc.queryForObject(
				"insert into appointment (patient_id, start_at, end_at, status) values (?, ?, ?, ?) returning id",
				Long.class, patient, Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)), status);
	}

}

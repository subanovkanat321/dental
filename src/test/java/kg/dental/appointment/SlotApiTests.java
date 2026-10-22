package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * /api/slots на настоящем Postgres. 2099-06-01 — понедельник; рабочие часы по умолчанию
 * 09:00–18:00 по Бишкеку = 03:00–12:00 UTC.
 */
@IntegrationTest
class SlotApiTests {

	static final String DAY = "2099-06-01";

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

	@Test
	void emptyWorkingDayIsOneWindowAndFullGrid() {
		var json = assertThat(slots("?date=" + DAY + "&duration=30")).hasStatusOk().bodyJson();

		json.extractingPath("$.workingDay").isEqualTo(true);
		json.extractingPath("$.workStart").isEqualTo("09:00:00");
		json.extractingPath("$.free[0].start").isEqualTo("2099-06-01T03:00:00Z");
		json.extractingPath("$.free[0].end").isEqualTo("2099-06-01T12:00:00Z");
		json.extractingPath("$.starts").asArray().hasSize(35);
	}

	@Test
	void withoutDurationOnlyWindows() {
		insert("2099-06-01T05:00:00Z", "2099-06-01T06:00:00Z", "SCHEDULED");

		var json = assertThat(slots("?date=" + DAY)).hasStatusOk().bodyJson();
		json.extractingPath("$.free[*].start").asArray().containsExactly("2099-06-01T03:00:00Z", "2099-06-01T06:00:00Z");
		json.extractingPath("$.starts").asArray().isEmpty();
	}

	/**
	 * Главное свойство: сетка совпадает с ограничением базы. Время из сетки всегда можно
	 * занять, а время, которого в сетке нет из-за приёма, база занять не даст.
	 */
	@ParameterizedTest
	@EnumSource(AppointmentStatus.class)
	void gridAgreesWithDatabaseConstraintForEveryStatus(AppointmentStatus status) {
		insert("2099-06-01T04:00:00Z", "2099-06-01T04:30:00Z", status.name()); // 10:00 по Бишкеку

		var offered = jsonStarts(slots("?date=" + DAY + "&duration=30")).contains("2099-06-01T04:00:00Z");
		var booked = book("2099-06-01T04:00:00Z").getResponse().getStatus() == HttpStatus.CREATED.value();

		assertThat(offered).as("сетка предлагает 10:00 при статусе %s", status).isEqualTo(booked);
		var occupies = status == AppointmentStatus.SCHEDULED || status == AppointmentStatus.DONE;
		assertThat(offered).isEqualTo(!occupies);
	}

	@Test
	void rescheduledAppointmentDoesNotBlockItself() {
		var id = insert("2099-06-01T04:00:00Z", "2099-06-01T04:30:00Z", "SCHEDULED");

		assertThat(jsonStarts(slots("?date=" + DAY + "&duration=30"))).doesNotContain("2099-06-01T04:00:00Z");
		assertThat(jsonStarts(slots("?date=" + DAY + "&duration=30&exclude=" + id))).contains("2099-06-01T04:00:00Z");
	}

	@Test
	void pastDayHasNothingFree() {
		var json = assertThat(slots("?date=2020-06-01&duration=30")).hasStatusOk().bodyJson();
		json.extractingPath("$.free").asArray().isEmpty();
		json.extractingPath("$.starts").asArray().isEmpty();
	}

	@Test
	void badDurationIs422AndNoLoginIs401() {
		assertThat(slots("?date=" + DAY + "&duration=10")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.errors[0].field")
			.isEqualTo("durationMinutes");
		assertThat(mvc.get().uri("/api/slots?date=" + DAY)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	private MvcTestResult slots(String query) {
		return mvc.get().uri("/api/slots" + query).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private MvcTestResult book(String startAt) {
		return mvc.post().uri("/api/appointments").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"patientId": %d, "startAt": "%s", "durationMinutes": 30}""".formatted(patientId, startAt))
			.exchange();
	}

	private static List<String> jsonStarts(MvcTestResult result) {
		try {
			return JsonPath.read(result.getResponse().getContentAsString(), "$.starts");
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private long insert(String start, String end, String status) {
		return jdbc.queryForObject("""
				insert into appointment (patient_id, start_at, end_at, status)
				values (?, ?::timestamptz, ?::timestamptz, ?) returning id""", Long.class, patientId, start, end, status);
	}

}

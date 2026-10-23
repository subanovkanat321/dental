package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class PatientCardApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long patientId;

	long otherId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate appointment, patient restart identity cascade");
		patientId = patient("Айгуль Т.", "996555123456");
		otherId = patient("Бакыт М.", "996700112233");
	}

	@Test
	void historyStatsAndUpcomingOfOnePatient() {
		insert(patientId, "2020-06-01T04:00:00Z", "DONE");
		insert(patientId, "2020-07-01T04:00:00Z", "DONE");
		insert(patientId, "2020-08-01T04:00:00Z", "NO_SHOW");
		insert(patientId, "2020-09-01T04:00:00Z", "CANCELLED");
		insert(patientId, "2099-06-02T04:00:00Z", "SCHEDULED");
		insert(patientId, "2099-06-01T04:00:00Z", "SCHEDULED");
		insert(patientId, "2099-07-01T04:00:00Z", "CANCELLED");
		insert(otherId, "2020-06-01T05:00:00Z", "DONE"); // чужой приём не попадает

		var json = assertThat(card(patientId)).hasStatusOk().bodyJson();
		json.extractingPath("$.patient.fullName").isEqualTo("Айгуль Т.");
		json.extractingPath("$.done").isEqualTo(2);
		json.extractingPath("$.noShow").isEqualTo(1);
		json.extractingPath("$.cancelled").isEqualTo(2);
		// Впереди — по возрастанию, ближайший первым; отменённый будущий — в прошлом списке
		json.extractingPath("$.upcoming[*].startAt").asArray()
			.containsExactly("2099-06-01T04:00:00Z", "2099-06-02T04:00:00Z");
		json.extractingPath("$.past[*].startAt").asArray()
			.containsExactly("2099-07-01T04:00:00Z", "2020-09-01T04:00:00Z", "2020-08-01T04:00:00Z",
					"2020-07-01T04:00:00Z", "2020-06-01T04:00:00Z");
	}

	@Test
	void pastScheduledWithoutMarkIsInPast() {
		insert(patientId, "2020-06-01T04:00:00Z", "SCHEDULED");

		var json = assertThat(card(patientId)).hasStatusOk().bodyJson();
		json.extractingPath("$.upcoming").asArray().isEmpty();
		json.extractingPath("$.past[0].status").isEqualTo("SCHEDULED");
	}

	@Test
	void unknownPatientIs404() {
		assertThat(card(999999)).hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult card(long id) {
		return mvc.get().uri("/api/appointments/by-patient/" + id)
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.exchange();
	}

	private long patient(String name, String phone) {
		return jdbc.queryForObject("insert into patient (full_name, phone) values (?, ?) returning id", Long.class,
				name, phone);
	}

	private void insert(long patient, String start, String status) {
		var startAt = Instant.parse(start);
		jdbc.update("insert into appointment (doctor_id, patient_id, start_at, end_at, status) values (1, ?, ?, ?, ?)", patient,
				Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)), status);
	}

}

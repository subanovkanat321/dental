package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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

/** Удаление: приём, все приёмы («с чистого листа»), услуга — только админ. */
@IntegrationTest
class DeleteApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long patient;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
		jdbc.update("update doctor set telegram_id = 222 where id = 1");
		patient = jdbc.queryForObject("insert into patient (full_name, phone) values ('Пациент', '996555000000') returning id", Long.class);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("update doctor set telegram_id = null where id = 1");
		jdbc.execute("truncate appointment restart identity cascade");
		jdbc.update("delete from service_type where name in ('Тест-услуга', 'Тест-с-записью')");
	}

	@Test
	void adminDeletesOneAppointmentDoctorCannot() {
		var id = insert("2099-06-01T04:00:00Z", null);
		assertThat(mvc.delete().uri("/api/appointments/" + id).header(HttpHeaders.AUTHORIZATION, tma(222)))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.delete().uri("/api/appointments/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(count()).isZero();
	}

	@Test
	void deleteAllNeedsWordAndKeepsPatientsAndDoctors() {
		insert("2099-06-01T04:00:00Z", null);
		insert("2099-06-02T04:00:00Z", null);

		assertThat(deleteAll("удалю")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(count()).isEqualTo(2);
		assertThat(deleteAll(" удалить ")).hasStatusOk().bodyJson().extractingPath("$.deleted").isEqualTo(2);
		assertThat(count()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from doctor", Integer.class)).isPositive();
	}

	@Test
	void unusedServiceIsDeletedUsedOneIsHidden() {
		var unused = jdbc.queryForObject("insert into service_type (name, duration_minutes) values ('Тест-услуга', 30) returning id", Long.class);
		var used = jdbc.queryForObject("insert into service_type (name, duration_minutes) values ('Тест-с-записью', 30) returning id", Long.class);
		insert("2099-06-01T04:00:00Z", used);

		assertThat(mvc.delete().uri("/api/service-types/" + unused).header(HttpHeaders.AUTHORIZATION, tma(222)))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.delete().uri("/api/service-types/" + unused).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.bodyJson().extractingPath("$.deleted").isEqualTo(true);
		assertThat(mvc.delete().uri("/api/service-types/" + used).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.bodyJson().extractingPath("$.deleted").isEqualTo(false);
		assertThat(jdbc.queryForObject("select count(*) from service_type where id = ?", Integer.class, unused)).isZero();
		assertThat(jdbc.queryForObject("select active from service_type where id = ?", Boolean.class, used)).isFalse();
	}

	private org.springframework.test.web.servlet.assertj.MvcTestResult deleteAll(String word) {
		return mvc.post().uri("/api/appointments/delete-all").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content("{\"confirm\": \"" + word + "\"}").exchange();
	}

	private long insert(String startAt, Long serviceId) {
		var start = Instant.parse(startAt);
		return jdbc.queryForObject("""
				insert into appointment (doctor_id, patient_id, start_at, end_at, service_type_id) values (1, ?, ?, ?, ?) returning id""",
				Long.class, patient, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)), serviceId);
	}

	private int count() {
		return jdbc.queryForObject("select count(*) from appointment", Integer.class);
	}

	private static String tma(long userId) {
		return "tma " + TestInitData.signed(userId, Instant.now());
	}

}

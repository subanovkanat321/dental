package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
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

/** «Пора на профосмотр» — по настоящему времени: визиты считаются в днях от «сейчас». */
@IntegrationTest
class RecallApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
	}

	@Test
	void dueAreLongAgoAndNothingPlanned() {
		var due = patient("Давно был");
		visit(due, -250, "DONE");
		var booked = patient("Давно был, но записан");
		visit(booked, -250, "DONE");
		visit(booked, 10, "SCHEDULED");
		var recent = patient("Был недавно");
		visit(recent, -60, "DONE");
		var never = patient("Только неявка");
		visit(never, -300, "NO_SHOW");
		var both = patient("Давний и свежий визит");
		visit(both, -400, "DONE");
		visit(both, -30, "DONE");
		var older = patient("Ещё дольше не был");
		visit(older, -365, "DONE");
		visit(older, -300, "NO_SHOW"); // неявка — не визит

		var json = assertThat(recall()).hasStatusOk().bodyJson();
		// Кто дольше не был — первым
		json.extractingPath("$[*].patient.fullName").asArray().containsExactly("Ещё дольше не был", "Давно был");
		json.extractingPath("$[0].invitedAt").isNull();
	}

	@Test
	void invitedGoToTheEndWithDate() {
		var a = patient("Позвали");
		visit(a, -365, "DONE");
		var b = patient("Не звали");
		visit(b, -200, "DONE");

		assertThat(mvc.post().uri("/api/patients/" + a + "/recall-invited")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatus(HttpStatus.NO_CONTENT);

		var json = assertThat(recall()).hasStatusOk().bodyJson();
		json.extractingPath("$[*].patient.fullName").asArray().containsExactly("Не звали", "Позвали");
		json.extractingPath("$[1].invitedAt").isNotNull();
		assertThat(mvc.post().uri("/api/patients/999999/recall-invited")
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult recall() {
		return mvc.get().uri("/api/appointments/recall").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.exchange();
	}

	private long patient(String name) {
		var phone = "99655" + String.format("%07d", Math.abs(name.hashCode()) % 10_000_000);
		return jdbc.queryForObject("insert into patient (full_name, phone) values (?, ?) returning id", Long.class,
				name, phone);
	}

	/** Разные часы у разных визитов: кресло одно, и база не даст двум приёмам совпасть. */
	private int hourShift;

	private void visit(long patient, int days, String status) {
		var start = Instant.now().plus(Duration.ofDays(days)).plus(Duration.ofHours(hourShift++));
		jdbc.update("insert into appointment (patient_id, start_at, end_at, status) values (?, ?, ?, ?)", patient,
				Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)), status);
	}

}

package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import kg.dental.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Отчёт по неделям на настоящем Postgres. «Сейчас» — среда 2099-06-03, 12:00 по Бишкеку. */
@IntegrationTest
class AppointmentStatsQueryTests {

	static final Instant NOW = Instant.parse("2099-06-03T06:00:00Z");

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	@Autowired
	AppointmentStatsQuery query;

	@Autowired
	JdbcTemplate jdbc;

	long patientId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
		patientId = jdbc.queryForObject(
				"insert into patient (full_name, phone) values ('Тест', '996555000000') returning id", Long.class);
	}

	@Test
	void countsByWeekInBishkekTime() {
		// Эта неделя (пн 2099-06-01 — вс 06-07)
		insert("2099-05-31T18:00:00Z", "DONE", false); // пн 00:00 по Бишкеку — уже эта неделя
		insert("2099-06-01T04:00:00Z", "NO_SHOW", true);
		insert("2099-06-01T05:00:00Z", "CANCELLED", false);
		insert("2099-06-03T03:00:00Z", "SCHEDULED", true); // прошёл, не отмечен
		insert("2099-06-04T03:00:00Z", "SCHEDULED", false); // впереди — не «не отмечен»
		// Прошлая неделя
		insert("2099-05-31T17:00:00Z", "DONE", true); // вс 23:00 по Бишкеку
		insert("2099-05-26T04:00:00Z", "NO_SHOW", false);

		var weeks = query.weeks(3, NOW, BISHKEK, null);

		assertThat(weeks).extracting(WeekStats::weekStart)
			.containsExactly(LocalDate.of(2099, 6, 1), LocalDate.of(2099, 5, 25), LocalDate.of(2099, 5, 18));
		assertThat(weeks.get(0)).isEqualTo(new WeekStats(LocalDate.of(2099, 6, 1), 4, 1, 1, 1, 1, 2));
		assertThat(weeks.get(1)).isEqualTo(new WeekStats(LocalDate.of(2099, 5, 25), 2, 1, 1, 0, 0, 1));
		assertThat(weeks.get(2)).isEqualTo(WeekStats.empty(LocalDate.of(2099, 5, 18)));
	}

	private void insert(String start, String status, boolean reminded) {
		var startAt = Instant.parse(start);
		jdbc.update("""
				insert into appointment (doctor_id, patient_id, start_at, end_at, status, reminder_opened_at)
				values (1, ?, ?, ?, ?, ?)""", patientId, Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)),
				status, reminded ? Timestamp.from(startAt.minusSeconds(86400)) : null);
	}

}

package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import kg.dental.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ограничения таблицы appointment из V1__init.sql — последняя линия защиты от двойной
 * записи. Проверяем прямым SQL, без сервисов: так видно, что держит именно база.
 * Каждый тест в своей транзакции, которая откатывается в конце.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AppointmentScheduleConstraintTests {

	private static final String EXCLUSION_VIOLATION = "23P01";
	private static final String CHECK_VIOLATION = "23514";

	@Autowired
	JdbcTemplate jdbc;

	long patientId;

	@BeforeEach
	void createPatient() {
		patientId = jdbc.queryForObject(
				"insert into patient (full_name, phone) values ('Тестовый пациент', '996555000000') returning id",
				Long.class);
	}

	@Test
	void overlappingActiveAppointmentIsRejected() {
		insert("10:00", "10:30", "SCHEDULED");

		assertThatThrownBy(() -> insert("10:15", "10:45", "SCHEDULED"))
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(EXCLUSION_VIOLATION));
	}

	@Test
	void doneAppointmentStillOccupiesTime() {
		insert("10:00", "10:30", "DONE");

		assertThatThrownBy(() -> insert("10:00", "10:30", "SCHEDULED"))
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(EXCLUSION_VIOLATION));
	}

	@Test
	void backToBackAppointmentsAreAllowed() {
		insert("10:00", "10:30", "SCHEDULED");
		insert("10:30", "11:00", "SCHEDULED");
		insert("09:30", "10:00", "SCHEDULED");

		assertThat(count()).isEqualTo(3);
	}

	@Test
	void cancelledAndNoShowDoNotOccupyTime() {
		insert("10:00", "10:30", "CANCELLED");
		insert("10:00", "10:30", "NO_SHOW");
		insert("10:00", "10:30", "SCHEDULED");

		assertThat(count()).isEqualTo(3);
	}

	@Test
	void restoringCancelledAppointmentOntoTakenTimeIsRejected() {
		long cancelled = insert("10:00", "10:30", "CANCELLED");
		insert("10:00", "10:30", "SCHEDULED");

		assertThatThrownBy(() -> jdbc.update("update appointment set status = 'SCHEDULED' where id = ?", cancelled))
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(EXCLUSION_VIOLATION));
	}

	// Одна ошибка на тест: после неё Postgres отклоняет любой запрос в той же транзакции (25P02)
	@Test
	void zeroDurationIsRejected() {
		assertThatThrownBy(() -> insert("10:00", "10:00", "SCHEDULED"))
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
	}

	@Test
	void negativeDurationIsRejected() {
		assertThatThrownBy(() -> insert("10:30", "10:00", "SCHEDULED"))
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
	}

	@Test
	void malformedPhoneIsRejected() {
		assertThatThrownBy(() -> jdbc.update("insert into patient (full_name, phone) values ('Имя', '+996 555')"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
	}

	/** Приём на 1 июня 2027 (UTC), время "HH:mm". */
	private long insert(String start, String end, String status) {
		return jdbc.queryForObject("""
				insert into appointment (patient_id, start_at, end_at, status)
				values (?, ?, ?, ?) returning id""", Long.class,
				patientId, at(start), at(end), status);
	}

	private static Timestamp at(String time) {
		return Timestamp.from(Instant.parse("2027-06-01T" + time + ":00Z"));
	}

	private int count() {
		return jdbc.queryForObject("select count(*) from appointment", Integer.class);
	}

	/**
	 * Код ошибки Postgres. Spring по-разному оборачивает 23P01 (в 7.x — не всегда
	 * DataIntegrityViolationException), поэтому проверяем сам SQLState, а не класс.
	 */
	private static String sqlState(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}

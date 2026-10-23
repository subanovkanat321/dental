package kg.dental.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Вечернее автонапоминание в бот: только записавшимся через бота, один раз, без ответивших. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class AutoRemindersTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	/** Понедельник 2099-06-01, 18:00 по Бишкеку; завтра — вторник 2 июня. */
	static final Instant EVENING = LocalDate.of(2099, 6, 1).atTime(18, 0).atZone(BISHKEK).toInstant();

	@Autowired
	AutoReminders job;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long botPatient;

	long otherPatient;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		botPatient = patient("Бакыт", "996700112233", TestInitData.STRANGER_ID);
		otherPatient = patient("Айгуль", "996555000001", null);
		clearInvocations(telegram);
	}

	@Test
	void remindsOnlyBotPatientsTomorrowAndMarksThem() {
		var bot = appointment(botPatient, LocalTime.of(10, 0), "SCHEDULED", "NONE", false);
		appointment(otherPatient, LocalTime.of(11, 0), "SCHEDULED", "NONE", false);

		assertThat(job.send(EVENING)).isEqualTo(1);

		verify(telegram).sendMessage(eq(TestInitData.STRANGER_ID), anyString(), anyList());
		verify(telegram, times(1)).sendMessage(anyLong(), anyString(), anyList());
		assertThat(jdbc.queryForObject("select reminder_opened_at from appointment where id = ?", Instant.class, bot))
			.isNotNull();
	}

	@Test
	void secondRunDoesNotRepeat() {
		appointment(botPatient, LocalTime.of(10, 0), "SCHEDULED", "NONE", false);
		job.send(EVENING);

		assertThat(job.send(EVENING)).isZero();
		verify(telegram, times(1)).sendMessage(anyLong(), anyString(), anyList());
	}

	@Test
	void skipsRemindedAnsweredRequestedAndOtherDays() {
		appointment(botPatient, LocalTime.of(9, 0), "SCHEDULED", "NONE", true);
		appointment(botPatient, LocalTime.of(10, 0), "SCHEDULED", "CONFIRMED", false);
		appointment(botPatient, LocalTime.of(11, 0), "REQUESTED", "NONE", false);
		insert(botPatient, LocalDate.of(2099, 6, 3).atTime(10, 0), "SCHEDULED", "NONE", false);

		assertThat(job.send(EVENING)).isZero();
		verify(telegram, never()).sendMessage(anyLong(), anyString(), anyList());
	}

	@Test
	void notDeliveredDoesNotStopOthers() {
		var second = patient("Нурлан", "996700000002", 555L);
		appointment(botPatient, LocalTime.of(10, 0), "SCHEDULED", "NONE", false);
		appointment(second, LocalTime.of(11, 0), "SCHEDULED", "NONE", false);
		doThrow(TelegramException.class).when(telegram).sendMessage(eq(TestInitData.STRANGER_ID), anyString(), anyList());

		assertThat(job.send(EVENING)).isEqualTo(1);
		verify(telegram).sendMessage(eq(555L), anyString(), anyList());
	}

	private long patient(String name, String phone, Long telegramId) {
		return jdbc.queryForObject("insert into patient (full_name, phone, telegram_id) values (?, ?, ?) returning id",
				Long.class, name, phone, telegramId);
	}

	private long appointment(long patientId, LocalTime time, String status, String reply, boolean reminded) {
		return insert(patientId, LocalDate.of(2099, 6, 2).atTime(time), status, reply, reminded);
	}

	private long insert(long patientId, java.time.LocalDateTime at, String status, String reply, boolean reminded) {
		var start = at.atZone(BISHKEK).toInstant();
		return jdbc.queryForObject("""
				insert into appointment (doctor_id, patient_id, start_at, end_at, status, reply, reminder_opened_at)
				values (1, ?, ?, ?, ?, ?, ?) returning id""", Long.class, patientId, Timestamp.from(start),
				Timestamp.from(start.plusSeconds(1800)), status, reply, reminded ? Timestamp.from(EVENING) : null);
	}

}

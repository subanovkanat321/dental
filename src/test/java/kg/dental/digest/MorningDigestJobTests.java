package kg.dental.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;

import kg.dental.AssetVersion;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Сводка на настоящей базе; TelegramClient подменён. «Сейчас» — понедельник 2099-06-01,
 * 09:00 по Бишкеку (03:00 UTC); «завтра» — вторник 2099-06-02.
 */
@IntegrationTest
class MorningDigestJobTests {

	static final Instant MONDAY_9AM = Instant.parse("2099-06-01T03:00:00Z");

	static final String NAME = "Айгуль Токтогулова";

	static final String PHONE = "996555123456";

	@Autowired
	MorningDigestJob job;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	@Autowired
	AssetVersion version;

	long patientId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate appointment, patient restart identity cascade");
		patientId = jdbc.queryForObject("insert into patient (full_name, phone) values (?, ?) returning id", Long.class,
				NAME, PHONE);
	}

	@Test
	void countsTomorrowAndUnmarkedWeekWithoutPersonalData() {
		insert("2099-06-02T04:00:00Z", "SCHEDULED"); // завтра 10:00
		insert("2099-06-02T03:30:00Z", "SCHEDULED"); // завтра 09:30 — первый
		insert("2099-06-02T03:00:00Z", "CANCELLED"); // отменён — не считается
		insert("2099-05-30T05:00:00Z", "SCHEDULED"); // суббота, не отмечен — в понедельник виден
		insert("2099-05-31T05:00:00Z", "DONE"); // отмечен
		insert("2099-05-20T05:00:00Z", "SCHEDULED"); // больше недели назад

		var text = sendAndCapture();

		assertThat(text).isEqualTo("Завтра, вт, 2 июн: 2 приёма, первый в 09:30.\n"
				+ "Не отмечено за неделю: 1 — отметьте «Пришёл» или «Не пришёл».");
		// Главное правило сводки: Telegram — сторонний сервис, данных пациентов в нём нет
		assertThat(text).doesNotContain("Айгуль", "Токтогулова", PHONE, "555123456", "123456");
	}

	@Test
	void emptyTomorrowIsReportedAndButtonOpensTomorrow() {
		var text = sendAndCapture();

		assertThat(text).isEqualTo("Завтра приёмов нет.");
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(), eq("Открыть завтра"),
				eq(URI.create("https://dental.test/?day=tomorrow&v=" + version.value())));
	}

	@Test
	void logsCarryJobIdWhileSending() {
		var jobIds = new ArrayList<String>();
		willAnswer(invocation -> jobIds.add(MDC.get("jobId"))).given(telegram)
			.sendMessage(anyLong(), anyString(), anyString(), any());

		job.send(MONDAY_9AM);

		assertThat(jobIds).containsExactly("digest-2099-06-02");
		assertThat(MDC.get("jobId")).as("после задачи номер убран").isNull();
	}

	private String sendAndCapture() {
		job.send(MONDAY_9AM);
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), text.capture(), anyString(), any());
		return text.getValue();
	}

	private void insert(String start, String status) {
		var startAt = Instant.parse(start);
		jdbc.update("insert into appointment (patient_id, start_at, end_at, status) values (?, ?, ?, ?)", patientId,
				Timestamp.from(startAt), Timestamp.from(startAt.plusSeconds(1800)), status);
	}

}

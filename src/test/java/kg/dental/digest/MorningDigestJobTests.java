package kg.dental.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * Уведомления врачу на настоящей базе; TelegramClient подменён. «Сейчас» — понедельник
 * 2099-06-01, 09:00 по Бишкеку (03:00 UTC). Главное правило: ни имени, ни телефона в тексте.
 */
@IntegrationTest
class MorningDigestJobTests {

	static final Instant MONDAY_9AM = Instant.parse("2099-06-01T03:00:00Z");

	static final String NAME = "Айгуль Токтогулова";

	static final String PHONE = "996555123456";

	@Autowired
	MorningDigestJob morning;

	@Autowired
	EveningDigestJob evening;

	@Autowired
	PreNoticeJob preNotice;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AssetVersion version;

	@MockitoBean
	TelegramClient telegram;

	long patientId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
		patientId = jdbc.queryForObject("insert into patient (full_name, phone) values (?, ?) returning id", Long.class,
				NAME, PHONE);
	}

	@Test
	void morningTellsTodayAndTomorrowWithoutPersonalData() {
		insert("2099-06-01T04:00:00Z", "SCHEDULED", "CONFIRMED", false); // сегодня 10:00
		insert("2099-06-01T08:00:00Z", "SCHEDULED", "NONE", true); // сегодня 14:00
		insert("2099-06-02T03:30:00Z", "SCHEDULED", "NONE", false); // завтра 09:30
		insert("2099-06-02T03:00:00Z", "CANCELLED", "NONE", false); // отменён — не считается
		insert("2099-05-30T05:00:00Z", "SCHEDULED", "NONE", false); // суббота, не отмечен

		morning.send(MONDAY_9AM);

		var text = sent();
		assertThat(text).isEqualTo("""
				Сегодня, пн, 1 июн: 2 приёма, 10:00–14:30.
				Подтвердили: 1 · без ответа: 1.
				Завтра, вт, 2 июн: 1 приём, первый в 09:30.
				Не отмечено за неделю: 1 — отметьте «Приём состоялся» или «Неявка».""");
		assertThat(text).doesNotContain("Айгуль", "Токтогулова", PHONE, "555123456", "123456");
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(),
				eq(List.of(button("Открыть сегодня", "doctor=1"), button("Открыть завтра", "day=tomorrow&doctor=1"))));
	}

	@Test
	void eveningWhenSomethingIsLeftAndSilenceWhenDone() {
		insert("2099-06-01T04:00:00Z", "SCHEDULED", "NONE", false); // сегодня, к вечеру не отмечен
		insert("2099-06-02T03:30:00Z", "SCHEDULED", "NONE", false); // завтра, не напомнили
		var evening6pm = Instant.parse("2099-06-01T12:30:00Z"); // 18:30 по Бишкеку

		evening.send(evening6pm);

		assertThat(sent()).isEqualTo("""
				Завтра не напомнили: 1 из 1 — откройте обход.
				Сегодня не отмечено «явка / неявка»: 1.""");

		jdbc.update("update appointment set status = 'DONE' where start_at < '2099-06-02'");
		jdbc.update("update appointment set reminder_opened_at = now()");
		clearInvocations(telegram);
		evening.send(evening6pm);
		verify(telegram, never()).sendMessage(anyLong(), anyString(), anyList());
	}

	@Test
	void preNoticeOnceForAppointmentStartingSoon() {
		var soon = insert("2099-06-01T03:10:00Z", "SCHEDULED", "CONFIRMED", false); // через 10 мин
		insert("2099-06-01T03:45:00Z", "SCHEDULED", "NONE", false); // через 45 мин — позже окна
		insert("2099-06-01T03:05:00Z", "CANCELLED", "NONE", false); // отменён

		preNotice.send(MONDAY_9AM);
		preNotice.send(MONDAY_9AM.plusSeconds(60)); // следующая минута — тот же приём не повторяется

		assertThat(sent()).isEqualTo("Через 10 мин, в 09:10 — приём (30 мин). Ответ пациента: придёт.");
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(),
				eq(List.of(button("Открыть приём", "appointment=" + soon + "&doctor=1"))));
	}

	@Test
	void rescheduledAppointmentIsAnnouncedAgain() {
		var id = insert("2099-06-01T03:10:00Z", "SCHEDULED", "NONE", false);
		preNotice.send(MONDAY_9AM);
		jdbc.update("update appointment set start_at = '2099-06-01T03:40:00Z', end_at = '2099-06-01T04:10:00Z' where id = ?",
				id);
		jdbc.update("update appointment set pre_notified_at = null where id = ?", id); // так делает перенос
		clearInvocations(telegram);

		preNotice.send(MONDAY_9AM.plusSeconds(30 * 60));

		assertThat(sent()).startsWith("Через 10 мин, в 09:40");
	}

	@Test
	void logsCarryJobIdWhileSending() {
		var jobIds = new ArrayList<String>();
		willAnswer(invocation -> jobIds.add(MDC.get("jobId"))).given(telegram)
			.sendMessage(anyLong(), anyString(), anyList());

		morning.send(MONDAY_9AM);

		assertThat(jobIds).containsExactly("morning-2099-06-01");
		assertThat(MDC.get("jobId")).as("после задачи номер убран").isNull();
	}

	private String sent() {
		var text = ArgumentCaptor.forClass(String.class);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), text.capture(), anyList());
		return text.getValue();
	}

	private TelegramClient.Button button(String text, String query) {
		return new TelegramClient.Button(text, version.miniApp(URI.create("https://dental.test"), query));
	}

	private long insert(String start, String status, String reply, boolean reminded) {
		var startAt = Instant.parse(start);
		return jdbc.queryForObject("""
				insert into appointment (doctor_id, patient_id, start_at, end_at, status, reply, reminder_opened_at)
				values (1, ?, ?, ?, ?, ?, ?) returning id""", Long.class, patientId, Timestamp.from(startAt),
				Timestamp.from(startAt.plusSeconds(1800)), status, reply,
				reminded ? Timestamp.from(startAt.minusSeconds(86400)) : null);
	}

}

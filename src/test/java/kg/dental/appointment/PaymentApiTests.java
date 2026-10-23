package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Касса: оплата у состоявшегося приёма, выручка по врачу, отчёт для Excel в бот. 2099-06-01 — понедельник. */
@IntegrationTest
class PaymentApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	long second;

	long patient;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient restart identity cascade");
		second = jdbc.queryForObject("insert into doctor (full_name, telegram_id) values ('Айгерим', 222) returning id", Long.class);
		patient = jdbc.queryForObject("insert into patient (full_name, phone) values ('Бакыт Маматов', '996700112233') returning id",
				Long.class);
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("truncate appointment restart identity cascade");
		jdbc.update("delete from doctor where id <> 1");
	}

	@Test
	void paymentOnlyForDoneAppointmentAndValidated() {
		var done = insert(1, "2099-06-01T04:00:00Z", "DONE");
		var scheduled = insert(1, "2099-06-01T05:00:00Z", "SCHEDULED");

		assertThat(pay(TestInitData.DOCTOR_ID, done, "{\"amountSom\": 3000, \"method\": \"CASH\"}")).hasStatusOk()
			.bodyJson().extractingPath("$.paidSom").isEqualTo(3000);
		assertThat(pay(TestInitData.DOCTOR_ID, scheduled, "{\"amountSom\": 3000, \"method\": \"CASH\"}"))
			.hasStatus(HttpStatus.CONFLICT);
		assertThat(pay(TestInitData.DOCTOR_ID, done, "{\"amountSom\": -1, \"method\": \"CASH\"}"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(pay(TestInitData.DOCTOR_ID, done, "{\"amountSom\": 500}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		// Снять отметку
		assertThat(pay(TestInitData.DOCTOR_ID, done, "{\"amountSom\": null}")).bodyJson().extractingPath("$.paidSom").isNull();
		// Версия приёма не меняется — карточка врача не устаревает
		assertThat(jdbc.queryForObject("select version from appointment where id = ?", Long.class, done)).isZero();
	}

	@Test
	void colleagueCannotTouchPaymentOrSeeRevenue() {
		var done = insert(1, "2099-06-01T04:00:00Z", "DONE");

		assertThat(pay(222, done, "{\"amountSom\": 3000, \"method\": \"CASH\"}")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(get(222, "/api/appointments/revenue?from=2099-06-01&to=2099-06-30&doctorId=1")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(get(222, "/api/appointments/revenue?from=2099-06-01&to=2099-06-30")).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(get(222, "/api/appointments/revenue?from=2099-06-01&to=2099-06-30&doctorId=" + second)).hasStatusOk();
	}

	@Test
	void revenueByDoctorMethodAndDay() {
		pay(TestInitData.DOCTOR_ID, insert(1, "2099-06-01T04:00:00Z", "DONE"), "{\"amountSom\": 3000, \"method\": \"CASH\"}");
		pay(TestInitData.DOCTOR_ID, insert(1, "2099-06-01T05:00:00Z", "DONE"), "{\"amountSom\": 1500, \"method\": \"TRANSFER\"}");
		pay(TestInitData.DOCTOR_ID, insert(1, "2099-06-02T04:00:00Z", "DONE"), "{\"amountSom\": 2000, \"method\": \"CASH\"}");
		insert(1, "2099-06-02T05:00:00Z", "DONE"); // не отмечена оплата
		pay(TestInitData.DOCTOR_ID, insert(second, "2099-06-01T04:00:00Z", "DONE"), "{\"amountSom\": 9000, \"method\": \"CARD\"}");

		var json = assertThat(get(TestInitData.DOCTOR_ID, "/api/appointments/revenue?from=2099-06-01&to=2099-06-30&doctorId=1"))
			.hasStatusOk().bodyJson();
		json.extractingPath("$.totalSom").isEqualTo(6500);
		json.extractingPath("$.paidCount").isEqualTo(3);
		json.extractingPath("$.unpaidCount").isEqualTo(1);
		json.extractingPath("$.byMethod.CASH").isEqualTo(5000);
		json.extractingPath("$.byMethod.TRANSFER").isEqualTo(1500);
		json.extractingPath("$.byMethod.CARD").isEqualTo(0);
		json.extractingPath("$.days[*].totalSom").asArray().containsExactly(4500, 2000);
		assertThat(get(TestInitData.DOCTOR_ID, "/api/appointments/revenue?from=2099-06-01&to=2099-06-30")).bodyJson()
			.extractingPath("$.totalSom").isEqualTo(15500);
	}

	@Test
	void monthReportComesToBotAsCsvWithoutPatientData() {
		pay(TestInitData.DOCTOR_ID, insert(1, "2099-06-01T04:00:00Z", "DONE"), "{\"amountSom\": 3000, \"method\": \"CASH\"}");
		insert(1, "2099-06-02T04:00:00Z", "NO_SHOW");

		assertThat(mvc.post().uri("/api/appointments/report").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content("{\"month\": \"2099-06\", \"doctorId\": 1}"))
			.hasStatus(HttpStatus.NO_CONTENT);

		var bytes = ArgumentCaptor.forClass(byte[].class);
		verify(telegram).sendDocument(eq(TestInitData.DOCTOR_ID), eq("otchet-2099-06-vrach-1.csv"), bytes.capture(), anyString());
		var csv = new String(bytes.getValue(), StandardCharsets.UTF_8);
		assertThat(csv).startsWith("﻿Номер;Дата;Время;Врач;Услуга;Статус;Сумма, сом;Оплата\r\n")
			.contains(";01.06.2099;10:00;Врач;;Состоялся;3000;Наличные\r\n")
			.contains(";02.06.2099;10:00;Врач;;Неявка;;\r\n")
			.doesNotContain("Бакыт", "700112233");
	}

	private long insert(long doctorId, String startAt, String status) {
		var start = Instant.parse(startAt);
		return jdbc.queryForObject("""
				insert into appointment (doctor_id, patient_id, start_at, end_at, status) values (?, ?, ?, ?, ?) returning id""",
				Long.class, doctorId, patient, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)), status);
	}

	private MvcTestResult pay(long userId, long id, String body) {
		return mvc.post().uri("/api/appointments/" + id + "/payment").header(HttpHeaders.AUTHORIZATION, tma(userId))
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private MvcTestResult get(long userId, String uri) {
		return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, tma(userId)).exchange();
	}

	private static String tma(long userId) {
		return "tma " + TestInitData.signed(userId, Instant.now());
	}

}

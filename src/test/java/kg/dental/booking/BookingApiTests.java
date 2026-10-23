package kg.dental.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Самозапись через бота на настоящей базе. Посторонний — Telegram id STRANGER_ID с настоящей
 * подписью. День записи — через 3 дня, 10:00 по Бишкеку: всегда дальше minLead (2 ч).
 */
@IntegrationTest
class BookingApiTests {

	static final ZoneId BISHKEK = ZoneId.of("Asia/Bishkek");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	long serviceId;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade");
		serviceId = jdbc.queryForObject("select id from service_type where name = 'Осмотр и консультация'", Long.class);
	}

	@Test
	void strangerSeesBookingScreenAndDoctorSeesDoctor() {
		var stranger = assertThat(get(stranger(), "/api/patient/me")).hasStatusOk().bodyJson();
		stranger.extractingPath("$.doctor").isEqualTo(false);
		stranger.extractingPath("$.bookingEnabled").isEqualTo(true);
		stranger.extractingPath("$.phone").isNull();
		stranger.extractingPath("$.services[*].name").asArray().contains("Осмотр и консультация");

		assertThat(get(TestInitData.doctor(), "/api/patient/me")).bodyJson().extractingPath("$.doctor").isEqualTo(true);
		// Остальной API посторонним по-прежнему закрыт
		assertThat(get(stranger(), "/api/appointments?doctorId=1&date=2099-06-01")).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void needsSharedPhoneAndConsent() {
		assertThat(book(day(3), "10:00", "Бакыт М.", true)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("phone");

		shareContact();
		assertThat(book(day(3), "10:00", "Бакыт М.", false)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("consent");
	}

	@Test
	void bookingIsARequestThatHoldsTheTimeAndOnlyOnePerPerson() {
		shareContact();

		var booked = book(day(3), "10:00", "  Бакыт   М. ", true);

		assertThat(booked).hasStatus(HttpStatus.CREATED);
		assertThat(booked).bodyJson().extractingPath("$.status").isEqualTo("REQUESTED");
		assertThat(booked).bodyJson().extractingPath("$.serviceName").isEqualTo("Осмотр и консультация");
		var patient = jdbc.queryForMap("select full_name, phone, telegram_id, consent_at from patient");
		assertThat(patient.get("full_name")).isEqualTo("Бакыт М.");
		assertThat(patient.get("phone")).isEqualTo("996700112233");
		assertThat(patient.get("telegram_id")).isEqualTo(TestInitData.STRANGER_ID);
		assertThat(patient.get("consent_at")).isNotNull();
		// Время занято и для врача, и для других пациентов
		assertThat(get(stranger(), "/api/patient/slots?doctorId=1&date=" + day(3) + "&serviceTypeId=" + serviceId)).bodyJson()
			.extractingPath("$.starts").asArray().doesNotContain(instant(day(3), "10:00"));

		assertThat(book(day(4), "10:00", "Бакыт М.", true)).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("already-booked");
		assertThat(get(stranger(), "/api/patient/me")).bodyJson().extractingPath("$.booking.status")
			.isEqualTo("REQUESTED");
	}

	@Test
	void onlyTimesFromTheGrid() {
		shareContact();

		assertThat(book(day(3), "10:07", "Бакыт М.", true)).hasStatus(HttpStatus.CONFLICT); // не на сетке
		assertThat(book(day(3), "20:00", "Бакыт М.", true)).hasStatus(HttpStatus.CONFLICT); // вне рабочих часов
		assertThat(book(day(45), "10:00", "Бакыт М.", true)).hasStatus(HttpStatus.CONFLICT); // дальше 30 дней
		assertThat(get(stranger(), "/api/patient/slots?doctorId=1&date=" + day(45) + "&serviceTypeId=" + serviceId)).bodyJson()
			.extractingPath("$.starts").asArray().isEmpty();
	}

	@Test
	void selfBookingLinksToCardTheDoctorAlreadyMade() {
		jdbc.update("insert into patient (full_name, phone) values ('Бакыт М.', '996700112233')");
		shareContact();

		assertThat(book(day(3), "10:00", "бакыт м.", true)).hasStatus(HttpStatus.CREATED);

		assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select telegram_id from patient", Long.class)).isEqualTo(TestInitData.STRANGER_ID);
	}

	@Test
	void patientCancelsOwnBooking() {
		shareContact();
		book(day(3), "10:00", "Бакыт М.", true);

		assertThat(post(stranger(), "/api/patient/booking/cancel", "")).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(jdbc.queryForObject("select status from appointment", String.class)).isEqualTo("CANCELLED");
		assertThat(get(stranger(), "/api/patient/me")).bodyJson().extractingPath("$.booking").isNull();
		assertThat(post(stranger(), "/api/patient/booking/cancel", "")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void doctorConfirmsOrRejectsRequest() {
		shareContact();
		var id = ((Number) JsonPath.read(body(book(day(3), "10:00", "Бакыт М.", true)), "$.id"))
			.longValue();

		assertThat(post(TestInitData.doctor(), "/api/appointments/" + id + "/status", """
				{"version": 0, "status": "SCHEDULED"}""")).hasStatusOk();
		assertThat(get(stranger(), "/api/patient/me")).bodyJson().extractingPath("$.booking.status")
			.isEqualTo("SCHEDULED");
	}

	private void shareContact() {
		jdbc.update("insert into telegram_contact (telegram_id, phone, first_name) values (?, '996700112233', 'Бакыт')",
				TestInitData.STRANGER_ID);
	}

	private MvcTestResult book(LocalDate date, String time, String name, boolean consent) {
		return post(stranger(), "/api/patient/booking", """
				{"doctorId": 1, "serviceTypeId": %d, "startAt": "%s", "fullName": "%s", "consent": %s}"""
			.formatted(serviceId, instant(date, time), name, consent));
	}

	private static String instant(LocalDate date, String time) {
		return date.atTime(LocalTime.parse(time)).atZone(BISHKEK).toInstant().toString();
	}

	private static LocalDate day(int plus) {
		return LocalDate.now(BISHKEK).plusDays(plus);
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

	private MvcTestResult get(String auth, String uri) {
		return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, auth).exchange();
	}

	private MvcTestResult post(String auth, String uri, String body) {
		var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, auth);
		return (body.isEmpty() ? request : request.contentType(MediaType.APPLICATION_JSON).content(body)).exchange();
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

}

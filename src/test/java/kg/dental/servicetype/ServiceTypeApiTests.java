package kg.dental.servicetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Услуги: врач заводит с ценой «от», пациент видит цену при самозаписи. */
@IntegrationTest
class ServiceTypeApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void cleanUp() {
		jdbc.update("delete from service_type where name = 'Имплантация'");
	}

	@Test
	void priceIsSavedAndShownToPatients() {
		var created = send("POST", "/api/service-types", """
				{"name": "Имплантация", "durationMinutes": 120, "priceSom": 45000}""");
		assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.priceSom").isEqualTo(45000);
		var id = jdbc.queryForObject("select id from service_type where name = 'Имплантация'", Long.class);

		var me = mvc.get().uri("/api/patient/me")
			.header(HttpHeaders.AUTHORIZATION, "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now()));
		assertThat(me).bodyJson().extractingPath("$.services[?(@.name == 'Имплантация')].priceSom").asArray()
			.containsExactly(45000);

		// Без цены — цену не показываем
		assertThat(send("PUT", "/api/service-types/" + id, """
				{"name": "Имплантация", "durationMinutes": 120, "active": true}""")).bodyJson()
			.extractingPath("$.priceSom").isNull();
	}

	@Test
	void negativePriceIs422() {
		assertThat(send("POST", "/api/service-types", """
				{"name": "Имплантация", "durationMinutes": 120, "priceSom": -1}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("priceSom");
	}

	private MvcTestResult send(String method, String uri, String body) {
		var request = "PUT".equals(method) ? mvc.put().uri(uri) : mvc.post().uri(uri);
		return request.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}

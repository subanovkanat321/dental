package kg.dental.patient;

import static org.assertj.core.api.Assertions.assertThat;

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

@IntegrationTest
class PatientApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate appointment, patient restart identity cascade");
	}

	@Test
	void createsPatientWithNormalizedPhoneAndTrimmedName() {
		var result = create("  Айгуль   Токтогулова ", "0555 12 34 56", false);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		var json = assertThat(result).bodyJson();
		json.extractingPath("$.fullName").isEqualTo("Айгуль Токтогулова");
		json.extractingPath("$.phone").isEqualTo("996555123456");
	}

	@Test
	void malformedPhoneIs422UnderPhoneFieldWithoutEchoingTheValue() throws Exception {
		var result = create("Айгуль", "555-12", false);

		assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("phone");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("555-12");
	}

	@Test
	void blankNameIs422() {
		assertThat(create(" ", "0555123456", false)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.errors[0].field")
			.isEqualTo("fullName");
	}

	@Test
	void familyMembersShareOneNumber() {
		assertThat(create("Айгуль Т.", "0555123456", false)).hasStatus(HttpStatus.CREATED);
		assertThat(create("Бакыт Т.", "+996 555 123456", false)).hasStatus(HttpStatus.CREATED);

		assertThat(search("0555 12")).bodyJson()
			.extractingPath("$[*].fullName")
			.asArray()
			.containsExactly("Айгуль Т.", "Бакыт Т.");
	}

	@Test
	void sameNameAndNumberAsksBeforeCreatingDuplicate() {
		create("Айгуль Т.", "0555123456", false);

		var duplicate = create("  айгуль т. ", "996555123456", false);
		assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
		var json = assertThat(duplicate).bodyJson();
		json.extractingPath("$.code").isEqualTo("duplicate-patient");
		json.extractingPath("$.detail").isEqualTo("Такой пациент уже есть");
		json.extractingPath("$.patient.fullName").isEqualTo("Айгуль Т.");

		// «Всё равно создать»
		assertThat(create("айгуль т.", "996555123456", true)).hasStatus(HttpStatus.CREATED);
		assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isEqualTo(2);
	}

	@Test
	void searchesByPartOfNameIgnoringCase() {
		create("Айгуль Токтогулова", "0555123456", false);
		create("Бакыт Маматов", "0700123456", false);

		assertThat(search("токто")).bodyJson().extractingPath("$[*].fullName").asArray()
			.containsExactly("Айгуль Токтогулова");
		assertThat(search("+996 700")).bodyJson().extractingPath("$[*].fullName").asArray()
			.containsExactly("Бакыт Маматов");
		assertThat(search("  ")).bodyJson().extractingPath("$").asArray().isEmpty();
	}

	@Test
	void likeWildcardsInSearchAreLiteral() {
		create("Айгуль", "0555123456", false);

		assertThat(search("%")).bodyJson().extractingPath("$").asArray().isEmpty();
		assertThat(search("_")).bodyJson().extractingPath("$").asArray().isEmpty();
	}

	private MvcTestResult create(String fullName, String phone, boolean allowDuplicate) {
		return mvc.post().uri("/api/patients").contentType(MediaType.APPLICATION_JSON).content("""
				{"fullName": "%s", "phone": "%s", "allowDuplicate": %s}""".formatted(fullName, phone, allowDuplicate))
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private MvcTestResult search(String query) {
		return mvc.post().uri("/api/patients/search").contentType(MediaType.APPLICATION_JSON).content("""
				{"query": "%s"}""".formatted(query)).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

}

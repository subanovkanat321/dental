package kg.dental.patient;

import static org.assertj.core.api.Assertions.assertThat;

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

	@Test
	void editsNameAndPhoneWithSameRules() throws Exception {
		var id = idOf(create("Айгуль", "0555123456", false));

		var edited = update(id, "  Айгуль   Токтогулова ", "+996 700 11 22 33", false);
		assertThat(edited).hasStatusOk();
		assertThat(edited).bodyJson().extractingPath("$.fullName").isEqualTo("Айгуль Токтогулова");
		assertThat(edited).bodyJson().extractingPath("$.phone").isEqualTo("996700112233");

		assertThat(update(id, "Айгуль", "12", false)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("phone");
		// Сохранить себя без изменений — не дубль самого себя
		assertThat(update(id, "Айгуль Токтогулова", "996700112233", false)).hasStatusOk();
	}

	@Test
	void editIntoAnotherPatientsNameAndPhoneAsksFirst() throws Exception {
		create("Бакыт М.", "0700112233", false);
		var id = idOf(create("Бакыт", "0700112233", false)); // семья: тот же номер, другое имя

		assertThat(update(id, "бакыт м.", "0700112233", false)).hasStatus(HttpStatus.CONFLICT)
			.bodyJson().extractingPath("$.code").isEqualTo("duplicate-patient");
		assertThat(update(id, "бакыт м.", "0700112233", true)).hasStatusOk();
	}

	@Test
	void editUnknownPatientIs404() {
		assertThat(update(999999, "Кто-то", "0555123456", false)).hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult update(long id, String fullName, String phone, boolean allowDuplicate) {
		return mvc.put().uri("/api/patients/" + id).contentType(MediaType.APPLICATION_JSON).content("""
				{"fullName": "%s", "phone": "%s", "allowDuplicate": %s}""".formatted(fullName, phone, allowDuplicate))
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private static long idOf(MvcTestResult result) throws Exception {
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
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

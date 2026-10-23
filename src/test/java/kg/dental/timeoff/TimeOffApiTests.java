package kg.dental.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Перерывы и выходные. 2099-06-05 — пятница: по умолчанию (V7) 13:00–15:00 джума-намаз =
 * 07:00–09:00 UTC. После теста — только перерыв из V7, как в базе после миграции.
 */
@IntegrationTest
class TimeOffApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void restoreDefault() {
		jdbc.update("delete from time_off where title <> 'Джума-намаз'");
	}

	@Test
	void fridayHasJumaBreakOutOfFreeTimeAndGrid() {
		var json = assertThat(slots("2099-06-05", 30)).hasStatusOk().bodyJson();

		json.extractingPath("$.breaks[0].title").isEqualTo("Джума-намаз");
		json.extractingPath("$.breaks[0].emoji").isEqualTo("🕌");
		json.extractingPath("$.breaks[0].start").isEqualTo("2099-06-05T07:00:00Z");
		json.extractingPath("$.breaks[0].end").isEqualTo("2099-06-05T09:00:00Z");
		json.extractingPath("$.free[*].start").asArray().containsExactly("2099-06-05T03:00:00Z", "2099-06-05T09:00:00Z");
		json.extractingPath("$.starts").asArray().doesNotContain("2099-06-05T07:00:00Z", "2099-06-05T08:30:00Z")
			.contains("2099-06-05T06:30:00Z", "2099-06-05T09:00:00Z");
	}

	@Test
	void mondayHasNoBreak() {
		assertThat(slots("2099-06-01", 30)).bodyJson().extractingPath("$.breaks").asArray().isEmpty();
	}

	@Test
	void vacationClosesWholeDaysWithTitle() {
		assertThat(create("""
				{"firstDay": "2099-06-01", "lastDay": "2099-06-03", "title": "Отпуск", "emoji": "🏖️"}"""))
			.hasStatus(HttpStatus.CREATED);

		var json = assertThat(slots("2099-06-02", 30)).bodyJson();
		json.extractingPath("$.workingDay").isEqualTo(false);
		json.extractingPath("$.dayOff.title").isEqualTo("Отпуск");
		json.extractingPath("$.starts").asArray().isEmpty();
		assertThat(slots("2099-06-04", 30)).bodyJson().extractingPath("$.workingDay").isEqualTo(true);
	}

	@Test
	void breakIsClippedToWorkHours() {
		// Работа с 09:00: перерыв 08:00–10:00 закрывает только 09:00–10:00
		create("""
				{"firstDay": "2099-06-01", "startTime": "08:00", "endTime": "10:00", "title": "Учёба"}""");

		var json = assertThat(slots("2099-06-01", 30)).bodyJson();
		json.extractingPath("$.breaks[0].start").isEqualTo("2099-06-01T03:00:00Z");
		json.extractingPath("$.breaks[0].end").isEqualTo("2099-06-01T04:00:00Z");
		json.extractingPath("$.breaks[0].emoji").isEqualTo("⏸️");
		json.extractingPath("$.free[0].start").isEqualTo("2099-06-01T04:00:00Z");
	}

	@Test
	void invalidInputIs422WithField() {
		assertThat(create("{\"title\": \"Обед\"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("firstDay");
		assertThat(create("""
				{"weekday": "MONDAY", "startTime": "14:00", "endTime": "13:00", "title": "Обед"}"""))
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("endTime");
		assertThat(create("""
				{"firstDay": "2099-06-03", "lastDay": "2099-06-01", "title": "Отпуск"}"""))
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("lastDay");
		assertThat(create("""
				{"weekday": "MONDAY", "title": " "}""")).bodyJson().extractingPath("$.errors[0].field").isEqualTo("title");
	}

	@Test
	void listAndDelete() {
		create("""
				{"weekday": "WEDNESDAY", "startTime": "12:00", "endTime": "13:00", "title": "Обед", "emoji": "🍽️"}""");
		var list = assertThat(mvc.get().uri("/api/time-off").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatusOk().bodyJson();
		// По дню недели: среда раньше пятницы
		list.extractingPath("$[*].title").asArray().containsExactly("Обед", "Джума-намаз");
		var id = jdbc.queryForObject("select id from time_off where title = 'Обед'", Long.class);

		assertThat(mvc.delete().uri("/api/time-off/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.delete().uri("/api/time-off/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult slots(String date, int duration) {
		return mvc.get().uri("/api/slots?date=" + date + "&duration=" + duration)
			.header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()).exchange();
	}

	private MvcTestResult create(String body) {
		return mvc.post().uri("/api/time-off").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}

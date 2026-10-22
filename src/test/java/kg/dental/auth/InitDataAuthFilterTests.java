package kg.dental.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class InitDataAuthFilterTests {

	static final String DAY = "/api/appointments?date=2099-06-01";

	@Autowired
	MockMvcTester mvc;

	@Test
	void doctorWithValidInitDataGetsData() {
		assertThat(mvc.get().uri(DAY).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())).hasStatusOk();
	}

	@Test
	void noHeaderIs401WithReopenHint() {
		assertThat(mvc.get().uri(DAY)).hasStatus(HttpStatus.UNAUTHORIZED)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("Сессия устарела — откройте заново из бота");
	}

	@Test
	void expiredOrForgedInitDataIs401() {
		var expired = "tma " + TestInitData.signed(TestInitData.DOCTOR_ID, Instant.now().minus(Duration.ofHours(25)));
		assertThat(mvc.get().uri(DAY).header(HttpHeaders.AUTHORIZATION, expired)).hasStatus(HttpStatus.UNAUTHORIZED);

		var forged = TestInitData.doctor().replace("hash=", "hash=0");
		assertThat(mvc.get().uri(DAY).header(HttpHeaders.AUTHORIZATION, forged)).hasStatus(HttpStatus.UNAUTHORIZED);

		var wrongScheme = TestInitData.doctor().replace("tma ", "Bearer ");
		assertThat(mvc.get().uri(DAY).header(HttpHeaders.AUTHORIZATION, wrongScheme))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void strangerWithValidSignatureIs403WithClinicContactAndNoData() {
		var stranger = "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());

		assertThat(mvc.get().uri(DAY).header(HttpHeaders.AUTHORIZATION, stranger)).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("Это служебный бот записи клиники. Записаться на приём: +996 555 000 000");
	}

	@Test
	void devInitDataExistsOnlyInLocalProfile() {
		// Тесты идут без профиля local — как сервер: выдачи входа нет
		assertThat(mvc.get().uri("/dev/init-data")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void healthAndWebhookAreNotBehindInitData() {
		assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
		// У webhook своя защита — секрет в заголовке (TelegramWebhookTests)
		assertThat(mvc.post().uri("/telegram/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

}

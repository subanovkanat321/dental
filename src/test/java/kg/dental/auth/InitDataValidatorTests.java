package kg.dental.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * FIXTURE подписан не нашим Java-кодом, а отдельной реализацией на Python (hmac/hashlib)
 * по документации Telegram: если data_check_string собирается неверно, тест это поймает,
 * а не повторит ту же ошибку. Поле signature (Bot API 8.0) в строке есть.
 * TODO шаг 4: добавить строку, снятую с настоящего Telegram.WebApp.initData.
 */
class InitDataValidatorTests {

	static final String TOKEN = "123456:TEST-token-for-fixture";

	static final String FIXTURE = "query_id=AAHdF6IQAAAAAN0XohDhrOrc"
			+ "&user=%7B%22id%22%3A111%2C%22first_name%22%3A%22%D0%92%D1%80%D0%B0%D1%87%22%2C%22language_code%22%3A%22ru%22%7D"
			+ "&auth_date=1767225600&signature=abcDEF123_-xyz"
			+ "&hash=47a2b62a89ffc1c61cb7dc43ef3bf89e4c8a4f4a1422faa6c27dbfe005213078";

	/** auth_date фикстуры: 2026-01-01T00:00:00Z. */
	static final Instant SIGNED_AT = Instant.ofEpochSecond(1767225600);

	final InitDataValidator validator = new InitDataValidator(TOKEN, Duration.ofHours(24), JsonMapper.builder().build());

	@Test
	void acceptsStringSignedByTelegramAlgorithm() {
		assertThat(validator.validate(FIXTURE, SIGNED_AT.plusSeconds(3600))).hasValue(111);
	}

	@Test
	void fieldOrderDoesNotMatter() {
		var reordered = "auth_date=1767225600&" + FIXTURE.replace("&auth_date=1767225600", "");
		assertThat(validator.validate(reordered, SIGNED_AT)).hasValue(111);
	}

	@Test
	void rejectsTamperedUser() {
		var otherUser = FIXTURE.replace("%22id%22%3A111", "%22id%22%3A112");
		assertThat(validator.validate(otherUser, SIGNED_AT)).isEmpty();
	}

	@Test
	void rejectsTamperedHash() {
		var tampered = FIXTURE.substring(0, FIXTURE.length() - 1) + "9";
		assertThat(validator.validate(tampered, SIGNED_AT)).isEmpty();
	}

	@Test
	void rejectsOtherBotToken() {
		var otherBot = new InitDataValidator("654321:other", Duration.ofHours(24), JsonMapper.builder().build());
		assertThat(otherBot.validate(FIXTURE, SIGNED_AT)).isEmpty();
	}

	@Test
	void rejectsOlderThan24Hours() {
		assertThat(validator.validate(FIXTURE, SIGNED_AT.plus(Duration.ofHours(24)))).hasValue(111);
		assertThat(validator.validate(FIXTURE, SIGNED_AT.plus(Duration.ofHours(24)).plusSeconds(1))).isEmpty();
	}

	@Test
	void rejectsSignedInTheFuture() {
		assertThat(validator.validate(FIXTURE, SIGNED_AT.minusSeconds(120))).isEmpty();
	}

	@Test
	void rejectsMissingHashDuplicatesAndGarbage() {
		assertThat(validator.validate(FIXTURE.replaceAll("&hash=[0-9a-f]+", ""), SIGNED_AT)).isEmpty();
		assertThat(validator.validate(FIXTURE + "&auth_date=1767225600", SIGNED_AT)).isEmpty();
		assertThat(validator.validate("", SIGNED_AT)).isEmpty();
		assertThat(validator.validate(null, SIGNED_AT)).isEmpty();
		assertThat(validator.validate("garbage", SIGNED_AT)).isEmpty();
	}

}

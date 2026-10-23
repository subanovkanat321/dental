package kg.dental.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.net.URI;
import java.util.List;

import kg.dental.AssetVersion;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Webhook и ответы бота. TelegramClient подменён: в настоящий Telegram тесты не ходят. */
@IntegrationTest
class TelegramWebhookTests {

	static final String SECRET = "test-webhook-secret";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	TelegramClient telegram;

	@Autowired
	AssetVersion version;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void wrongOrMissingSecretIs401AndNothingHappens() {
		assertThat(webhook("wrong", start(TestInitData.DOCTOR_ID))).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post().uri("/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
			.content(start(TestInitData.DOCTOR_ID))).hasStatus(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(telegram);
	}

	@Test
	void doctorStartGetsWelcomeAndMiniAppButton() {
		assertThat(webhook(SECRET, start(TestInitData.DOCTOR_ID))).hasStatusOk();

		verify(telegram).sendMessage(TestInitData.DOCTOR_ID, BotTexts.WELCOME);
		// Адрес с версией: после обновления сервера кнопка ведёт на свежие файлы (AssetVersion)
		verify(telegram).setChatMenuButton(TestInitData.DOCTOR_ID, "Открыть день",
				URI.create("https://dental.test/?v=" + version.value()));
	}

	@Test
	void strangerStartGetsBookingButtonAndMenu() {
		assertThat(webhook(SECRET, start(TestInitData.STRANGER_ID))).hasStatusOk();

		var url = URI.create("https://dental.test/?v=" + version.value());
		verify(telegram).sendMessage(TestInitData.STRANGER_ID, "Здравствуйте! Это бот записи к стоматологу. "
				+ "Нажмите «Записаться на приём» — выберите услугу и свободное время. Телефон клиники: +996 555 000 000",
				List.of(new TelegramClient.Button("Записаться на приём", url)));
		verify(telegram).setChatMenuButton(TestInitData.STRANGER_ID, "Записаться", url);
		verifyNoMoreInteractions(telegram);
	}

	@Test
	void ownContactIsSavedForBookingForeignIsNot() {
		assertThat(webhook(SECRET, contact(TestInitData.STRANGER_ID, TestInitData.STRANGER_ID, "996700112233")))
			.hasStatusOk();
		verify(telegram).sendMessage(TestInitData.STRANGER_ID,
				"Спасибо, номер получен. Вернитесь в приложение и завершите запись.");
		assertThat(jdbc.queryForObject("select phone from telegram_contact where telegram_id = ?", String.class,
				TestInitData.STRANGER_ID)).isEqualTo("996700112233");

		// Чужой контакт из записной книжки — user_id другой: не сохраняем
		assertThat(webhook(SECRET, contact(TestInitData.STRANGER_ID + 1, 42, "996555000111"))).hasStatusOk();
		assertThat(jdbc.queryForObject("select count(*) from telegram_contact where telegram_id = ?", Integer.class,
				TestInitData.STRANGER_ID + 1)).isZero();
	}

	@Test
	void strangerOtherMessagesAreIgnored() {
		assertThat(webhook(SECRET, message(TestInitData.STRANGER_ID, "private", "покажи расписание"))).hasStatusOk();
		verifyNoInteractions(telegram);
	}

	@Test
	void groupChatsAreIgnoredEvenForDoctor() {
		assertThat(webhook(SECRET, message(TestInitData.DOCTOR_ID, "group", "/start"))).hasStatusOk();
		verifyNoInteractions(telegram);
	}

	@Test
	void failureWhileHandlingStillAnswers200() {
		willThrow(new TelegramException("sendMessage: HTTP 403 Forbidden: bot was blocked by the user"))
			.given(telegram).sendMessage(anyLong(), anyString());

		assertThat(webhook(SECRET, start(TestInitData.DOCTOR_ID))).hasStatusOk();
	}

	@Test
	void malformedBodyWithSecretIs200SoTelegramDoesNotRetryForever() {
		assertThat(webhook(SECRET, "not json")).hasStatusOk();
		verifyNoInteractions(telegram);
	}

	@Test
	void updatesWithoutMessageAreAccepted() {
		assertThat(webhook(SECRET, "{\"update_id\": 7, \"edited_message\": {}}")).hasStatusOk();
		verifyNoInteractions(telegram);
	}

	private MvcTestResult webhook(String secret, String body) {
		return mvc.post().uri("/telegram/webhook").header(TelegramWebhookController.SECRET_HEADER, secret)
			.contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static String contact(long fromId, long contactUserId, String phone) {
		return """
				{"update_id": 2, "message": {"message_id": 6, "date": 1767225600,
				 "from": {"id": %d, "is_bot": false, "first_name": "Test"},
				 "chat": {"id": %d, "type": "private"},
				 "contact": {"phone_number": "%s", "first_name": "Test", "user_id": %d}}}"""
			.formatted(fromId, fromId, phone, contactUserId);
	}

	private static String start(long userId) {
		return message(userId, "private", "/start");
	}

	private static String message(long userId, String chatType, String text) {
		return """
				{"update_id": 1, "message": {"message_id": 5, "date": 1767225600, "text": "%s",
				 "from": {"id": %d, "is_bot": false, "first_name": "Test"},
				 "chat": {"id": %d, "type": "%s"}}}""".formatted(text, userId, userId, chatType);
	}

}

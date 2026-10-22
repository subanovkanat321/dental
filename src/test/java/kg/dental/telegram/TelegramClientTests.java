package kg.dental.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Запросы клиента к Bot API — подставной сервер вместо api.telegram.org. */
class TelegramClientTests {

	static final String TOKEN = "123456:SECRET-token";

	static final String BASE = "https://api.telegram.org/bot" + TOKEN;

	MockRestServiceServer server;

	TelegramClient client;

	@BeforeEach
	void setUp() {
		var builder = RestClient.builder().baseUrl("https://api.telegram.org");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TelegramClient(builder.build(), TOKEN);
	}

	@Test
	void sendMessagePostsChatIdAndText() {
		server.expect(requestTo(BASE + "/sendMessage"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("{\"chat_id\": 111, \"text\": \"Привет\"}", JsonCompareMode.STRICT))
			.andRespond(withSuccess("{\"ok\": true, \"result\": {\"message_id\": 1}}", MediaType.APPLICATION_JSON));

		client.sendMessage(111, "Привет");

		server.verify();
	}

	@Test
	void setWebhookSendsSecretAndOnlyMessageUpdates() {
		server.expect(requestTo(BASE + "/setWebhook"))
			.andExpect(content().json("""
					{"url": "https://dental.test/telegram/webhook", "secret_token": "s3cret",
					 "allowed_updates": ["message"], "drop_pending_updates": false}"""))
			.andRespond(withSuccess("{\"ok\": true, \"result\": true}", MediaType.APPLICATION_JSON));

		client.setWebhook(URI.create("https://dental.test/telegram/webhook"), "s3cret");

		server.verify();
	}

	@Test
	void setChatMenuButtonOpensMiniApp() {
		server.expect(requestTo(BASE + "/setChatMenuButton"))
			.andExpect(content().json("""
					{"chat_id": 111, "menu_button": {"type": "web_app", "text": "Открыть день",
					 "web_app": {"url": "https://dental.test"}}}"""))
			.andRespond(withSuccess("{\"ok\": true, \"result\": true}", MediaType.APPLICATION_JSON));

		client.setChatMenuButton(111, "Открыть день", URI.create("https://dental.test"));

		server.verify();
	}

	@Test
	void getUpdatesParsesMessagesAndIgnoresUnknownFields() {
		server.expect(requestTo(BASE + "/getUpdates"))
			.andExpect(content().json("{\"offset\": 5, \"timeout\": 25, \"allowed_updates\": [\"message\"]}"))
			.andRespond(withSuccess("""
					{"ok": true, "result": [{"update_id": 5, "new_field": 1, "message": {"message_id": 9,
					 "text": "/start", "from": {"id": 111, "is_bot": false}, "chat": {"id": 111, "type": "private"}}}]}""",
					MediaType.APPLICATION_JSON));

		var updates = client.getUpdates(5, 25);

		assertThat(updates).hasSize(1);
		assertThat(updates.getFirst().updateId()).isEqualTo(5);
		assertThat(updates.getFirst().message().from().id()).isEqualTo(111);
		assertThat(updates.getFirst().message().text()).isEqualTo("/start");
	}

	@Test
	void telegramErrorKeepsDescriptionButNotToken() {
		server.expect(requestTo(BASE + "/sendMessage"))
			.andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"ok\": false, \"error_code\": 400, \"description\": \"Bad Request: chat not found\"}"));

		assertThatThrownBy(() -> client.sendMessage(1, "x")).isInstanceOf(TelegramException.class)
			.hasMessage("sendMessage: HTTP 400 Bad Request: chat not found")
			.message()
			.doesNotContain(TOKEN);
	}

	@Test
	void networkErrorDoesNotLeakTokenFromUrl() {
		server.expect(requestTo(BASE + "/getUpdates")).andRespond(withException(new IOException("Connection reset")));

		assertThatThrownBy(() -> client.getUpdates(0, 25)).isInstanceOf(TelegramException.class)
			.message()
			.startsWith("getUpdates: ")
			.doesNotContain(TOKEN);
	}

}

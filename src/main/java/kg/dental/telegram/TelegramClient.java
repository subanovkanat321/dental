package kg.dental.telegram;

import java.net.URI;
import java.util.List;
import java.util.Map;

import kg.dental.telegram.TelegramApi.Response;
import kg.dental.telegram.TelegramApi.Update;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Тонкий клиент Bot API на Spring RestClient (R1/D3): четыре нужных метода и
 * deleteWebhook для локального polling. Библиотека TelegramBots не нужна: это обычные
 * POST-запросы с JSON, а своим кодом проще управлять таймаутами и тем, что попадает в лог.
 */
public class TelegramClient {

	/** Только сообщения: остальные типы обновлений боту не нужны. */
	static final List<String> ALLOWED_UPDATES = List.of("message");

	private static final ParameterizedTypeReference<Response<Boolean>> BOOLEAN = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<Response<List<Update>>> UPDATES = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<Response<Object>> ANY = new ParameterizedTypeReference<>() {
	};

	private final RestClient http;

	private final String token;

	/** @param http клиент с базовым адресом {@code https://api.telegram.org} и таймаутами */
	public TelegramClient(RestClient http, String token) {
		this.http = http;
		this.token = token;
	}

	public void sendMessage(long chatId, String text) {
		call("sendMessage", new TelegramApi.SendMessage(chatId, text), ANY);
	}

	/** Кнопка меню чата открывает Mini App по этому адресу (нужен https). */
	public void setChatMenuButton(long chatId, String text, URI webAppUrl) {
		var button = new TelegramApi.MenuButton("web_app", text, new TelegramApi.WebAppInfo(webAppUrl.toString()));
		call("setChatMenuButton", new TelegramApi.SetChatMenuButton(chatId, button), BOOLEAN);
	}

	public void setWebhook(URI url, String secretToken) {
		call("setWebhook", new TelegramApi.SetWebhook(url.toString(), secretToken, ALLOWED_UPDATES, false), BOOLEAN);
	}

	/** Перед polling: пока у бота есть webhook, getUpdates Telegram не отдаёт. */
	public void deleteWebhook() {
		call("deleteWebhook", Map.of(), BOOLEAN);
	}

	/**
	 * Long polling: Telegram держит запрос до {@code timeoutSeconds}, пока не появятся
	 * обновления. Read-таймаут HTTP-клиента должен быть больше (TelegramConfig).
	 */
	public List<Update> getUpdates(long offset, int timeoutSeconds) {
		var updates = call("getUpdates", new TelegramApi.GetUpdates(offset, timeoutSeconds, ALLOWED_UPDATES), UPDATES);
		return updates == null ? List.of() : updates;
	}

	private <T> T call(String method, Object body, ParameterizedTypeReference<Response<T>> type) {
		Response<T> response;
		try {
			response = http.post()
				// Строкой, а не шаблоном {token}: шаблон закодировал бы «:» токена в %3A
				.uri("/bot" + token + "/" + method)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(type);
		}
		catch (RestClientResponseException e) {
			// Тело ошибки Telegram: {"ok":false,"description":"Bad Request: chat not found"} — без токена
			throw new TelegramException(method + ": HTTP " + e.getStatusCode().value() + " " + describe(e));
		}
		catch (RestClientException e) {
			// Не e.getMessage(): «I/O error on POST request for "https://api.telegram.org/bot<токен>/…"»
			throw new TelegramException(method + ": " + e.getClass().getSimpleName()
					+ (e.getMostSpecificCause() != e ? " / " + e.getMostSpecificCause().getClass().getSimpleName() : ""));
		}
		if (response == null || !response.ok()) {
			throw new TelegramException(method + ": " + (response == null ? "пустой ответ" : response.description()));
		}
		return response.result();
	}

	private static String describe(RestClientResponseException e) {
		try {
			var body = e.getResponseBodyAs(Response.class);
			return body == null || body.description() == null ? "" : body.description();
		}
		catch (RuntimeException notJson) {
			return "";
		}
	}

}

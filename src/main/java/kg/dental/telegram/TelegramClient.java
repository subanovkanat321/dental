package kg.dental.telegram;

import java.net.URI;
import java.util.List;
import java.util.Map;

import kg.dental.telegram.TelegramApi.Response;
import kg.dental.telegram.TelegramApi.Update;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Тонкий клиент Bot API на Spring RestClient (R1/D3): четыре нужных метода и
 * deleteWebhook для локального polling. Библиотека TelegramBots не нужна: это обычные
 * POST-запросы с JSON, а своим кодом проще управлять таймаутами и тем, что попадает в лог.
 */
public class TelegramClient {

	/** Сообщения и нажатия кнопок под сообщениями бота; остальные типы обновлений не нужны. */
	static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query");

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
		call("sendMessage", new TelegramApi.SendMessage(chatId, text, null), ANY);
	}

	/**
	 * Кнопка под сообщением: открывает Mini App по адресу webAppUrl (https) или, если задан
	 * callbackData, присылает боту нажатие с этими данными (ответ пациента на напоминание).
	 */
	public record Button(String text, URI webAppUrl, String callbackData, URI url) {

		public Button(String text, URI webAppUrl) {
			this(text, webAppUrl, null, null);
		}

		public static Button callback(String text, String data) {
			return new Button(text, null, data, null);
		}

		/** Обычная ссылка (2ГИС, t.me поддержки): Telegram откроет её сам. */
		public static Button link(String text, URI url) {
			return new Button(text, null, null, url);
		}

	}

	/** Сообщение с кнопками под ним — каждая на своей строке. */
	public void sendMessage(long chatId, String text, List<Button> buttons) {
		sendKeyboard(chatId, text, buttons.stream().map(List::of).toList());
	}

	/** Сообщение с кнопками рядами: {@code [[⭐1, ⭐2, … ⭐5]]} — пять кнопок в одну строку. */
	public void sendKeyboard(long chatId, String text, List<List<Button>> rows) {
		var keyboard = rows.stream()
			.map(row -> row.stream()
				.map(b -> new TelegramApi.InlineButton(b.text(),
						b.webAppUrl() == null ? null : new TelegramApi.WebAppInfo(b.webAppUrl().toString()),
						b.callbackData(), b.url() == null ? null : b.url().toString()))
				.toList())
			.toList();
		call("sendMessage", new TelegramApi.SendMessage(chatId, text,
				keyboard.isEmpty() ? null : new TelegramApi.InlineKeyboard(keyboard)), ANY);
	}

	/** Файл в чат (отчёт для Excel): multipart, как требует Bot API для загрузки документа. */
	public void sendDocument(long chatId, String fileName, byte[] content, String caption) {
		var form = new LinkedMultiValueMap<String, Object>();
		form.add("chat_id", String.valueOf(chatId));
		form.add("caption", caption);
		form.add("document", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return fileName;
			}
		});
		call("sendDocument", form, MediaType.MULTIPART_FORM_DATA, ANY);
	}

	/** Меню «/» в чате с ботом: что умеет бот. */
	public void setMyCommands(Map<String, String> commands) {
		call("setMyCommands", new TelegramApi.SetMyCommands(commands.entrySet().stream()
			.map(e -> new TelegramApi.BotCommand(e.getKey(), e.getValue())).toList()), BOOLEAN);
	}

	/** Ответ на нажатие кнопки: Telegram убирает «часики» и показывает text всплывающей подсказкой. */
	public void answerCallbackQuery(String callbackQueryId, String text) {
		call("answerCallbackQuery", new TelegramApi.AnswerCallbackQuery(callbackQueryId, text), BOOLEAN);
	}

	/** Убрать кнопки под сообщением: на напоминание отвечают один раз. */
	public void removeButtons(long chatId, long messageId) {
		call("editMessageReplyMarkup",
				new TelegramApi.EditMessageReplyMarkup(chatId, messageId, new TelegramApi.InlineKeyboard(List.of())), ANY);
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
		return call(method, body, MediaType.APPLICATION_JSON, type);
	}

	private <T> T call(String method, Object body, MediaType contentType, ParameterizedTypeReference<Response<T>> type) {
		Response<T> response;
		try {
			response = http.post()
				// Строкой, а не шаблоном {token}: шаблон закодировал бы «:» токена в %3A
				.uri("/bot" + token + "/" + method)
				.contentType(contentType)
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

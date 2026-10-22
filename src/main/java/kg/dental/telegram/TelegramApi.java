package kg.dental.telegram;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Типы Bot API — только те поля, которые нам нужны (https://core.telegram.org/bots/api).
 * Остальные поля Telegram игнорируются: он добавляет новые, и это не должно ломать бота.
 */
public final class TelegramApi {

	private TelegramApi() {
	}

	/** Обёртка любого ответа: {@code {"ok": true, "result": ...}}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Response<T>(boolean ok, T result, String description) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Update(@JsonProperty("update_id") long updateId, Message message) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(@JsonProperty("message_id") long messageId, User from, Chat chat, String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record User(long id) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Chat(long id, String type) {
	}

	record SendMessage(@JsonProperty("chat_id") long chatId, String text) {
	}

	record SetWebhook(String url, @JsonProperty("secret_token") String secretToken,
			@JsonProperty("allowed_updates") List<String> allowedUpdates,
			@JsonProperty("drop_pending_updates") boolean dropPendingUpdates) {
	}

	record GetUpdates(long offset, int timeout, @JsonProperty("allowed_updates") List<String> allowedUpdates) {
	}

	record SetChatMenuButton(@JsonProperty("chat_id") long chatId, @JsonProperty("menu_button") MenuButton menuButton) {
	}

	record MenuButton(String type, String text, @JsonProperty("web_app") WebAppInfo webApp) {
	}

	record WebAppInfo(String url) {
	}

}

package kg.dental.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import kg.dental.telegram.TelegramApi.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Сюда Telegram присылает сообщения боту (режим webhook, сервер).
 * <ul>
 * <li>Чужой или пустой {@code X-Telegram-Bot-Api-Secret-Token} — 401: адрес webhook
 * публичный, и без секрета кто угодно мог бы писать «от имени» Telegram.</li>
 * <li>Иначе всегда 200, даже если обработка упала (R4/D6). На не-200 Telegram повторяет
 * то же обновление снова и снова, и одно «плохое» сообщение остановило бы бота.</li>
 * </ul>
 */
@RestController
class TelegramWebhookController {

	static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

	private final BotUpdateHandler handler;

	private final TelegramProperties properties;

	private final JsonMapper json;

	TelegramWebhookController(BotUpdateHandler handler, TelegramProperties properties, JsonMapper json) {
		this.handler = handler;
		this.properties = properties;
		this.json = json;
	}

	/**
	 * Тело — строкой, а разбор JSON — после проверки секрета. С {@code @RequestBody Update}
	 * Spring разобрал бы JSON раньше, и на кривое тело без секрета ответил бы 400, а не 401.
	 */
	@PostMapping("/telegram/webhook")
	ResponseEntity<Void> receive(@RequestHeader(name = SECRET_HEADER, required = false) String secret,
			@RequestBody(required = false) String body) {
		if (!secretMatches(secret)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Update update;
		try {
			update = json.readValue(body == null ? "" : body, Update.class);
		}
		catch (JacksonException e) {
			log.warn("Webhook: тело не разобрано ({})", e.getClass().getSimpleName());
			return ResponseEntity.ok().build();
		}
		try {
			handler.handle(update);
		}
		catch (RuntimeException e) {
			// Без текста сообщения и данных пользователя: только номер обновления и тип ошибки
			log.warn("Обновление {} не обработано: {}", update.updateId(), e.getClass().getSimpleName()
					+ (e instanceof TelegramException ? " " + e.getMessage() : ""));
		}
		return ResponseEntity.ok().build();
	}

	/** Сравнение за постоянное время: иначе по скорости ответа можно подбирать секрет по символу. */
	private boolean secretMatches(String secret) {
		var expected = properties.webhookSecret();
		return !expected.isEmpty() && secret != null && MessageDigest
			.isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
	}

}

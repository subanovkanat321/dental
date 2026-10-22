package kg.dental.telegram;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки бота {@code dental.telegram.*}. Значения — из переменных окружения
 * (application.yaml), токен и секрет webhook в файлах не хранятся.
 *
 * @param botToken токен от @BotFather; пустой — бот выключен, а API закрыт для всех
 * @param webhookSecret секрет, который Telegram присылает в заголовке каждого webhook
 * @param allowedIds Telegram id врача (и ассистента), которым открыты бот и Mini App
 * @param mode как получать сообщения: off, polling (ноутбук) или webhook (сервер)
 * @param publicUrl https-адрес приложения: из него адрес webhook и кнопка Mini App
 * @param clinicContact как записаться на приём — для посторонних, написавших боту
 * @param initDataMaxAge сколько живёт вход в Mini App
 */
@ConfigurationProperties("dental.telegram")
public record TelegramProperties(String botToken, String webhookSecret, Set<Long> allowedIds, Mode mode,
		URI publicUrl, String clinicContact, Duration initDataMaxAge) {

	public enum Mode {
		OFF, POLLING, WEBHOOK
	}

	public TelegramProperties {
		botToken = botToken == null ? "" : botToken.strip();
		webhookSecret = webhookSecret == null ? "" : webhookSecret.strip();
		allowedIds = allowedIds == null ? Set.of() : Set.copyOf(allowedIds);
		mode = mode == null ? Mode.OFF : mode;
		clinicContact = clinicContact == null ? "" : clinicContact.strip();
		initDataMaxAge = initDataMaxAge == null ? Duration.ofHours(24) : initDataMaxAge;
	}

	public boolean configured() {
		return !botToken.isEmpty();
	}

	public boolean isAllowed(long telegramId) {
		return allowedIds.contains(telegramId);
	}

}

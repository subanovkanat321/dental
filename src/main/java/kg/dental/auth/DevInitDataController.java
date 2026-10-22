package kg.dental.auth;

import java.time.Clock;

import kg.dental.shared.NotFoundException;
import kg.dental.telegram.TelegramProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Только профиль local: подписанная initData первого id из ALLOWED_TELEGRAM_IDS, чтобы
 * Mini App открывалась в браузере на ноутбуке (http://localhost:8080) без Telegram и туннеля.
 * На сервере (профиль prod) этого бина нет: адрес отвечает 404, а не выдаёт вход.
 */
@Profile("local")
@RestController
class DevInitDataController {

	private final TelegramProperties properties;

	private final Clock clock;

	DevInitDataController(TelegramProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	@GetMapping(path = "/dev/init-data", produces = MediaType.TEXT_PLAIN_VALUE)
	String initData() {
		if (!properties.configured() || properties.allowedIds().isEmpty()) {
			throw new NotFoundException("Нужны TELEGRAM_BOT_TOKEN и ALLOWED_TELEGRAM_IDS");
		}
		var userId = properties.allowedIds().stream().sorted().findFirst().orElseThrow();
		return InitDataSigner.sign(properties.botToken(), userId, clock.instant());
	}

}

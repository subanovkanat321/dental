package kg.dental.auth;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
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
 * <p>
 * С туннелем (PUBLIC_URL задан) ноутбук виден из интернета, а туннель приходит к приложению
 * с того же 127.0.0.1 — поэтому вход выдаётся, только когда PUBLIC_URL пуст и у запроса нет
 * заголовков прокси. Иначе любой, кто узнал адрес туннеля, вошёл бы как врач.
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
	String initData(HttpServletRequest request) {
		if (properties.publicUrl() != null || !direct(request)) {
			throw new NotFoundException("Только для браузера на этом компьютере без туннеля");
		}
		if (!properties.configured() || properties.allowedIds().isEmpty()) {
			throw new NotFoundException("Нужны TELEGRAM_BOT_TOKEN и ALLOWED_TELEGRAM_IDS");
		}
		var userId = properties.allowedIds().stream().sorted().findFirst().orElseThrow();
		return InitDataSigner.sign(properties.botToken(), userId, clock.instant());
	}

	/** Запрос с этого компьютера и не через прокси или туннель (они добавляют свои заголовки). */
	private static boolean direct(HttpServletRequest request) {
		var loopback = "127.0.0.1".equals(request.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(request.getRemoteAddr());
		return loopback && request.getHeader("X-Forwarded-For") == null && request.getHeader("Forwarded") == null
				&& request.getHeader("Cf-Connecting-Ip") == null;
	}

}

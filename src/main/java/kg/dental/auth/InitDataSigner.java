package kg.dental.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Подписывает initData так же, как Telegram: зеркало {@link InitDataValidator}.
 * Нужен только на ноутбуке ({@code DevInitDataController}), где Mini App открыта
 * в обычном браузере, а не из Telegram.
 */
final class InitDataSigner {

	private InitDataSigner() {
	}

	static String sign(String botToken, long userId, Instant authDate) {
		var fields = new TreeMap<String, String>(Map.of("auth_date", String.valueOf(authDate.getEpochSecond()),
				"query_id", "local-dev", "user", "{\"id\":" + userId + ",\"first_name\":\"Local\"}"));
		var dataCheckString = fields.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining("\n"));
		var secret = InitDataValidator.hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
				botToken.getBytes(StandardCharsets.UTF_8));
		fields.put("hash", HexFormat.of()
			.formatHex(InitDataValidator.hmac(secret, dataCheckString.getBytes(StandardCharsets.UTF_8))));
		return fields.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

}

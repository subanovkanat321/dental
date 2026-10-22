package kg.dental;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подписывает initData токеном из тестовых настроек — как это делает Telegram. Для тестов
 * API, где важен не вход, а сама логика. Сам алгоритм проверяется в InitDataValidatorTests
 * на строке, подписанной независимой реализацией (Python).
 */
public final class TestInitData {

	public static final String BOT_TOKEN = "123456:TEST-token-for-fixture";

	public static final long DOCTOR_ID = 111;

	public static final long STRANGER_ID = 999;

	private TestInitData() {
	}

	/** Заголовок Authorization врача из ALLOWED_TELEGRAM_IDS. */
	public static String doctor() {
		return "tma " + signed(DOCTOR_ID, Instant.now());
	}

	public static String signed(long userId, Instant authDate) {
		var fields = new TreeMap<String, String>(Map.of("auth_date", String.valueOf(authDate.getEpochSecond()),
				"query_id", "AAHtest", "user", "{\"id\":" + userId + ",\"first_name\":\"Test\"}"));
		var dataCheckString = fields.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining("\n"));
		fields.put("hash", HexFormat.of().formatHex(hmac(hmac("WebAppData".getBytes(), BOT_TOKEN.getBytes()),
				dataCheckString.getBytes(StandardCharsets.UTF_8))));
		return fields.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}

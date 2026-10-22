package kg.dental.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Проверка {@code initData} — строки, которую Telegram передаёт в Mini App
 * ({@code Telegram.WebApp.initData}) и которую Mini App присылает с каждым запросом.
 * По официальной схеме (https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app):
 * <ol>
 * <li>все поля, кроме {@code hash}, сортируются по имени и склеиваются в строки
 * {@code ключ=значение} через {@code \n} (data_check_string);</li>
 * <li>{@code secret = HMAC_SHA256(key = "WebAppData", data = bot_token)};</li>
 * <li>{@code hash} должен совпасть с {@code hex(HMAC_SHA256(key = secret, data = data_check_string))}.</li>
 * </ol>
 * Подделать подпись без токена бота нельзя, поэтому {@code user.id} из такой строки можно
 * доверять. Поле {@code signature} (Bot API 8.0, для проверки без токена) входит в
 * data_check_string как обычное поле.
 */
public class InitDataValidator {

	private static final String HMAC = "HmacSHA256";

	private final byte[] secret;

	private final Duration maxAge;

	private final JsonMapper json;

	public InitDataValidator(String botToken, Duration maxAge, JsonMapper json) {
		this.secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
		this.maxAge = maxAge;
		this.json = json;
	}

	/** @return Telegram id пользователя, если подпись верна и вход не устарел; иначе пусто */
	public OptionalLong validate(String initData, Instant now) {
		if (initData == null || initData.isBlank()) {
			return OptionalLong.empty();
		}
		var fields = new TreeMap<String, String>();
		for (var pair : initData.split("&")) {
			var eq = pair.indexOf('=');
			if (eq <= 0) {
				return OptionalLong.empty();
			}
			var key = decode(pair.substring(0, eq));
			if (fields.put(key, decode(pair.substring(eq + 1))) != null) {
				return OptionalLong.empty(); // одно поле дважды — строку собрал не Telegram
			}
		}
		var hash = fields.remove("hash");
		if (hash == null) {
			return OptionalLong.empty();
		}
		var dataCheckString = fields.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining("\n"));
		var expected = HexFormat.of().formatHex(hmac(secret, dataCheckString.getBytes(StandardCharsets.UTF_8)));
		// Сравнение за постоянное время: equals() выходит на первом несовпавшем символе,
		// и по времени ответа подпись можно было бы подбирать по одному символу
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				hash.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
			return OptionalLong.empty();
		}
		if (!fresh(fields.get("auth_date"), now)) {
			return OptionalLong.empty();
		}
		return userId(fields.get("user"));
	}

	/** Подписанная строка без срока жила бы вечно: утёкшая однажды — пускала бы всегда. */
	private boolean fresh(String authDate, Instant now) {
		if (authDate == null) {
			return false;
		}
		try {
			var issued = Instant.ofEpochSecond(Long.parseLong(authDate));
			// Минута запаса на расхождение часов сервера и Telegram
			return !issued.isAfter(now.plusSeconds(60)) && !issued.plus(maxAge).isBefore(now);
		}
		catch (NumberFormatException e) {
			return false;
		}
	}

	private OptionalLong userId(String user) {
		if (user == null) {
			return OptionalLong.empty();
		}
		try {
			var id = json.readTree(user).path("id");
			return id.isIntegralNumber() ? OptionalLong.of(id.asLong()) : OptionalLong.empty();
		}
		catch (JacksonException e) {
			return OptionalLong.empty();
		}
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			var mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(key, HMAC));
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 недоступен", e);
		}
	}

}

package kg.dental;

import java.net.URI;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * Версия файлов Mini App — новая при каждом запуске приложения, то есть при каждом обновлении
 * сервера. Она стоит в адресах: страница открывается как {@code /?v=<версия>}, файлы — из
 * {@code /a/<версия>/...}. Новый адрес — новый файл для любого кэша: встроенный браузер
 * Telegram на iPhone (WKWebView) держал старый app.js даже после очистки кэша Telegram.
 */
@Component
public class AssetVersion {

	private final String value = Long.toString(Instant.now().getEpochSecond(), 36);

	public String value() {
		return value;
	}

	/**
	 * Адрес Mini App для кнопок бота: {@code https://host/?day=tomorrow&v=<версия>}.
	 *
	 * @param query дополнительные параметры без «?» или null
	 */
	public URI miniApp(URI publicUrl, String query) {
		var base = publicUrl.toString().replaceAll("/+$", "");
		return URI.create(base + "/?" + (query == null ? "" : query + "&") + "v=" + value);
	}

}

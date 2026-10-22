package kg.dental.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import kg.dental.AssetVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Страница Mini App. Ссылки на app.css и app.js в ней — с версией (MiniAppWebConfig), а сама
 * страница не кэшируется вовсе (no-store): после обновления сервера она сразу ведёт на новые файлы.
 */
@RestController
class IndexController {

	private final String html;

	IndexController(AssetVersion version) {
		var assets = MiniAppWebConfig.PREFIX + version.value() + "/";
		var source = read("static/index.html");
		this.html = source.replace("href=\"app.css\"", "href=\"" + assets + "app.css\"")
			.replace("src=\"js/app.js\"", "src=\"" + assets + "js/app.js\"");
		// Поменяли разметку index.html и замена молча перестала работать — лучше не стартовать
		if (!html.contains(assets + "app.css") || !html.contains(assets + "js/app.js")) {
			throw new IllegalStateException("В index.html не найдены ссылки app.css и js/app.js");
		}
	}

	@GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<String> index() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
	}

	private static String read(String path) {
		try (var in = new ClassPathResource(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}

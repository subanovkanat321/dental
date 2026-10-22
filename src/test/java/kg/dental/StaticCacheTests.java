package kg.dental;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * После обновления сервера Mini App сразу получает новые файлы: страница не кэшируется,
 * а файлы идут по адресам с версией, которых в старом кэше нет (AssetVersion).
 */
@IntegrationTest
class StaticCacheTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	AssetVersion version;

	@Test
	void pageIsNeverCachedAndLinksVersionedFiles() {
		var result = mvc.get().uri("/").exchange();

		assertThat(result).hasStatusOk();
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		assertThat(result).bodyText()
			.contains("href=\"/a/" + version.value() + "/app.css\"")
			.contains("src=\"/a/" + version.value() + "/js/app.js\"");
	}

	@Test
	void versionedFilesAreCachedForAYearIncludingRelativeImport() {
		// js/state.js — так его запросит браузер по import './state.js' из /a/<версия>/js/app.js
		for (var file : new String[] { "app.css", "js/app.js", "js/state.js" }) {
			var result = mvc.get().uri("/a/" + version.value() + "/" + file).exchange();
			assertThat(result).as(file).hasStatusOk();
			assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(file)
				.contains("max-age=31536000")
				.contains("immutable");
		}
	}

	@Test
	void otherVersionIsNotServed() {
		assertThat(mvc.get().uri("/a/old123/js/app.js")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void miniAppUrlForBotButtons() {
		assertThat(version.miniApp(URI.create("https://dental.test"), null))
			.isEqualTo(URI.create("https://dental.test/?v=" + version.value()));
		assertThat(version.miniApp(URI.create("https://dental.test/"), "day=tomorrow"))
			.isEqualTo(URI.create("https://dental.test/?day=tomorrow&v=" + version.value()));
	}

}

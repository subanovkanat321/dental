package kg.dental;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** После обновления сервера Mini App сразу получает новые файлы, а не версию из кэша Telegram. */
@IntegrationTest
class StaticCacheTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void miniAppFilesAreRevalidatedEveryTime() {
		for (var path : new String[] { "/index.html", "/js/app.js", "/js/state.js", "/app.css" }) {
			var result = mvc.get().uri(path).exchange();
			assertThat(result).as(path).hasStatusOk();
			assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(path).contains("no-cache");
			var lastModified = result.getResponse().getHeader(HttpHeaders.LAST_MODIFIED);
			assertThat(lastModified).as(path).isNotNull();
			// Файл не менялся — короткий ответ 304 без тела
			assertThat(mvc.get().uri(path).header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
				.hasStatus(HttpStatus.NOT_MODIFIED);
		}
	}

}

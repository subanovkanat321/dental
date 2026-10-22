package kg.dental.web;

import java.time.Duration;

import kg.dental.AssetVersion;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

/**
 * Файлы Mini App с версией в адресе: {@code /a/<версия>/js/app.js} → {@code static/js/app.js}.
 * Относительный импорт {@code ./state.js} из такого app.js тоже идёт с версией. Раз адрес
 * меняется с каждой версией, файл можно кэшировать на год: старую копию браузер не возьмёт.
 */
@Configuration(proxyBeanMethods = false)
class MiniAppWebConfig implements WebMvcConfigurer {

	static final String PREFIX = "/a/";

	private final AssetVersion version;

	MiniAppWebConfig(AssetVersion version) {
		this.version = version;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(PREFIX + "**")
			.addResourceLocations("classpath:/static/")
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
			.resourceChain(true)
			// Версия — первый сегмент пути; файл ищется без него. Чужая версия — 404
			.addResolver(new VersionResourceResolver().addFixedVersionStrategy(version.value(), "/**"));
	}

}

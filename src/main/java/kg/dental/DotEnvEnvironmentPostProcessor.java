package kg.dental;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Профиль local: секреты и настройки ноутбука из файла .env (KEY=value) рядом с pom.xml.
 * <p>
 * Не {@code spring.config.import: file:.env[.properties]}: Spring Boot читает .properties
 * в ISO-8859-1, и кириллица из .env (подпись клиники) превращалась в «ÐÐ»Ð¸Ð½Ð¸ÐºÐ°».
 * Здесь файл читается в UTF-8. Настоящие переменные окружения важнее .env.
 * На сервере .env читает Docker Compose, а не приложение, — там этот класс ничего не делает.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String SOURCE = "dotenv";

	private final Path file;

	public DotEnvEnvironmentPostProcessor() {
		this(Path.of(".env"));
	}

	DotEnvEnvironmentPostProcessor(Path file) {
		this.file = file;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.acceptsProfiles(Profiles.of("local")) || !Files.isRegularFile(file)) {
			return;
		}
		var properties = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Не прочитать " + file.toAbsolutePath(), e);
		}
		var source = new PropertiesPropertySource(SOURCE, properties);
		var sources = environment.getPropertySources();
		if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
		}
		else {
			sources.addLast(source);
		}
	}

	/** После загрузки application*.yaml: тогда уже известно, что включён профиль local. */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}

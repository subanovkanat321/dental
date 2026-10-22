package kg.dental;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DotEnvEnvironmentPostProcessorTests {

	@TempDir
	Path dir;

	@Test
	void readsCyrillicAsUtf8InLocalProfile() throws Exception {
		var env = environment("local");
		new DotEnvEnvironmentPostProcessor(write("CLINIC_SIGNATURE=Клиника «Улыбка»\nTELEGRAM_MODE=polling\n"))
			.postProcessEnvironment(env, null);

		assertThat(env.getProperty("CLINIC_SIGNATURE")).isEqualTo("Клиника «Улыбка»");
		assertThat(env.getProperty("TELEGRAM_MODE")).isEqualTo("polling");
	}

	@Test
	void realEnvironmentVariablesWinOverFile() throws Exception {
		var env = environment("local");
		env.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new MapPropertySource(
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("TELEGRAM_MODE", "off")));
		new DotEnvEnvironmentPostProcessor(write("TELEGRAM_MODE=polling\n")).postProcessEnvironment(env, null);

		assertThat(env.getProperty("TELEGRAM_MODE")).isEqualTo("off");
	}

	@Test
	void ignoredOutsideLocalProfileAndWithoutFile() throws Exception {
		var prod = environment("prod");
		new DotEnvEnvironmentPostProcessor(write("CLINIC_SIGNATURE=x\n")).postProcessEnvironment(prod, null);
		assertThat(prod.containsProperty("CLINIC_SIGNATURE")).isFalse();

		var local = environment("local");
		new DotEnvEnvironmentPostProcessor(dir.resolve("missing.env")).postProcessEnvironment(local, null);
		assertThat(local.getPropertySources().contains(DotEnvEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	private Path write(String content) throws Exception {
		return Files.writeString(dir.resolve(".env"), content, StandardCharsets.UTF_8);
	}

	private static StandardEnvironment environment(String profile) {
		var env = new StandardEnvironment();
		env.setActiveProfiles(profile);
		return env;
	}

}

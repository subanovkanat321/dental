package kg.dental;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Полное приложение на настоящем Postgres (Testcontainers) и MockMvc. Одна аннотация на все
 * такие тесты — один и тот же набор настроек, поэтому Spring поднимает контекст и контейнер
 * один раз и переиспользует их во всех классах. Бот в режиме off: тесты не ходят в Telegram.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = { "dental.telegram.bot-token=" + TestInitData.BOT_TOKEN,
		"dental.telegram.allowed-ids=" + TestInitData.DOCTOR_ID, "dental.telegram.webhook-secret=test-webhook-secret",
		"dental.telegram.public-url=https://dental.test", "dental.telegram.clinic-contact=+996 555 000 000" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}

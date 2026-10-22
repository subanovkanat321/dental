package kg.dental;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Приложение поднимается, Flyway применяет миграции, Hibernate сверяет схему. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DentalApplicationTests {

	@Test
	void contextLoads() {
	}

}

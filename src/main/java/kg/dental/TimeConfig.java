package kg.dental;

import java.time.Clock;

import kg.dental.appointment.WorkSchedule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Часы приложения. Сервисы берут «сейчас» из {@link Clock}, а не из {@code Instant.now()}:
 * в тестах часы можно подменить и проверить «сегодня», «завтра», «прошлый приём».
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** Разбор WORK_HOURS / WORK_DAYS при старте: ошибка в них не даст приложению подняться. */
	@Bean
	WorkSchedule workSchedule(DentalProperties properties) {
		return WorkSchedule.parse(properties.workHours(), properties.workDays());
	}

}

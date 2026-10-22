package kg.dental;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки {@code dental.*} из application.yaml. */
@ConfigurationProperties("dental")
public record DentalProperties(ZoneId timeZone) {
}

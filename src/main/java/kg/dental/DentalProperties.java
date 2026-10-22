package kg.dental;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки {@code dental.*} из application.yaml.
 *
 * @param workHours рабочие часы «09:00-18:00» (WORK_HOURS)
 * @param workDays рабочие дни «MON-SUN», «MON-SAT» или «MON,WED,FRI» (WORK_DAYS)
 */
@ConfigurationProperties("dental")
public record DentalProperties(ZoneId timeZone, String workHours, String workDays) {
}

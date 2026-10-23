package kg.dental;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Самозапись пациентов через бота ({@code dental.booking.*}).
 *
 * @param enabled включена ли (PATIENT_BOOKING); выключена — посторонний видит только контакт клиники
 * @param minLead не раньше чем через столько от «сейчас»: врачу нужно время увидеть заявку
 * @param daysAhead не дальше стольких дней вперёд
 */
@ConfigurationProperties("dental.booking")
public record BookingProperties(boolean enabled, Duration minLead, int daysAhead) {
}

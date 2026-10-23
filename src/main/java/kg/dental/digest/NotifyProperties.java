package kg.dental.digest;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Когда бот пишет врачу ({@code dental.notify.*}), время — по Бишкеку.
 *
 * @param morningTime утренняя сводка: сегодня и завтра (MORNING_DIGEST_TIME)
 * @param eveningTime вечерняя: кому не напомнили на завтра, что не отмечено сегодня (EVENING_DIGEST_TIME)
 * @param beforeMinutes за сколько минут предупредить о приёме; 0 — не предупреждать (NOTIFY_BEFORE_MINUTES)
 */
@ConfigurationProperties("dental.notify")
public record NotifyProperties(LocalTime morningTime, LocalTime eveningTime, int beforeMinutes) {
}

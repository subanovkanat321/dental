package kg.dental.digest;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Когда бот пишет врачу ({@code dental.notify.*}), время — по Бишкеку.
 *
 * @param morningTime утренняя сводка: сегодня и завтра (MORNING_DIGEST_TIME)
 * @param eveningTime вечерняя: кому не напомнили на завтра, что не отмечено сегодня (EVENING_DIGEST_TIME)
 * @param beforeMinutes за сколько минут предупредить о приёме; 0 — не предупреждать (NOTIFY_BEFORE_MINUTES)
 * @param patientReminderTime когда бот сам напоминает пациентам, записавшимся через бота, о завтрашнем
 * приёме (PATIENT_REMINDER_TIME) — раньше вечерней сводки, чтобы она их уже посчитала
 * @param patientReminders false — бот пациентам сам не напоминает (AUTO_REMIND_PATIENTS)
 * @param reviewTime когда бот спрашивает оценку за вчерашний приём (REVIEW_TIME)
 * @param reviews false — оценку не спрашивать (ASK_REVIEWS)
 */
@ConfigurationProperties("dental.notify")
public record NotifyProperties(LocalTime morningTime, LocalTime eveningTime, int beforeMinutes,
		LocalTime patientReminderTime, boolean patientReminders, LocalTime reviewTime, boolean reviews) {
}

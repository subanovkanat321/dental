package kg.dental.patient;

/**
 * Пациент в ответах API. Телефон нужен Mini App, чтобы собрать ссылку wa.me у себя
 * в браузере: он уходит только в тело ответа по HTTPS, не в URL и не в логи.
 *
 * @param telegram пациент записывался через бота — ему можно напомнить в Telegram. Сам
 * Telegram id наружу не отдаём: Mini App он не нужен
 */
public record PatientView(long id, String fullName, String phone, boolean telegram) {

	public static PatientView of(Patient patient) {
		return new PatientView(patient.getId(), patient.getFullName(), patient.getPhone(),
				patient.getTelegramId() != null);
	}

}

package kg.dental.telegram;

import kg.dental.clinic.ClinicView;
import kg.dental.clinic.PhoneText;
import kg.dental.patient.Lang;
import kg.dental.patient.PatientTexts;

/**
 * Тексты бота (DD13). Врачам — по-русски, константами; пациенту — на его языке из
 * messages*.properties (PatientTexts).
 */
public final class BotTexts {

	static final String MENU_BUTTON = "Открыть день";

	static final String WELCOME = "Каждое утро в 9:00 пришлю сводку на завтра. Расписание — кнопка «Открыть день».";

	private BotTexts() {
	}

	/** Постороннему при включённой самозаписи: как записаться через приложение. */
	static String patientWelcome(PatientTexts t, Lang lang, String clinicContact) {
		var text = t.get(lang, "bot.patient.welcome");
		return clinicContact.isEmpty() ? text : text + t.get(lang, "bot.clinic-phone", clinicContact);
	}

	/**
	 * /help: к кому идти с вопросом. Запись и приём — клиника; не открывается приложение —
	 * техподдержка (если её контакты заданы в «О клинике»).
	 */
	static String help(PatientTexts t, Lang lang, ClinicView clinic, String clinicContact) {
		var text = new StringBuilder(t.get(lang, "help.title")).append("\n");
		text.append(t.get(lang, "help.book")).append("\n");
		if (!clinicContact.isEmpty()) {
			text.append(t.get(lang, "help.phone", clinicContact)).append("\n");
		}
		if (clinic.address() != null) {
			text.append(t.get(lang, "help.address", clinic.address())).append("\n");
		}
		if (clinic.hasSupport()) {
			text.append(t.get(lang, "help.support"));
			if (clinic.supportTelegram() != null) {
				text.append(" @").append(clinic.supportTelegram());
			}
			if (clinic.supportPhone() != null) {
				text.append(clinic.supportTelegram() != null ? ", " : " ").append(PhoneText.format(clinic.supportPhone()));
			}
		}
		return text.toString().strip();
	}

	/** Постороннему без самозаписи: ни данных, ни кнопки — только как записаться. */
	static String stranger(PatientTexts t, Lang lang, String clinicContact) {
		var text = t.get(lang, "bot.stranger");
		return clinicContact.isEmpty() ? text : text + t.get(lang, "bot.stranger.contact", clinicContact);
	}

	/** То же для Mini App (ответ 403): язык там выбирает сам экран, сервер отвечает по-русски. */
	public static String stranger(String clinicContact) {
		var text = "Это служебный бот записи клиники.";
		return clinicContact.isEmpty() ? text : text + " Записаться на приём: " + clinicContact;
	}

}

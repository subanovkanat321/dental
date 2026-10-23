package kg.dental.telegram;

import kg.dental.clinic.ClinicView;
import kg.dental.clinic.PhoneText;

/** Тексты бота (DD13). */
public final class BotTexts {

	static final String MENU_BUTTON = "Открыть день";

	static final String WELCOME = "Каждое утро в 9:00 пришлю сводку на завтра. Расписание — кнопка «Открыть день».";

	private BotTexts() {
	}

	static final String BOOK_BUTTON = "Записаться на приём";

	static final String STALE_BUTTON = "Эта запись уже изменилась — откройте приложение";

	static final String CONTACT_SAVED = "Спасибо, номер получен. Вернитесь в приложение и завершите запись.";

	/** Постороннему при включённой самозаписи: как записаться через приложение. */
	static String patientWelcome(String clinicContact) {
		var text = "Здравствуйте! Это бот записи к стоматологу. Нажмите «Записаться на приём» — "
				+ "выберите услугу и свободное время.";
		return clinicContact.isEmpty() ? text : text + " Телефон клиники: " + clinicContact;
	}

	/**
	 * /help: к кому идти с вопросом. Запись и приём — клиника; не открывается приложение —
	 * техподдержка (если её контакты заданы в «О клинике»).
	 */
	static String help(ClinicView clinic, String clinicContact) {
		var text = new StringBuilder("🦷 Бот записи к стоматологу.\n");
		text.append("• Записаться, посмотреть или отменить запись — кнопка «Записаться» внизу или /start.\n");
		if (!clinicContact.isEmpty()) {
			text.append("• Вопросы о приёме — телефон клиники: ").append(clinicContact).append("\n");
		}
		if (clinic.address() != null) {
			text.append("• Адрес: ").append(clinic.address()).append("\n");
		}
		if (clinic.hasSupport()) {
			text.append("🛟 Не открывается приложение или что-то не работает — техподдержка:");
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
	public static String stranger(String clinicContact) {
		var text = "Это служебный бот записи клиники.";
		return clinicContact.isEmpty() ? text : text + " Записаться на приём: " + clinicContact;
	}

}

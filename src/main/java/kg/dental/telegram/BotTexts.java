package kg.dental.telegram;

/** Тексты бота (DD13). */
public final class BotTexts {

	static final String MENU_BUTTON = "Открыть день";

	static final String WELCOME = "Каждое утро в 9:00 пришлю сводку на завтра. Расписание — кнопка «Открыть день».";

	private BotTexts() {
	}

	static final String BOOK_BUTTON = "Записаться на приём";

	static final String CONTACT_SAVED = "Спасибо, номер получен. Вернитесь в приложение и завершите запись.";

	/** Постороннему при включённой самозаписи: как записаться через приложение. */
	static String patientWelcome(String clinicContact) {
		var text = "Здравствуйте! Это бот записи к стоматологу. Нажмите «Записаться на приём» — "
				+ "выберите услугу и свободное время.";
		return clinicContact.isEmpty() ? text : text + " Телефон клиники: " + clinicContact;
	}

	/** Постороннему без самозаписи: ни данных, ни кнопки — только как записаться. */
	public static String stranger(String clinicContact) {
		var text = "Это служебный бот записи клиники.";
		return clinicContact.isEmpty() ? text : text + " Записаться на приём: " + clinicContact;
	}

}

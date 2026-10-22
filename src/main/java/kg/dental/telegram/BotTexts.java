package kg.dental.telegram;

/** Тексты бота (DD13). */
public final class BotTexts {

	static final String MENU_BUTTON = "Открыть день";

	static final String WELCOME = "Каждое утро в 9:00 пришлю сводку на завтра. Расписание — кнопка «Открыть день».";

	private BotTexts() {
	}

	/** Постороннему: ни данных, ни кнопки — только как записаться. */
	public static String stranger(String clinicContact) {
		var text = "Это служебный бот записи клиники.";
		return clinicContact.isEmpty() ? text : text + " Записаться на приём: " + clinicContact;
	}

}

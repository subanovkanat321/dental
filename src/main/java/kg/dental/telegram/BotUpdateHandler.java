package kg.dental.telegram;

import java.util.List;

import kg.dental.AssetVersion;
import kg.dental.BookingProperties;
import kg.dental.patient.PatientService;
import kg.dental.telegram.TelegramApi.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Что бот делает с входящим сообщением. Один и тот же код для webhook (сервер) и polling
 * (ноутбук). Список доступа проверяется на каждом сообщении, а не только на /start:
 * убрали id из ALLOWED_TELEGRAM_IDS — человек сразу теряет доступ.
 */
@Component
class BotUpdateHandler {

	private static final Logger log = LoggerFactory.getLogger(BotUpdateHandler.class);

	private final TelegramClient telegram;

	private final TelegramProperties properties;

	private final AssetVersion version;

	private final BookingProperties booking;

	private final PatientService patients;

	BotUpdateHandler(TelegramClient telegram, TelegramProperties properties, AssetVersion version,
			BookingProperties booking, PatientService patients) {
		this.telegram = telegram;
		this.properties = properties;
		this.version = version;
		this.booking = booking;
		this.patients = patients;
	}

	/**
	 * Кнопка меню с адресом новой версии — всем из списка доступа, при каждом старте
	 * приложения. Иначе после обновления сервера кнопка вела бы на старый адрес до нового /start.
	 */
	void refreshMenuButtons() {
		if (!miniAppAvailable()) {
			return;
		}
		for (var chatId : properties.allowedIds()) {
			try {
				setMenuButton(chatId);
			}
			catch (TelegramException e) {
				// Например, врач ещё не нажимал /start — кнопка появится по /start
				log.info("Кнопка меню не обновлена: {}", e.getMessage());
			}
		}
	}

	void handle(Update update) {
		var message = update.message();
		// Только личный чат: в группе бот мог бы показать расписание чужим людям
		if (message == null || message.from() == null || message.chat() == null
				|| !"private".equals(message.chat().type())) {
			return;
		}
		var chatId = message.chat().id();
		// Поделился номером для самозаписи. Только свой номер: user_id контакта — тот же, кто пишет,
		// иначе можно было бы прислать чужой контакт из записной книжки
		var contact = message.contact();
		if (contact != null && contact.userId() != null && contact.userId() == message.from().id()) {
			if (patients.saveContact(message.from().id(), contact.phoneNumber(), contact.firstName())) {
				telegram.sendMessage(chatId, BotTexts.CONTACT_SAVED);
			}
			return;
		}
		var start = message.text() != null && message.text().strip().startsWith("/start");
		if (!properties.isAllowed(message.from().id())) {
			if (start) {
				greetPatient(chatId);
			}
			return;
		}
		if (start) {
			greet(chatId, update.updateId());
		}
	}

	private void greet(long chatId, long updateId) {
		telegram.sendMessage(chatId, BotTexts.WELCOME);
		// Mini App открывается только по https: на ноутбуке без туннеля кнопки не будет
		if (miniAppAvailable()) {
			setMenuButton(chatId);
		}
		else {
			log.info("Кнопка Mini App не установлена: dental.telegram.public-url не https (update {})", updateId);
		}
	}

	/** Постороннему: кнопка самозаписи, если она включена и Mini App доступна; иначе — контакт клиники. */
	private void greetPatient(long chatId) {
		if (!booking.enabled() || !miniAppAvailable()) {
			telegram.sendMessage(chatId, BotTexts.stranger(properties.clinicContact()));
			return;
		}
		var url = version.miniApp(properties.publicUrl(), null);
		telegram.sendMessage(chatId, BotTexts.patientWelcome(properties.clinicContact()),
				List.of(new TelegramClient.Button(BotTexts.BOOK_BUTTON, url)));
		telegram.setChatMenuButton(chatId, "Записаться", url);
	}

	private void setMenuButton(long chatId) {
		telegram.setChatMenuButton(chatId, BotTexts.MENU_BUTTON, version.miniApp(properties.publicUrl(), null));
	}

	private boolean miniAppAvailable() {
		var url = properties.publicUrl();
		return url != null && "https".equals(url.getScheme());
	}

}

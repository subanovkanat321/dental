package kg.dental.telegram;

import java.util.List;
import java.util.Optional;

import kg.dental.AssetVersion;
import kg.dental.BookingProperties;
import kg.dental.doctor.Staff;
import kg.dental.patient.PatientService;
import kg.dental.telegram.TelegramApi.CallbackQuery;
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

	private final List<BotCallbacks> callbacks;

	private final Staff staff;

	BotUpdateHandler(TelegramClient telegram, TelegramProperties properties, AssetVersion version,
			BookingProperties booking, PatientService patients, List<BotCallbacks> callbacks, Staff staff) {
		this.staff = staff;
		this.telegram = telegram;
		this.properties = properties;
		this.version = version;
		this.booking = booking;
		this.patients = patients;
		this.callbacks = callbacks;
	}

	/**
	 * Кнопка меню с адресом новой версии — всем из списка доступа, при каждом старте
	 * приложения. Иначе после обновления сервера кнопка вела бы на старый адрес до нового /start.
	 */
	void refreshMenuButtons() {
		if (!miniAppAvailable()) {
			return;
		}
		for (var chatId : staff.allTelegramIds()) {
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
		if (update.callbackQuery() != null) {
			handleCallback(update.callbackQuery());
			return;
		}
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
		if (!staff.isStaff(message.from().id())) {
			if (start) {
				greetPatient(chatId);
			}
			return;
		}
		if (start) {
			greet(chatId, update.updateId());
		}
	}

	/**
	 * Нажатие кнопки под сообщением бота (ответ на напоминание). Отвечаем всегда: без
	 * answerCallbackQuery у человека крутятся «часики» на кнопке. Кнопки после ответа
	 * убираем — отвечают один раз; новый ответ можно дать врачу по телефону.
	 */
	private void handleCallback(CallbackQuery query) {
		if (query.from() == null || query.data() == null) {
			return;
		}
		var answer = callbacks.stream()
			.map(c -> c.handle(query.from().id(), query.data()))
			.flatMap(Optional::stream)
			.findFirst()
			.orElse(new BotCallbacks.Answer(BotTexts.STALE_BUTTON, null));
		telegram.answerCallbackQuery(query.id(), answer.popup());
		var message = query.message();
		if (message != null && message.chat() != null && "private".equals(message.chat().type())) {
			try {
				telegram.removeButtons(message.chat().id(), message.messageId());
			}
			catch (TelegramException e) {
				// Сообщение старше 48 часов или уже без кнопок — не страшно
				log.info("Кнопки не убраны: {}", e.getMessage());
			}
			if (answer.message() != null) {
				telegram.sendMessage(message.chat().id(), answer.message());
			}
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

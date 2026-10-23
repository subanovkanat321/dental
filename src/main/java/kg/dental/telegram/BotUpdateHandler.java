package kg.dental.telegram;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import kg.dental.AssetVersion;
import kg.dental.BookingProperties;
import kg.dental.clinic.ClinicService;
import kg.dental.doctor.Staff;
import kg.dental.patient.ChatLanguages;
import kg.dental.patient.Lang;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientTexts;
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

	private final ClinicService clinics;

	private final PatientTexts texts;

	private final ChatLanguages languages;

	BotUpdateHandler(TelegramClient telegram, TelegramProperties properties, AssetVersion version,
			BookingProperties booking, PatientService patients, List<BotCallbacks> callbacks, Staff staff,
			ClinicService clinics, PatientTexts texts, ChatLanguages languages) {
		this.texts = texts;
		this.languages = languages;
		this.staff = staff;
		this.clinics = clinics;
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
		var isStaff = staff.isStaff(message.from().id());
		// Врачам — по-русски; пациенту — его язык (первый раз — из настроек Telegram)
		var lang = isStaff ? Lang.RU : languages.detect(message.from().id(), message.from().languageCode());
		// Поделился номером для самозаписи. Только свой номер: user_id контакта — тот же, кто пишет,
		// иначе можно было бы прислать чужой контакт из записной книжки
		var contact = message.contact();
		if (contact != null && contact.userId() != null && contact.userId() == message.from().id()) {
			if (patients.saveContact(message.from().id(), contact.phoneNumber(), contact.firstName())) {
				telegram.sendMessage(chatId, texts.get(lang, "bot.contact-saved"));
			}
			return;
		}
		var text = message.text() == null ? "" : message.text().strip();
		// /help — любому: куда обращаться (клиника, техподдержка)
		if (text.startsWith("/help")) {
			sendHelp(chatId, lang);
			return;
		}
		var start = text.startsWith("/start");
		if (!isStaff) {
			if (start) {
				greetPatient(chatId, lang);
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
			.orElseGet(() -> new BotCallbacks.Answer(texts.get(texts.langOf(query.from().id()), "bot.stale"), null));
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

	private void sendHelp(long chatId, Lang lang) {
		var clinic = clinics.view();
		var buttons = new ArrayList<TelegramClient.Button>();
		if (clinic.supportUrl() != null) {
			buttons.add(TelegramClient.Button.link(texts.get(lang, "help.support-button"), URI.create(clinic.supportUrl())));
		}
		if (clinic.mapUrl() != null) {
			buttons.add(TelegramClient.Button.link(texts.get(lang, "clinic.map-button"), URI.create(clinic.mapUrl())));
		}
		telegram.sendMessage(chatId, BotTexts.help(texts, lang, clinic, properties.clinicContact()), buttons);
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
	private void greetPatient(long chatId, Lang lang) {
		if (!booking.enabled() || !miniAppAvailable()) {
			telegram.sendMessage(chatId, BotTexts.stranger(texts, lang, properties.clinicContact()));
			return;
		}
		var url = version.miniApp(properties.publicUrl(), null);
		telegram.sendMessage(chatId, BotTexts.patientWelcome(texts, lang, properties.clinicContact()),
				List.of(new TelegramClient.Button(texts.get(lang, "bot.book-button"), url)));
		telegram.setChatMenuButton(chatId, texts.get(lang, "bot.menu-button"), url);
	}

	private void setMenuButton(long chatId) {
		telegram.setChatMenuButton(chatId, BotTexts.MENU_BUTTON, version.miniApp(properties.publicUrl(), null));
	}

	private boolean miniAppAvailable() {
		var url = properties.publicUrl();
		return url != null && "https".equals(url.getScheme());
	}

}

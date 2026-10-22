package kg.dental.telegram;

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

	BotUpdateHandler(TelegramClient telegram, TelegramProperties properties) {
		this.telegram = telegram;
		this.properties = properties;
	}

	void handle(Update update) {
		var message = update.message();
		// Только личный чат: в группе бот мог бы показать расписание чужим людям
		if (message == null || message.from() == null || message.chat() == null
				|| !"private".equals(message.chat().type())) {
			return;
		}
		var chatId = message.chat().id();
		var start = message.text() != null && message.text().strip().startsWith("/start");
		if (!properties.isAllowed(message.from().id())) {
			if (start) {
				telegram.sendMessage(chatId, BotTexts.stranger(properties.clinicContact()));
			}
			return;
		}
		if (start) {
			greet(chatId, update.updateId());
		}
	}

	private void greet(long chatId, long updateId) {
		telegram.sendMessage(chatId, BotTexts.WELCOME);
		var url = properties.publicUrl();
		// Mini App открывается только по https: на ноутбуке без туннеля кнопки не будет
		if (url != null && "https".equals(url.getScheme())) {
			telegram.setChatMenuButton(chatId, BotTexts.MENU_BUTTON, url);
		}
		else {
			log.info("Кнопка Mini App не установлена: dental.telegram.public-url не https (update {})", updateId);
		}
	}

}

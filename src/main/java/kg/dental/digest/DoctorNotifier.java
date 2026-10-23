package kg.dental.digest;

import java.util.ArrayList;
import java.util.List;

import kg.dental.AssetVersion;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import kg.dental.telegram.TelegramProperties;
import kg.dental.telegram.TelegramProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Отправить уведомление всем из ALLOWED_TELEGRAM_IDS. Текст — только числа и время, без имён
 * и телефонов; имена видны в Mini App по кнопке. Не доставлено одному (например, не нажимал
 * /start) — остальным всё равно уходит.
 */
@Component
class DoctorNotifier {

	/** Кнопка: подпись и параметры адреса Mini App («day=tomorrow»), null — главный экран. */
	record Link(String text, String query) {
	}

	private static final Logger log = LoggerFactory.getLogger(DoctorNotifier.class);

	private final TelegramClient telegram;

	private final TelegramProperties properties;

	private final AssetVersion version;

	DoctorNotifier(TelegramClient telegram, TelegramProperties properties, AssetVersion version) {
		this.telegram = telegram;
		this.properties = properties;
		this.version = version;
	}

	/** Бот включён и настроен — расписанию есть смысл работать. */
	boolean enabled() {
		return properties.mode() != Mode.OFF && properties.configured();
	}

	void send(String kind, String text, List<Link> links) {
		var url = properties.publicUrl();
		var buttons = new ArrayList<TelegramClient.Button>();
		// Mini App открывается только по https: без него — сообщение без кнопок
		if (url != null && "https".equals(url.getScheme())) {
			links.forEach(l -> buttons.add(new TelegramClient.Button(l.text(), version.miniApp(url, l.query()))));
		}
		var sent = 0;
		for (var chatId : properties.allowedIds()) {
			try {
				telegram.sendMessage(chatId, text, buttons);
				sent++;
			}
			catch (TelegramException e) {
				log.warn("{}: не доставлено: {}", kind, e.getMessage());
			}
		}
		log.info("{}: отправлено {} из {}", kind, sent, properties.allowedIds().size());
	}

}

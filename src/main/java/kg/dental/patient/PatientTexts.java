package kg.dental.patient;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Тексты бота для пациента на его языке: messages.properties (русский) и messages_ky.properties
 * (кыргызский). Отдельные файлы — чтобы перевод мог проверить и поправить человек, а не программист.
 * Врачам бот пишет по-русски, в коде, как и раньше.
 */
@Component
public class PatientTexts {

	private final MessageSource messages;

	private final ChatLanguages languages;

	PatientTexts(MessageSource messages, ChatLanguages languages) {
		this.messages = messages;
		this.languages = languages;
	}

	public Lang langOf(long telegramId) {
		return languages.of(telegramId);
	}

	/** @param args подставляются в {0}, {1}… как строки */
	public String get(Lang lang, String key, Object... args) {
		var strings = new Object[args.length];
		for (var i = 0; i < args.length; i++) {
			// Числа MessageFormat отформатировал бы по-своему («1 000»): только строки
			strings[i] = String.valueOf(args[i]);
		}
		return messages.getMessage(key, strings, lang.locale());
	}

}

package kg.dental.telegram;

/**
 * Вызов Bot API не удался. Сообщение собирается здесь, а не берётся из исключения
 * RestClient: в его тексте бывает полный URL запроса, а в URL Bot API — токен бота.
 */
public class TelegramException extends RuntimeException {

	TelegramException(String message) {
		super(message);
	}

}

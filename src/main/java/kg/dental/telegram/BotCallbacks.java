package kg.dental.telegram;

import java.util.Optional;

/**
 * Обработчик нажатий кнопок под сообщениями бота. Реализует модуль, которому кнопка
 * принадлежит (напоминание — booking): так модуль telegram не зависит от приёмов и пациентов,
 * зависимость направлена внутрь, к нему.
 */
public interface BotCallbacks {

	/**
	 * @param fromId Telegram id нажавшего — от Telegram, подделать его нельзя
	 * @param data callback_data кнопки — её мог подменить кто угодно, проверять как ввод
	 * @return пусто — данные не этого обработчика
	 */
	Optional<Answer> handle(long fromId, String data);

	/**
	 * @param popup всплывающая подсказка у нажавшего
	 * @param message сообщение в чат после ответа, null — без него
	 */
	record Answer(String popup, String message) {
	}

}

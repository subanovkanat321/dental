package kg.dental.patient;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * На каком языке говорить с человеком в боте и Mini App (V16 chat_language). Язык — не
 * персональные данные: хранится по Telegram id, даже если человек ещё не записывался.
 */
@Service
public class ChatLanguages {

	private final JdbcClient jdbc;

	ChatLanguages(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Строки нет — русский. */
	@Transactional(readOnly = true)
	public Lang of(long telegramId) {
		return find(telegramId).orElse(Lang.RU);
	}

	/** Пусто — языка ещё нет: Mini App возьмёт его из настроек Telegram и сохранит. */
	@Transactional(readOnly = true)
	public Optional<Lang> find(long telegramId) {
		return jdbc.sql("select lang from chat_language where telegram_id = :id")
			.param("id", telegramId)
			.query(String.class)
			.optional()
			.map(Lang::of);
	}

	/** Выбор пациента в Mini App — всегда важнее того, что прислал Telegram. */
	@Transactional
	public void set(long telegramId, Lang lang) {
		jdbc.sql("""
				insert into chat_language (telegram_id, lang) values (:id, :lang)
				on conflict (telegram_id) do update set lang = excluded.lang, updated_at = now()""")
			.param("id", telegramId).param("lang", lang.code())
			.update();
	}

	/**
	 * Первое сообщение в боте: язык из Telegram (language_code «ky» — кыргызский). Только если
	 * человек ещё не выбирал сам: иначе /start с телефона на русском сбросил бы его выбор.
	 */
	@Transactional
	public Lang detect(long telegramId, String languageCode) {
		jdbc.sql("insert into chat_language (telegram_id, lang) values (:id, :lang) on conflict do nothing")
			.param("id", telegramId).param("lang", Lang.of(languageCode).code())
			.update();
		return of(telegramId);
	}

}

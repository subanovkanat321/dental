package kg.dental.clinic;

/**
 * Адрес и техподдержка в ответах API и в сообщениях бота. Пустое — null: не показываем.
 *
 * @param mapUrl ссылка 2ГИС на кабинет
 * @param supportTelegram ник поддержки без «@»; supportUrl — готовая ссылка https://t.me/ник
 * @param supportPhone телефон поддержки цифрами с кодом страны (996555123456)
 */
public record ClinicView(String address, String mapUrl, String supportTelegram, String supportUrl,
		String supportPhone) {

	public boolean hasSupport() {
		return supportTelegram != null || supportPhone != null;
	}

}

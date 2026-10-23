package kg.dental.clinic;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import kg.dental.patient.PhoneNumbers;
import kg.dental.shared.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Адрес кабинета со ссылкой 2ГИС и контакты техподдержки приложения. */
@Service
public class ClinicService {

	/** Домены 2ГИС: обычная ссылка и короткая «Поделиться» (go.2gis.com). */
	static final Set<String> MAP_HOSTS = Set.of("2gis.kg", "2gis.ru", "2gis.com", "2gis.kz");

	private static final Pattern TELEGRAM = Pattern.compile("[A-Za-z0-9_]{5,32}");

	private final ClinicSettingsRepository repository;

	ClinicService(ClinicSettingsRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public ClinicView view() {
		var s = settings();
		var telegram = s.getSupportTelegram();
		return new ClinicView(s.getAddress(), s.getMapUrl(), telegram, telegram == null ? null : "https://t.me/" + telegram,
				s.getSupportPhone());
	}

	@Transactional
	public ClinicView update(String address, String mapUrl, String supportTelegram, String supportPhone) {
		var cleanAddress = clean(address);
		if (cleanAddress != null && cleanAddress.length() > 200) {
			throw new InvalidFieldException("address", "Адрес — до 200 символов");
		}
		settings().update(cleanAddress, mapUrl(mapUrl), telegram(supportTelegram), phone(supportPhone));
		return view();
	}

	/**
	 * Только https-ссылка 2ГИС: её нажимают пациенты, и подменить её на чужой сайт (фишинг)
	 * нельзя даже по ошибке.
	 */
	static String mapUrl(String input) {
		var text = clean(input);
		if (text == null) {
			return null;
		}
		try {
			var uri = new URI(text);
			var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			var allowed = MAP_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
			if ("https".equals(uri.getScheme()) && allowed && text.length() <= 300) {
				return text;
			}
		}
		catch (URISyntaxException e) {
			// ниже — та же ошибка поля
		}
		throw new InvalidFieldException("mapUrl", "Нужна ссылка из 2ГИС: «Поделиться» → «Скопировать ссылку»");
	}

	/** «@kanat_dev», «kanat_dev» и «https://t.me/kanat_dev» — всё это ник kanat_dev. */
	static String telegram(String input) {
		var text = clean(input);
		if (text == null) {
			return null;
		}
		var nick = text.replaceFirst("^(https?://)?(t\\.me|telegram\\.me)/", "").replaceFirst("^@", "");
		if (!TELEGRAM.matcher(nick).matches()) {
			throw new InvalidFieldException("supportTelegram", "Ник Telegram: латиница, цифры и «_», от 5 символов");
		}
		return nick;
	}

	private static String phone(String input) {
		var text = clean(input);
		if (text == null) {
			return null;
		}
		return PhoneNumbers.normalize(text)
			.orElseThrow(() -> new InvalidFieldException("supportPhone", "Номер в формате 0555 123 456 или +996 555 123 456"));
	}

	private static String clean(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	private ClinicSettings settings() {
		return repository.findById(ClinicSettings.ID).orElseThrow();
	}

}

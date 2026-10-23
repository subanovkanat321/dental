package kg.dental.digest;

import java.util.ArrayList;
import java.util.List;

import kg.dental.AssetVersion;
import kg.dental.doctor.DoctorService;
import kg.dental.doctor.Staff;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import kg.dental.telegram.TelegramProperties;
import kg.dental.telegram.TelegramProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Уведомления врачам. Текст — только числа и время, без имён пациентов и телефонов; имена
 * видны в Mini App по кнопке. Не доставлено одному (например, не нажимал /start) — остальным
 * всё равно уходит.
 */
@Component
public class DoctorNotifier {

	/** Кнопка: подпись и параметры адреса Mini App («day=tomorrow»), null — главный экран. */
	public record Link(String text, String query) {
	}

	private static final Logger log = LoggerFactory.getLogger(DoctorNotifier.class);

	private final TelegramClient telegram;

	private final TelegramProperties properties;

	private final AssetVersion version;

	private final Staff staff;

	private final DoctorService doctors;

	DoctorNotifier(TelegramClient telegram, TelegramProperties properties, AssetVersion version, Staff staff,
			DoctorService doctors) {
		this.telegram = telegram;
		this.properties = properties;
		this.version = version;
		this.staff = staff;
		this.doctors = doctors;
	}

	/** Бот включён и настроен — расписанию есть смысл работать. */
	public boolean enabled() {
		return properties.mode() != Mode.OFF && properties.configured();
	}

	/**
	 * Уведомление о приёмах врача — ему самому. Telegram врача не привязан — админам
	 * (ALLOWED_TELEGRAM_IDS), и если врачей несколько, первой строкой — чьё это. Кнопки
	 * открывают «День» этого врача ({@code doctor=ID} в адресе).
	 */
	public void send(long doctorId, String kind, String text, List<Link> links) {
		var own = doctors.telegramOf(doctorId).isPresent();
		var recipients = staff.recipientsFor(doctorId);
		var body = !own && doctors.active().size() > 1 ? "🧑‍⚕️ " + doctors.nameOf(doctorId) + "\n" + text : text;
		var buttons = buttons(links.stream()
			.map(l -> new Link(l.text(), l.query() == null ? "doctor=" + doctorId : l.query() + "&doctor=" + doctorId))
			.toList());
		var sent = 0;
		for (var chatId : recipients) {
			try {
				telegram.sendMessage(chatId, body, buttons);
				sent++;
			}
			catch (TelegramException e) {
				log.warn("{}: не доставлено: {}", kind, e.getMessage());
			}
		}
		log.info("{}: отправлено {} из {}", kind, sent, recipients.size());
	}

	/** Всем сотрудникам (врачам и админам) — новость кабинета, например «заканчивается материал». */
	public void sendToStaff(String kind, String text, String query) {
		var buttons = buttons(List.of(new Link("Открыть склад", query)));
		var recipients = staff.allTelegramIds();
		var sent = 0;
		for (var chatId : recipients) {
			try {
				telegram.sendMessage(chatId, text, buttons);
				sent++;
			}
			catch (TelegramException e) {
				log.warn("{}: не доставлено: {}", kind, e.getMessage());
			}
		}
		log.info("{}: отправлено {} из {}", kind, sent, recipients.size());
	}

	/** Пациенту — ответ о его заявке (самозапись). Не доставлено — только в лог. */
	public void sendToPatient(long telegramId, String kind, String text, List<Link> links) {
		sendToPatient(telegramId, kind, text, links, List.of());
	}

	/** @param extra кнопки-ссылки после кнопок Mini App (например, «Как добраться» в 2ГИС) */
	public void sendToPatient(long telegramId, String kind, String text, List<Link> links,
			List<TelegramClient.Button> extra) {
		var all = new ArrayList<>(buttons(links));
		all.addAll(extra);
		try {
			telegram.sendMessage(telegramId, text, all);
			log.info("{}: отправлено пациенту", kind);
		}
		catch (TelegramException e) {
			log.warn("{}: пациенту не доставлено: {}", kind, e.getMessage());
		}
	}

	private List<TelegramClient.Button> buttons(List<Link> links) {
		var url = properties.publicUrl();
		var buttons = new ArrayList<TelegramClient.Button>();
		// Mini App открывается только по https: без него — сообщение без кнопок
		if (url != null && "https".equals(url.getScheme())) {
			links.forEach(l -> buttons.add(new TelegramClient.Button(l.text(), version.miniApp(url, l.query()))));
		}
		return buttons;
	}

}

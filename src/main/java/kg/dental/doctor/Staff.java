package kg.dental.doctor;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import kg.dental.telegram.TelegramProperties;
import org.springframework.stereotype.Component;

/**
 * Кто работает в приложении. Админ — Telegram из ALLOWED_TELEGRAM_IDS (владелец: заводит врачей).
 * Врач — работающий врач с привязанным Telegram. Сотрудник — любой из них: видит расписание
 * кабинета. Список проверяется на каждом запросе: убрали врача — доступ пропал сразу.
 */
@Component
public class Staff {

	private final TelegramProperties properties;

	private final DoctorService doctors;

	Staff(TelegramProperties properties, DoctorService doctors) {
		this.properties = properties;
		this.doctors = doctors;
	}

	public boolean isAdmin(long telegramId) {
		return properties.isAllowed(telegramId);
	}

	public boolean isStaff(long telegramId) {
		return isAdmin(telegramId) || doctors.doctorIdOf(telegramId).isPresent();
	}

	/** Врач, которым этот человек работает; админ без своего кресла — пусто. */
	public Optional<Long> doctorIdOf(long telegramId) {
		return doctors.doctorIdOf(telegramId);
	}

	/** Все сотрудники — кнопка «Открыть день» при старте приложения. */
	public Set<Long> allTelegramIds() {
		var ids = new LinkedHashSet<>(properties.allowedIds());
		ids.addAll(doctors.linkedTelegramIds());
		return ids;
	}

	/**
	 * Кому сообщить о приёме врача: ему самому; не привязан Telegram — админам
	 * (как было до нескольких врачей: ни одно уведомление не пропадёт).
	 */
	public Set<Long> recipientsFor(long doctorId) {
		return doctors.telegramOf(doctorId).map(Set::of).orElseGet(() -> Set.copyOf(properties.allowedIds()));
	}

}

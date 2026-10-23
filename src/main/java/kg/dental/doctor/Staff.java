package kg.dental.doctor;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import kg.dental.shared.ForbiddenException;
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

	/**
	 * Менять расписание врача (записать, перенести, отменить, отметить, напомнить) может сам
	 * этот врач или админ. Коллега видит чужой «День», но не правит его: случайное нажатие
	 * в чужом расписании испортило бы приём и статистику неявок.
	 */
	public void requireCanEdit(long telegramId, long doctorId) {
		if (isAdmin(telegramId)) {
			return;
		}
		if (doctors.doctorIdOf(telegramId).map(id -> id == doctorId).orElse(false)) {
			return;
		}
		throw new ForbiddenException("Это расписание коллеги — менять его может сам врач или администратор");
	}

	/** Деньги (выручка, отчёт) — свои (врач) или любого врача (админ); весь кабинет (null) — админ. */
	public void requireMoneyAccess(long telegramId, Long doctorId) {
		if (doctorId == null) {
			if (!isAdmin(telegramId)) {
				throw new ForbiddenException("Выручку всего кабинета видит администратор");
			}
			return;
		}
		requireCanEdit(telegramId, doctorId);
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

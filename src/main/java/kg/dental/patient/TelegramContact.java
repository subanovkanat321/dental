package kg.dental.patient;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Номер, которым человек поделился в боте (кнопка «Поделиться номером»), — до записи (V5).
 * Номер присылает сам Telegram, а не человек вводит: без опечаток и чужих номеров.
 */
@Entity
@Table(name = "telegram_contact")
class TelegramContact {

	@Id
	private Long telegramId;

	@Column(nullable = false)
	private String phone;

	private String firstName;

	@Column(nullable = false)
	private Instant sharedAt;

	protected TelegramContact() {
		// для JPA
	}

	TelegramContact(long telegramId, String phone, String firstName, Instant sharedAt) {
		this.telegramId = telegramId;
		this.phone = phone;
		this.firstName = firstName;
		this.sharedAt = sharedAt;
	}

	String getPhone() {
		return phone;
	}

	String getFirstName() {
		return firstName;
	}

}

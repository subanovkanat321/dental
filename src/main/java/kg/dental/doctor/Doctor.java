package kg.dental.doctor;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Врач кабинета (V11): своё кресло, своё расписание, свой Telegram. */
@Entity
@Table(name = "doctor")
class Doctor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String fullName;

	private String about;

	private Long telegramId;

	private byte[] photo;

	private String photoType;

	@Column(nullable = false)
	private int photoVersion;

	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Doctor() {
		// для JPA
	}

	Doctor(String fullName, String about, Long telegramId, Instant createdAt) {
		this.fullName = fullName;
		this.about = about;
		this.telegramId = telegramId;
		this.active = true;
		this.createdAt = createdAt;
	}

	void update(String fullName, String about, Long telegramId, boolean active) {
		this.fullName = fullName;
		this.about = about;
		this.telegramId = telegramId;
		this.active = active;
	}

	void setPhoto(byte[] photo, String type) {
		this.photo = photo;
		this.photoType = type;
		this.photoVersion++;
	}

	Long getId() {
		return id;
	}

	String getFullName() {
		return fullName;
	}

	String getAbout() {
		return about;
	}

	Long getTelegramId() {
		return telegramId;
	}

	byte[] getPhoto() {
		return photo;
	}

	String getPhotoType() {
		return photoType;
	}

	int getPhotoVersion() {
		return photoVersion;
	}

	boolean isActive() {
		return active;
	}

}

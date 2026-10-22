package kg.dental.patient;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Пациент: только ФИО и телефон (V1__init.sql). Телефон уже нормализован ({@link PhoneNumbers}). */
@Entity
public class Patient {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String fullName;

	@Column(nullable = false)
	private String phone;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Patient() {
		// для JPA
	}

	Patient(String fullName, String phone, Instant createdAt) {
		this.fullName = fullName;
		this.phone = phone;
		this.createdAt = createdAt;
	}

	void rename(String fullName, String phone) {
		this.fullName = fullName;
		this.phone = phone;
	}

	public Long getId() {
		return id;
	}

	public String getFullName() {
		return fullName;
	}

	public String getPhone() {
		return phone;
	}

}

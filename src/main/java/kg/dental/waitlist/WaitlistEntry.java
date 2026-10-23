package kg.dental.waitlist;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kg.dental.patient.Patient;

/** Строка листа ожидания (V2__waitlist.sql). */
@Entity
class WaitlistEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "patient_id")
	private Patient patient;

	@Column(nullable = false)
	private int durationMinutes;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** К какому врачу хочет (V11); null — к любому. */
	private Long doctorId;

	protected WaitlistEntry() {
		// для JPA
	}

	WaitlistEntry(Patient patient, Long doctorId, int durationMinutes, Instant createdAt) {
		this.patient = patient;
		this.doctorId = doctorId;
		this.durationMinutes = durationMinutes;
		this.createdAt = createdAt;
	}

	Long getDoctorId() {
		return doctorId;
	}

	Long getId() {
		return id;
	}

	Patient getPatient() {
		return patient;
	}

	int getDurationMinutes() {
		return durationMinutes;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

}

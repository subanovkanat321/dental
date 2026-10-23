package kg.dental.appointment;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Часы работы врача в один день недели (V13). */
@Entity
@Table(name = "doctor_hours")
@IdClass(DoctorHours.Key.class)
class DoctorHours {

	/** Составной ключ: врач + день недели. */
	record Key(Long doctorId, Short weekday) implements Serializable {

		Key() {
			this(null, null);
		}

	}

	@Id
	private Long doctorId;

	@Id
	private Short weekday;

	@Column(nullable = false)
	private LocalTime startTime;

	@Column(nullable = false)
	private LocalTime endTime;

	protected DoctorHours() {
		// для JPA
	}

	DoctorHours(long doctorId, DayOfWeek weekday, LocalTime startTime, LocalTime endTime) {
		this.doctorId = doctorId;
		this.weekday = (short) weekday.getValue();
		this.startTime = startTime;
		this.endTime = endTime;
	}

	DayOfWeek getWeekday() {
		return DayOfWeek.of(weekday);
	}

	LocalTime getStartTime() {
		return startTime;
	}

	LocalTime getEndTime() {
		return endTime;
	}

}

package kg.dental.timeoff;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Перерыв или выходной (V7): каждую неделю (weekday) или разово (firstDay…lastDay). */
@Entity
@Table(name = "time_off")
class TimeOff {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Short weekday;

	private LocalDate firstDay;

	private LocalDate lastDay;

	private LocalTime startTime;

	private LocalTime endTime;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String emoji;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected TimeOff() {
		// для JPA
	}

	TimeOff(DayOfWeek weekday, LocalDate firstDay, LocalDate lastDay, LocalTime startTime, LocalTime endTime,
			String title, String emoji, Instant createdAt) {
		this.weekday = weekday == null ? null : (short) weekday.getValue();
		this.firstDay = firstDay;
		this.lastDay = lastDay;
		this.startTime = startTime;
		this.endTime = endTime;
		this.title = title;
		this.emoji = emoji;
		this.createdAt = createdAt;
	}

	/** Действует ли в этот день. */
	boolean on(LocalDate date) {
		return weekday != null ? date.getDayOfWeek().getValue() == weekday
				: !date.isBefore(firstDay) && !date.isAfter(lastDay);
	}

	Long getId() {
		return id;
	}

	DayOfWeek getWeekday() {
		return weekday == null ? null : DayOfWeek.of(weekday);
	}

	LocalDate getFirstDay() {
		return firstDay;
	}

	LocalDate getLastDay() {
		return lastDay;
	}

	LocalTime getStartTime() {
		return startTime;
	}

	LocalTime getEndTime() {
		return endTime;
	}

	String getTitle() {
		return title;
	}

	String getEmoji() {
		return emoji;
	}

}

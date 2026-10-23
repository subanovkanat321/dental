package kg.dental.servicetype;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Услуга клиники (V5): пациент выбирает её при самозаписи, длительность приёма — из неё. */
@Entity
@Table(name = "service_type")
public class ServiceType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int durationMinutes;

	@Column(nullable = false)
	private boolean active;

	/** Цена «от», целые сомы; null — не показывать. */
	private Integer priceSom;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected ServiceType() {
		// для JPA
	}

	ServiceType(String name, int durationMinutes, Integer priceSom, Instant createdAt) {
		this.name = name;
		this.durationMinutes = durationMinutes;
		this.priceSom = priceSom;
		this.active = true;
		this.createdAt = createdAt;
	}

	void update(String name, int durationMinutes, Integer priceSom, boolean active) {
		this.name = name;
		this.durationMinutes = durationMinutes;
		this.priceSom = priceSom;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public boolean isActive() {
		return active;
	}

	public Integer getPriceSom() {
		return priceSom;
	}

}

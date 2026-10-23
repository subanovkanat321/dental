package kg.dental.clinic;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Адрес кабинета и техподдержка (V12): одна строка с id = 1. */
@Entity
@Table(name = "clinic_settings")
class ClinicSettings {

	static final short ID = 1;

	@Id
	private Short id;

	private String address;

	private String mapUrl;

	private String supportTelegram;

	private String supportPhone;

	protected ClinicSettings() {
		// для JPA
	}

	void update(String address, String mapUrl, String supportTelegram, String supportPhone) {
		this.address = address;
		this.mapUrl = mapUrl;
		this.supportTelegram = supportTelegram;
		this.supportPhone = supportPhone;
	}

	String getAddress() {
		return address;
	}

	String getMapUrl() {
		return mapUrl;
	}

	String getSupportTelegram() {
		return supportTelegram;
	}

	String getSupportPhone() {
		return supportPhone;
	}

}

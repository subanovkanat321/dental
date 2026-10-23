package kg.dental.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Профиль врача (V9): одна строка с id = 1. */
@Entity
@Table(name = "clinic_profile")
class ClinicProfile {

	static final short ID = 1;

	@Id
	private Short id;

	private String doctorName;

	private String about;

	private byte[] photo;

	private String photoType;

	@Column(nullable = false)
	private int photoVersion;

	protected ClinicProfile() {
		// для JPA
	}

	void update(String doctorName, String about) {
		this.doctorName = doctorName;
		this.about = about;
	}

	void setPhoto(byte[] photo, String type) {
		this.photo = photo;
		this.photoType = type;
		this.photoVersion++;
	}

	String getDoctorName() {
		return doctorName;
	}

	String getAbout() {
		return about;
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

}

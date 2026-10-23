package kg.dental.doctor;

/**
 * Врач в ответах API.
 *
 * @param photoUrl адрес фото с версией ({@code /doctors/3/photo?v=2}); null — фото нет
 * @param telegramLinked привязан ли Telegram — сам id наружу отдаём только админу (DoctorAdminView)
 */
public record DoctorView(long id, String fullName, String about, String photoUrl, boolean active,
		boolean telegramLinked) {

	static DoctorView of(Doctor d) {
		return new DoctorView(d.getId(), d.getFullName(), d.getAbout(),
				d.getPhoto() == null ? null : "/doctors/" + d.getId() + "/photo?v=" + d.getPhotoVersion(), d.isActive(),
				d.getTelegramId() != null);
	}

}

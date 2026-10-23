package kg.dental.doctor;

/** Врач для админа («Меню → Врачи»): с Telegram id, чтобы было видно, кто к кому привязан. */
public record DoctorAdminView(long id, String fullName, String about, String photoUrl, boolean active,
		Long telegramId) {

	static DoctorAdminView of(Doctor d) {
		var view = DoctorView.of(d);
		return new DoctorAdminView(d.getId(), d.getFullName(), d.getAbout(), view.photoUrl(), d.isActive(),
				d.getTelegramId());
	}

}

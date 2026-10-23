package kg.dental.profile;

import java.util.Arrays;
import java.util.Optional;

import kg.dental.shared.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Имя, пара слов и фото врача — пациент видит их на экране записи. */
@Service
public class ProfileService {

	/** Браузер врача присылает фото уже уменьшенным; больше — значит что-то не так. */
	static final int MAX_PHOTO_BYTES = 1_000_000;

	/** Фото для ответа: байты, тип и версия (для кэша). */
	record Photo(byte[] bytes, String type, int version) {
	}

	private final ClinicProfileRepository repository;

	ProfileService(ClinicProfileRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public ProfileView view() {
		var p = profile();
		return new ProfileView(p.getDoctorName(), p.getAbout(),
				p.getPhoto() == null ? null : "/profile/photo?v=" + p.getPhotoVersion());
	}

	@Transactional
	public ProfileView update(String doctorName, String about) {
		var name = clean(doctorName);
		var text = clean(about);
		if (name != null && name.length() > 100) {
			throw new InvalidFieldException("doctorName", "Не длиннее 100 символов");
		}
		if (text != null && text.length() > 300) {
			throw new InvalidFieldException("about", "Не длиннее 300 символов");
		}
		profile().update(name, text);
		return view();
	}

	/**
	 * Тип — по первым байтам файла, а не по имени или заголовку из запроса: их легко подделать,
	 * а отдаём мы фото всем с этим Content-Type.
	 */
	@Transactional
	public ProfileView setPhoto(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new InvalidFieldException("photo", "Выберите фото");
		}
		if (bytes.length > MAX_PHOTO_BYTES) {
			throw new InvalidFieldException("photo", "Фото больше 1 МБ");
		}
		var type = imageType(bytes)
			.orElseThrow(() -> new InvalidFieldException("photo", "Нужна картинка JPEG, PNG или WebP"));
		profile().setPhoto(bytes, type);
		return view();
	}

	@Transactional
	public ProfileView deletePhoto() {
		profile().setPhoto(null, null);
		return view();
	}

	@Transactional(readOnly = true)
	Optional<Photo> photo() {
		var p = profile();
		return p.getPhoto() == null ? Optional.empty()
				: Optional.of(new Photo(p.getPhoto(), p.getPhotoType(), p.getPhotoVersion()));
	}

	static Optional<String> imageType(byte[] b) {
		if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
			return Optional.of("image/jpeg");
		}
		if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Optional.of("image/png");
		}
		if (b.length >= 12 && startsWith(b, 'R', 'I', 'F', 'F')
				&& Arrays.equals(Arrays.copyOfRange(b, 8, 12), "WEBP".getBytes())) {
			return Optional.of("image/webp");
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] b, int... prefix) {
		if (b.length < prefix.length) {
			return false;
		}
		for (var i = 0; i < prefix.length; i++) {
			if ((b[i] & 0xFF) != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static String clean(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	private ClinicProfile profile() {
		return repository.findById(ClinicProfile.ID).orElseThrow();
	}

}

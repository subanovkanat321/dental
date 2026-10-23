package kg.dental.doctor;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Врачи кабинета: имена, фото, Telegram. Остальные модули спрашивают отсюда, кто чей врач. */
@Service
public class DoctorService {

	/** Браузер присылает фото уже уменьшенным; больше — значит что-то не так. */
	static final int MAX_PHOTO_BYTES = 1_000_000;

	static final String TELEGRAM_TAKEN = "telegram-taken";

	/** Фото для ответа: байты, тип и версия (для кэша). */
	record Photo(byte[] bytes, String type) {
	}

	private final DoctorRepository repository;

	private final Clock clock;

	DoctorService(DoctorRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Для пациента: кого можно выбрать. */
	@Transactional(readOnly = true)
	public List<DoctorView> active() {
		return repository.findByActiveTrueOrderByIdAsc().stream().map(DoctorView::of).toList();
	}

	/** Для сотрудников: все, сначала работающие. */
	@Transactional(readOnly = true)
	public List<DoctorView> all() {
		return repository.findAllByOrderByActiveDescIdAsc().stream().map(DoctorView::of).toList();
	}

	@Transactional(readOnly = true)
	public DoctorView get(long id) {
		return DoctorView.of(require(id));
	}

	/** Работающий врач для записи: скрытого выбрать нельзя. */
	@Transactional(readOnly = true)
	public DoctorView requireActive(long id) {
		var doctor = require(id);
		if (!doctor.isActive()) {
			throw new NotFoundException("Врач не найден");
		}
		return DoctorView.of(doctor);
	}

	/** Врач с этим Telegram — он работает в приложении как врач. */
	@Transactional(readOnly = true)
	public Optional<Long> doctorIdOf(long telegramId) {
		return repository.findByTelegramIdAndActiveTrue(telegramId).map(Doctor::getId);
	}

	/** Telegram врача — кому слать уведомления о его приёмах; пусто — не привязан. */
	@Transactional(readOnly = true)
	public Optional<Long> telegramOf(long doctorId) {
		return repository.findById(doctorId).filter(Doctor::isActive).map(Doctor::getTelegramId);
	}

	/** Telegram всех работающих врачей — кнопка меню при старте, вход в приложение. */
	@Transactional(readOnly = true)
	public List<Long> linkedTelegramIds() {
		return repository.findByActiveTrueOrderByIdAsc().stream().map(Doctor::getTelegramId)
			.filter(id -> id != null).toList();
	}

	/** Имя для админа в уведомлении «у Айгерим новая заявка», когда у врача нет Telegram. */
	@Transactional(readOnly = true)
	public String nameOf(long doctorId) {
		return repository.findById(doctorId).map(Doctor::getFullName).orElse("Врач");
	}

	@Transactional
	public DoctorAdminView create(String fullName, String about, Long telegramId) {
		var name = requireName(fullName);
		if (telegramId != null && repository.existsByTelegramId(telegramId)) {
			throw telegramTaken();
		}
		return DoctorAdminView.of(repository.save(new Doctor(name, cleanAbout(about), telegramId, clock.instant())));
	}

	@Transactional
	public DoctorAdminView update(long id, String fullName, String about, Long telegramId, boolean active) {
		var doctor = require(id);
		if (telegramId != null && repository.existsByTelegramIdAndIdNot(telegramId, id)) {
			throw telegramTaken();
		}
		doctor.update(requireName(fullName), cleanAbout(about), telegramId, active);
		return DoctorAdminView.of(doctor);
	}

	/** Профиль врач правит сам: имя и «о себе», без Telegram и без «скрыть». */
	@Transactional
	public DoctorView updateProfile(long id, String fullName, String about) {
		var doctor = require(id);
		doctor.update(requireName(fullName), cleanAbout(about), doctor.getTelegramId(), doctor.isActive());
		return DoctorView.of(doctor);
	}

	@Transactional(readOnly = true)
	public List<DoctorAdminView> adminList() {
		return repository.findAllByOrderByActiveDescIdAsc().stream().map(DoctorAdminView::of).toList();
	}

	/**
	 * Тип — по первым байтам файла, а не по имени или заголовку из запроса: их легко подделать,
	 * а отдаём мы фото всем с этим Content-Type.
	 */
	@Transactional
	public DoctorView setPhoto(long id, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new InvalidFieldException("photo", "Выберите фото");
		}
		if (bytes.length > MAX_PHOTO_BYTES) {
			throw new InvalidFieldException("photo", "Фото больше 1 МБ");
		}
		var type = imageType(bytes)
			.orElseThrow(() -> new InvalidFieldException("photo", "Нужна картинка JPEG, PNG или WebP"));
		var doctor = require(id);
		doctor.setPhoto(bytes, type);
		return DoctorView.of(doctor);
	}

	@Transactional
	public DoctorView deletePhoto(long id) {
		var doctor = require(id);
		doctor.setPhoto(null, null);
		return DoctorView.of(doctor);
	}

	@Transactional(readOnly = true)
	Optional<Photo> photo(long id) {
		return repository.findById(id)
			.filter(d -> d.getPhoto() != null)
			.map(d -> new Photo(d.getPhoto(), d.getPhotoType()));
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

	private static String requireName(String fullName) {
		var name = fullName == null ? "" : fullName.strip();
		if (name.isEmpty() || name.length() > 100) {
			throw new InvalidFieldException("fullName", "Имя врача — до 100 символов");
		}
		return name;
	}

	private static String cleanAbout(String about) {
		var text = about == null || about.isBlank() ? null : about.strip();
		if (text != null && text.length() > 300) {
			throw new InvalidFieldException("about", "Не длиннее 300 символов");
		}
		return text;
	}

	private static ConflictException telegramTaken() {
		return new ConflictException(TELEGRAM_TAKEN, "Этот Telegram уже у другого врача");
	}

	private Doctor require(long id) {
		return repository.findById(id).orElseThrow(() -> new NotFoundException("Врач не найден"));
	}

}

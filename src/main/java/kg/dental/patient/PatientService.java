package kg.dental.patient;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

	/** Запрос похож на номер: только цифры, «+» и разделители (и хотя бы три цифры). */
	private static final Pattern PHONE_CHARS = Pattern.compile("[+0-9\\s\\-()]+");

	private static final Pattern NOT_DIGIT = Pattern.compile("[^0-9]");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	private final PatientRepository patients;

	private final Clock clock;

	private final TelegramContactRepository contacts;

	PatientService(PatientRepository patients, Clock clock, TelegramContactRepository contacts) {
		this.patients = patients;
		this.clock = clock;
		this.contacts = contacts;
	}

	/** Профиль человека в боте: имя и номер, если он уже делился номером или записывался. */
	public record BotProfile(String fullName, String phone) {
	}

	/**
	 * Человек поделился номером в боте. Номер от Telegram — цифры без «+» в любом формате,
	 * поэтому нормализуем как международный.
	 *
	 * @return false — номер не похож на телефон (не сохраняем)
	 */
	@Transactional
	public boolean saveContact(long telegramId, String rawPhone, String firstName) {
		var digits = rawPhone == null ? "" : rawPhone.replaceAll("[^0-9]", "");
		var phone = PhoneNumbers.normalize("+" + digits);
		if (phone.isEmpty()) {
			return false;
		}
		contacts.save(new TelegramContact(telegramId, phone.get(), firstName, clock.instant()));
		return true;
	}

	/** Что знаем о человеке в боте: сначала его карточка пациента, иначе — поделённый номер. */
	@Transactional(readOnly = true)
	public Optional<BotProfile> botProfile(long telegramId) {
		var patient = patients.findByTelegramId(telegramId);
		if (patient.isPresent()) {
			return patient.map(p -> new BotProfile(p.getFullName(), p.getPhone()));
		}
		return contacts.findById(telegramId).map(c -> new BotProfile(c.getFirstName(), c.getPhone()));
	}

	/**
	 * Пациент для самозаписи: тот, кто уже записывался из этого Telegram; иначе — карточка,
	 * которую завёл врач (тот же номер и то же имя); иначе — новый. Телефон — только тот,
	 * которым человек поделился в Telegram.
	 */
	@Transactional
	public Patient forSelfBooking(long telegramId, String rawFullName) {
		var contact = contacts.findById(telegramId)
			.orElseThrow(() -> new InvalidFieldException("phone", "Поделитесь номером телефона"));
		var fullName = SPACES.matcher(rawFullName == null ? "" : rawFullName.strip()).replaceAll(" ");
		if (fullName.isEmpty() || fullName.length() > 200) {
			throw new InvalidFieldException("fullName", "Укажите имя пациента");
		}
		var now = clock.instant();
		var patient = patients.findByTelegramId(telegramId)
			.or(() -> patients.findByPhone(contact.getPhone())
				.stream()
				.filter(p -> p.getTelegramId() == null && sameName(p.getFullName(), fullName))
				.findFirst())
			.orElseGet(() -> patients.save(new Patient(fullName, contact.getPhone(), now)));
		patient.rename(fullName, contact.getPhone());
		patient.linkTelegram(telegramId, now);
		return patients.saveAndFlush(patient);
	}

	/** Telegram id пациента, если он записывался сам, — чтобы бот ответил ему о заявке. */
	@Transactional(readOnly = true)
	public Optional<Long> telegramIdOf(long patientId) {
		return patients.findById(patientId).map(Patient::getTelegramId);
	}

	@Transactional(readOnly = true)
	public Optional<Patient> byTelegram(long telegramId) {
		return patients.findByTelegramId(telegramId);
	}

	/**
	 * Поиск в «Новой записи»: по номеру — все пациенты с ним (у семьи один номер),
	 * иначе по части имени.
	 */
	@Transactional(readOnly = true)
	public List<PatientView> search(String query) {
		var text = query == null ? "" : query.strip();
		if (text.isEmpty()) {
			return List.of();
		}
		var digits = phoneDigits(text);
		var found = PHONE_CHARS.matcher(text).matches() && digits.length() >= 3
				? patients.findTop20ByPhoneContainingOrderByFullName(digits)
				: patients.findTop20ByFullNameContainingIgnoreCaseOrderByFullName(text);
		return found.stream().map(PatientView::of).toList();
	}

	@Transactional
	public PatientView create(PatientRequest request) {
		var valid = validate(request, null);
		return PatientView.of(patients.save(new Patient(valid.fullName(), valid.phone(), clock.instant())));
	}

	/** Исправить имя или телефон: те же правила, что при создании. */
	@Transactional
	public PatientView update(long id, PatientRequest request) {
		var patient = require(id);
		var valid = validate(request, id);
		patient.rename(valid.fullName(), valid.phone());
		return PatientView.of(patients.saveAndFlush(patient));
	}

	/** Кнопка WhatsApp «пора на осмотр» нажата — запомнить, чтобы не звать дважды. */
	@Transactional
	public void markRecallInvited(long id) {
		require(id).markRecallInvited(clock.instant());
	}

	private record Valid(String fullName, String phone) {
	}

	/** @param selfId при правке — сам пациент: совпадение с собой дублем не считается */
	private Valid validate(PatientRequest request, Long selfId) {
		var phone = PhoneNumbers.normalize(request.phone())
			.orElseThrow(() -> new InvalidFieldException("phone",
					"Номер в формате 0555 123 456, +996 555 123 456 или с кодом страны через +"));
		var fullName = SPACES.matcher(request.fullName().strip()).replaceAll(" ");
		if (!request.allowDuplicate()) {
			// Тот же номер и то же имя — скорее всего, тот же человек: иначе его история
			// неявок разделится на две карточки (D19). Другое имя с тем же номером — семья
			patients.findByPhone(phone)
				.stream()
				.filter(existing -> !existing.getId().equals(selfId))
				.filter(existing -> sameName(existing.getFullName(), fullName))
				.findFirst()
				.ifPresent(existing -> {
					throw new ConflictException(ConflictException.DUPLICATE_PATIENT, "Такой пациент уже есть",
							Map.of("patient", PatientView.of(existing)));
				});
		}
		return new Valid(fullName, phone);
	}

	/** Для модуля приёмов: пациент по id или 404. */
	@Transactional(readOnly = true)
	public Patient require(long id) {
		return patients.findById(id).orElseThrow(() -> new NotFoundException("Пациент не найден"));
	}

	/** «0555 12» ищет по «55512»: в базе номер хранится как 996555…, без местного нуля. */
	private static String phoneDigits(String text) {
		var digits = NOT_DIGIT.matcher(text).replaceAll("");
		return digits.startsWith("0") ? digits.substring(1) : digits;
	}

	private static boolean sameName(String a, String b) {
		return normalizeName(a).equals(normalizeName(b));
	}

	private static String normalizeName(String name) {
		return SPACES.matcher(name.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
	}

}

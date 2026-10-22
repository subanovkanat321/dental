package kg.dental.patient;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	PatientService(PatientRepository patients, Clock clock) {
		this.patients = patients;
		this.clock = clock;
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

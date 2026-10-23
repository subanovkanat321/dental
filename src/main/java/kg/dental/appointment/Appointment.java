package kg.dental.appointment;

import static kg.dental.appointment.AppointmentStatus.CANCELLED;
import static kg.dental.appointment.AppointmentStatus.DONE;
import static kg.dental.appointment.AppointmentStatus.NO_SHOW;
import static kg.dental.appointment.AppointmentStatus.REQUESTED;
import static kg.dental.appointment.AppointmentStatus.SCHEDULED;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import kg.dental.patient.Patient;
import kg.dental.servicetype.ServiceType;
import kg.dental.shared.ConflictException;

/**
 * Приём. Правила переходов статуса живут здесь, рядом с данными: сервис не может
 * «забыть» проверку, потому что поля меняются только через эти методы (R6, D8, ER1).
 * Пересечение по времени проверяет не этот класс, а база (appointment_no_overlap).
 */
@Entity
public class Appointment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "patient_id")
	private Patient patient;

	@Column(nullable = false)
	private Instant startAt;

	@Column(nullable = false)
	private Instant endAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AppointmentStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PatientReply reply;

	private Instant reminderOpenedAt;

	/** Оценка пациента 1–5 (V10). Ставит только ReviewQuery: JPA её не пишет и не затирает. */
	@Column(insertable = false, updatable = false)
	private Short rating;

	/**
	 * Оптимистическая блокировка (D18). Hibernate добавляет к UPDATE условие
	 * {@code where version = ?} и увеличивает номер; если строку уже изменили, обновится
	 * ноль строк — и вместо молчаливой перезаписи будет ошибка.
	 */
	@Version
	private Long version;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** Врач приёма (V11): у каждого своё кресло и своё расписание. Не меняется после записи. */
	@Column(nullable = false, updatable = false)
	private Long doctorId;

	/** Кто записал: врач или пациент сам через бота (V5). */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Source source;

	/** Услуга — у самозаписи всегда, у записи врачом её нет. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "service_type_id")
	private ServiceType serviceType;

	public enum Source {
		DOCTOR, PATIENT
	}

	protected Appointment() {
		// для JPA
	}

	Appointment(Patient patient, long doctorId, Instant startAt, Duration duration, Instant createdAt) {
		this.patient = patient;
		this.doctorId = doctorId;
		this.startAt = startAt;
		this.endAt = startAt.plus(duration);
		this.status = SCHEDULED;
		this.reply = PatientReply.NONE;
		this.createdAt = createdAt;
		this.source = Source.DOCTOR;
	}

	/** Заявка пациента из бота: время занято сразу, врач подтверждает или отклоняет. */
	static Appointment requested(Patient patient, long doctorId, Instant startAt, ServiceType serviceType,
			Instant createdAt) {
		var appointment = new Appointment(patient, doctorId, startAt, Duration.ofMinutes(serviceType.getDurationMinutes()),
				createdAt);
		appointment.status = REQUESTED;
		appointment.source = Source.PATIENT;
		appointment.serviceType = serviceType;
		return appointment;
	}

	/**
	 * Перенос. Ответ пациента и отметка напоминания относились к старому времени —
	 * о новом пациента нужно предупредить заново.
	 */
	void reschedule(Instant newStart, Duration duration) {
		if (status != SCHEDULED && status != REQUESTED) {
			throw invalidTransition("Перенести можно только запланированный приём или заявку");
		}
		this.startAt = newStart;
		this.endAt = newStart.plus(duration);
		this.reply = PatientReply.NONE;
		this.reminderOpenedAt = null;
	}

	void changeReply(PatientReply newReply) {
		requireStatus(SCHEDULED, "Ответ пациента отмечают только у запланированного приёма");
		this.reply = newReply;
	}

	/**
	 * Переходы из карточки приёма:
	 * <pre>
	 * REQUESTED → SCHEDULED | CANCELLED     «Подтвердить» / «Отклонить» заявку пациента
	 * SCHEDULED → CANCELLED                 «Отменить приём»
	 * SCHEDULED → DONE | NO_SHOW            «Пришёл» / «Не пришёл», только сегодня и раньше
	 * CANCELLED → SCHEDULED                 «Восстановить»: ответ и напоминание сбрасываются
	 * DONE ↔ NO_SHOW                        исправление ошибочной отметки
	 * </pre>
	 * NO_SHOW → SCHEDULED — только через {@link #undo}. Остальное — 409.
	 *
	 * @param startOfTomorrow начало завтрашнего дня по Бишкеку: приём раньше него уже
	 * можно отмечать
	 */
	void changeStatus(AppointmentStatus target, Instant startOfTomorrow) {
		var allowed = switch (status) {
			// Заявка: «Подтвердить» или «Отклонить»
			case REQUESTED -> target == SCHEDULED || target == CANCELLED;
			case SCHEDULED -> target == CANCELLED || target == DONE || target == NO_SHOW;
			case CANCELLED -> target == SCHEDULED;
			case DONE -> target == NO_SHOW;
			case NO_SHOW -> target == DONE;
		};
		if (!allowed) {
			throw invalidTransition("Так изменить статус нельзя");
		}
		if (status == SCHEDULED && (target == DONE || target == NO_SHOW) && !startAt.isBefore(startOfTomorrow)) {
			throw invalidTransition("Отметить явку можно только в день приёма или позже");
		}
		if (status == CANCELLED) {
			this.reply = PatientReply.NONE;
			this.reminderOpenedAt = null;
		}
		this.status = target;
	}

	/**
	 * «Вернуть» на плашке после отмены или «Не пришёл» (ER1): возвращает прежний статус,
	 * а ответ пациента и отметку напоминания оставляет как есть. В отличие от
	 * «Восстановить», ничего не теряется: отмена и «Не пришёл» эти поля не меняют, а у
	 * отменённого приёма их не изменить, поэтому в базе и так лежат прежние значения.
	 * Сервер не хранит историю, но проверяет, что такой статус мог быть до этого действия.
	 */
	void undo(AppointmentStatus previousStatus) {
		var possible = switch (status) {
			case CANCELLED -> previousStatus == SCHEDULED || previousStatus == REQUESTED;
			case NO_SHOW -> previousStatus == SCHEDULED || previousStatus == DONE;
			case REQUESTED, SCHEDULED, DONE -> false;
		};
		if (!possible) {
			throw invalidTransition("Вернуть можно только отмену приёма или отметку «неявка»");
		}
		this.status = previousStatus;
	}

	private void requireStatus(AppointmentStatus required, String message) {
		if (status != required) {
			throw invalidTransition(message);
		}
	}

	private static ConflictException invalidTransition(String message) {
		return new ConflictException(ConflictException.INVALID_TRANSITION, message);
	}

	public Long getId() {
		return id;
	}

	public Patient getPatient() {
		return patient;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public AppointmentStatus getStatus() {
		return status;
	}

	public Long getDoctorId() {
		return doctorId;
	}

	public Short getRating() {
		return rating;
	}

	public PatientReply getReply() {
		return reply;
	}

	public Instant getReminderOpenedAt() {
		return reminderOpenedAt;
	}

	public Source getSource() {
		return source;
	}

	public ServiceType getServiceType() {
		return serviceType;
	}

	public Long getVersion() {
		return version;
	}

}

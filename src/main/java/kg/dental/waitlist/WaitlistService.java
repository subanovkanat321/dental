package kg.dental.waitlist;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import kg.dental.appointment.AppointmentRequests;
import kg.dental.appointment.AppointmentService;
import kg.dental.appointment.AppointmentView;
import kg.dental.patient.PatientService;
import kg.dental.patient.PatientView;
import kg.dental.shared.ConflictException;
import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Лист ожидания. Зависит от приёмов и пациентов, а они о нём не знают: модуль можно убрать,
 * не трогая остальное.
 */
@Service
public class WaitlistService {

	private final WaitlistRepository entries;

	private final PatientService patients;

	private final AppointmentService appointments;

	private final Clock clock;

	WaitlistService(WaitlistRepository entries, PatientService patients, AppointmentService appointments, Clock clock) {
		this.entries = entries;
		this.patients = patients;
		this.appointments = appointments;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<WaitlistView> list() {
		var all = entries.findAllWithPatients();
		var upcoming = appointments.upcoming(all.stream().map(e -> e.getPatient().getId()).toList());
		return all.stream()
			.map(e -> new WaitlistView(e.getId(), PatientView.of(e.getPatient()), e.getDurationMinutes(),
					e.getCreatedAt(), upcoming.get(e.getPatient().getId())))
			.toList();
	}

	@Transactional
	public WaitlistView add(long patientId, int durationMinutes) {
		var patient = patients.require(patientId);
		if (entries.existsByPatientId(patientId)) {
			throw new ConflictException(ConflictException.ALREADY_WAITING, "Пациент уже в листе ожидания");
		}
		var entry = entries.saveAndFlush(new WaitlistEntry(patient, durationMinutes, clock.instant()));
		var upcoming = appointments.upcoming(List.of(patientId)).get(patientId);
		return new WaitlistView(entry.getId(), PatientView.of(patient), durationMinutes, entry.getCreatedAt(), upcoming);
	}

	@Transactional
	public void remove(long id) {
		entries.delete(require(id));
	}

	/**
	 * Записать в освободившееся окно и убрать из листа — одной транзакцией: время занято
	 * (409) — пациент остаётся в листе. Уже записан — приём переносится, а не дублируется.
	 */
	@Transactional
	public AppointmentView book(long id, Instant startAt) {
		var entry = require(id);
		var patientId = entry.getPatient().getId();
		var upcoming = appointments.upcoming(List.of(patientId)).get(patientId);
		var booked = upcoming == null
				? appointments.create(new AppointmentRequests.Create(patientId, startAt, entry.getDurationMinutes()))
				: appointments.reschedule(upcoming.id(),
						new AppointmentRequests.Reschedule(upcoming.version(), startAt, entry.getDurationMinutes()));
		entries.delete(entry);
		return booked;
	}

	private WaitlistEntry require(long id) {
		return entries.findById(id).orElseThrow(() -> new NotFoundException("Пациента уже нет в листе ожидания"));
	}

}

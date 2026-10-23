package kg.dental.appointment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	/**
	 * «Приём занимает время» — то же условие, что WHERE у ограничения appointment_no_overlap
	 * в V1__init.sql. Одна константа на все запросы: если сетка свободного времени разойдётся
	 * с базой, врач увидит «свободно» на время, которое база не даст занять.
	 * SlotApiTests сверяет их для каждого статуса.
	 */
	String OCCUPIES_TIME = "a.status not in (kg.dental.appointment.AppointmentStatus.CANCELLED, "
			+ "kg.dental.appointment.AppointmentStatus.NO_SHOW)";

	/**
	 * Занятое время, пересекающееся с [from, to). {@code excludeId} — приём, который переносят:
	 * своё старое время ему не мешает (ER4).
	 */
	@Query("select new kg.dental.appointment.TimeRange(a.startAt, a.endAt) from Appointment a"
			+ " where " + OCCUPIES_TIME + " and a.doctorId = :doctorId"
			+ " and a.startAt < :to and a.endAt > :from and a.id <> :excludeId")
	List<TimeRange> findBusy(long doctorId, Instant from, Instant to, long excludeId);

	/**
	 * Приёмы дня с пациентами одним запросом (T10). Без {@code join fetch} Hibernate
	 * сделал бы ещё по запросу на каждого пациента — проблема «N+1».
	 */
	@Query("""
			select a from Appointment a join fetch a.patient
			where a.doctorId = :doctorId and a.startAt >= :from and a.startAt < :to
			order by a.startAt, a.id""")
	List<Appointment> findDay(long doctorId, Instant from, Instant to);

	/** Все приёмы пациента, новые сверху — для карточки пациента. */
	@Query("""
			select a from Appointment a join fetch a.patient
			where a.patient.id = :patientId order by a.startAt desc, a.id desc""")
	List<Appointment> findByPatient(long patientId);

	/** Ближайшие запланированные приёмы и заявки этих пациентов, по возрастанию. */
	@Query("""
			select a from Appointment a join fetch a.patient
			where a.patient.id in :patientIds
			and a.status in (kg.dental.appointment.AppointmentStatus.SCHEDULED,
			                 kg.dental.appointment.AppointmentStatus.REQUESTED)
			and a.endAt > :now order by a.startAt""")
	List<Appointment> findUpcoming(Collection<Long> patientIds, Instant now);

	/** Прошедшие приёмы без отметки «пришёл» / «не пришёл», закончившиеся в [from, to). */
	@Query("""
			select count(a) from Appointment a
			where a.doctorId = :doctorId and a.status = kg.dental.appointment.AppointmentStatus.SCHEDULED
			and a.endAt >= :from and a.endAt < :to""")
	long countUnmarked(long doctorId, Instant from, Instant to);

	/** «С чистого листа»: все приёмы одним запросом, без загрузки сущностей. */
	@Modifying
	@Query(value = "delete from appointment", nativeQuery = true)
	int deleteAllAppointments();

	/**
	 * Отметка «напоминание открыто» прямым UPDATE, без {@code @Version}: её шлёт кнопка
	 * WhatsApp запросом keepalive, ответ которого Mini App не ждёт. Если бы отметка
	 * увеличивала версию, следующее действие в карточке получило бы ложное «Приём изменён».
	 *
	 * @return 1 — отмечено, 0 — приёма нет или он не запланирован
	 */
	@Modifying
	@Query("""
			update Appointment a set a.reminderOpenedAt = :now
			where a.id = :id and a.status = kg.dental.appointment.AppointmentStatus.SCHEDULED""")
	int markReminderOpened(long id, Instant now);

}

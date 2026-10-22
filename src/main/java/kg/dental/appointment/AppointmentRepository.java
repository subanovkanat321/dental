package kg.dental.appointment;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	/**
	 * Приёмы дня с пациентами одним запросом (T10). Без {@code join fetch} Hibernate
	 * сделал бы ещё по запросу на каждого пациента — проблема «N+1».
	 */
	@Query("""
			select a from Appointment a join fetch a.patient
			where a.startAt >= :from and a.startAt < :to
			order by a.startAt, a.id""")
	List<Appointment> findDay(Instant from, Instant to);

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

package kg.dental.waitlist;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

	/** Кто дольше ждёт — тот первый. */
	@Query("select w from WaitlistEntry w join fetch w.patient order by w.createdAt, w.id")
	List<WaitlistEntry> findAllWithPatients();

	boolean existsByPatientId(long patientId);

}

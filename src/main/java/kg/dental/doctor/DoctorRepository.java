package kg.dental.doctor;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface DoctorRepository extends JpaRepository<Doctor, Long> {

	List<Doctor> findAllByOrderByActiveDescIdAsc();

	List<Doctor> findByActiveTrueOrderByIdAsc();

	Optional<Doctor> findByTelegramIdAndActiveTrue(long telegramId);

	boolean existsByTelegramIdAndIdNot(long telegramId, long id);

	boolean existsByTelegramId(long telegramId);

}

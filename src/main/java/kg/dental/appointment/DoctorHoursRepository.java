package kg.dental.appointment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface DoctorHoursRepository extends JpaRepository<DoctorHours, DoctorHours.Key> {

	List<DoctorHours> findByDoctorIdOrderByWeekday(long doctorId);

	@Modifying
	@Query("delete from DoctorHours h where h.doctorId = :doctorId")
	void deleteByDoctor(long doctorId);

}

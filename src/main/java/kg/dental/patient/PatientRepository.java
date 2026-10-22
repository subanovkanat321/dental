package kg.dental.patient;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface PatientRepository extends JpaRepository<Patient, Long> {

	List<Patient> findByPhone(String phone);

	// Containing экранирует % и _ из ввода: поиск «50%» не станет шаблоном LIKE
	List<Patient> findTop20ByPhoneContainingOrderByFullName(String digits);

	List<Patient> findTop20ByFullNameContainingIgnoreCaseOrderByFullName(String text);

}

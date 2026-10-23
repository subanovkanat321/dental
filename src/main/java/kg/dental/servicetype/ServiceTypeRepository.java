package kg.dental.servicetype;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {

	List<ServiceType> findAllByOrderByActiveDescNameAsc();

	List<ServiceType> findByActiveTrueOrderByNameAsc();

}

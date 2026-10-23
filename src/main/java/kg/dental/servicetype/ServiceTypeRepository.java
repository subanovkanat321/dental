package kg.dental.servicetype;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {

	List<ServiceType> findAllByOrderByActiveDescNameAsc();

	List<ServiceType> findByActiveTrueOrderByNameAsc();

	/** Сколько приёмов с этой услугой — тогда не удаляем, а скрываем: история и выручка остаются. */
	@Query(value = "select count(*) from appointment where service_type_id = :id", nativeQuery = true)
	long usage(long id);

}

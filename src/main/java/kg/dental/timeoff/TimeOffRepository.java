package kg.dental.timeoff;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface TimeOffRepository extends JpaRepository<TimeOff, Long> {

	/** Еженедельные первыми (по дню недели), потом разовые по дате. */
	List<TimeOff> findAllByOrderByWeekdayAscFirstDayAscStartTimeAsc();

}

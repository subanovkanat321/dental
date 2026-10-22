package kg.dental.appointment;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkScheduleTests {

	@Test
	void parsesHoursAndDayRange() {
		var schedule = WorkSchedule.parse("09:00-18:00", "MON-SAT");

		assertThat(schedule.start()).isEqualTo(LocalTime.of(9, 0));
		assertThat(schedule.end()).isEqualTo(LocalTime.of(18, 0));
		assertThat(schedule.days()).hasSize(6).doesNotContain(SUNDAY);
		assertThat(schedule.isWorkingDay(LocalDate.of(2026, 10, 25))).isFalse(); // воскресенье
		assertThat(schedule.isWorkingDay(LocalDate.of(2026, 10, 24))).isTrue(); // суббота
	}

	@Test
	void parsesListAndWholeWeek() {
		assertThat(WorkSchedule.parse(" 08:30 - 14:00 ", "mon, wed ,FRI").days()).containsExactlyInAnyOrder(MONDAY,
				WEDNESDAY, FRIDAY);
		assertThat(WorkSchedule.parse("09:00-18:00", "MON-SUN").days()).containsExactlyInAnyOrder(DayOfWeek.values());
		assertThat(WorkSchedule.parse("09:00-18:00", "SAT").days()).containsExactly(SATURDAY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "9-18", "18:00-09:00", "09:00-09:00", "09:00", "09:00-18:00-19:00", "abc" })
	void rejectsBadHours(String hours) {
		assertThatThrownBy(() -> WorkSchedule.parse(hours, "MON-SUN")).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "SUN-MON", "MONDAY", "M", "XYZ", "MON-TUE-WED", "" })
	void rejectsBadDays(String days) {
		assertThatThrownBy(() -> WorkSchedule.parse("09:00-18:00", days)).isInstanceOf(IllegalArgumentException.class);
	}

}

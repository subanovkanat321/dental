package kg.dental.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Время в тестах — UTC «HH:mm» одного дня; рабочий день 09:00–18:00. */
class FreeTimeTests {

	static final Instant FROM = at("09:00");

	static final Instant TO = at("18:00");

	static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

	@Test
	void emptyDayIsOneWindow() {
		assertThat(FreeTime.free(FROM, TO, List.of(), LONG_AGO)).containsExactly(range("09:00", "18:00"));
	}

	@Test
	void appointmentsCutWindowsBackToBackLeavesNoGap() {
		var busy = List.of(range("10:00", "10:30"), range("10:30", "11:00"), range("14:00", "15:00"));

		assertThat(FreeTime.free(FROM, TO, busy, LONG_AGO))
			.containsExactly(range("09:00", "10:00"), range("11:00", "14:00"), range("15:00", "18:00"));
	}

	@Test
	void overlappingAndUnsortedBusyIsMerged() {
		var busy = List.of(range("12:00", "13:00"), range("10:00", "12:30"));

		assertThat(FreeTime.free(FROM, TO, busy, LONG_AGO)).containsExactly(range("09:00", "10:00"), range("13:00", "18:00"));
	}

	@Test
	void appointmentsOutsideWorkHoursCutTheEdges() {
		var busy = List.of(range("08:30", "09:30"), range("17:45", "19:00"));

		assertThat(FreeTime.free(FROM, TO, busy, LONG_AGO)).containsExactly(range("09:30", "17:45"));
	}

	@Test
	void gapShorterThan15MinutesIsNotFree() {
		var busy = List.of(range("10:00", "10:30"), range("10:40", "11:00"));

		assertThat(FreeTime.free(FROM, TO, busy, LONG_AGO)).containsExactly(range("09:00", "10:00"), range("11:00", "18:00"));
	}

	@Test
	void pastPartOfTodayIsNotFreeAndNowIsRoundedUpToQuarter() {
		assertThat(FreeTime.free(FROM, TO, List.of(), at("10:07"))).containsExactly(range("10:15", "18:00"));
		assertThat(FreeTime.free(FROM, TO, List.of(), at("10:15"))).containsExactly(range("10:15", "18:00"));
		assertThat(FreeTime.free(FROM, TO, List.of(), at("18:30"))).isEmpty();
	}

	@Test
	void startsFitWholeDurationOnQuarterGrid() {
		var free = List.of(range("09:00", "10:00"), range("10:40", "11:30"));

		assertThat(FreeTime.starts(free, Duration.ofMinutes(30)))
			.containsExactly(at("09:00"), at("09:15"), at("09:30"), at("10:45"), at("11:00"));
	}

	@Test
	void durationLongerThanEveryWindowGivesNoStarts() {
		assertThat(FreeTime.starts(List.of(range("09:00", "10:00")), Duration.ofMinutes(90))).isEmpty();
	}

	@Test
	void fullEmptyDayHas35StartsFor30Minutes() {
		assertThat(FreeTime.starts(List.of(range("09:00", "18:00")), Duration.ofMinutes(30))).hasSize(35)
			.startsWith(at("09:00"))
			.endsWith(at("17:30"));
	}

	private static Instant at(String time) {
		return Instant.parse("2099-06-01T" + time + ":00Z");
	}

	private static TimeRange range(String from, String to) {
		return new TimeRange(at(from), at(to));
	}

}

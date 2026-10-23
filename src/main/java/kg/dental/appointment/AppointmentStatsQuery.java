package kg.dental.appointment;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Отчёт по неделям — обычным SQL, а не через JPA: здесь нужны не сущности, а агрегаты
 * (count ... filter, группировка по неделе в часовом поясе Бишкека). JPA для этого пришлось
 * бы обходить, а SQL читается как есть.
 */
@Repository
class AppointmentStatsQuery {

	private final JdbcClient jdbc;

	AppointmentStatsQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Приёмы по дням с {@code from} на {@code days} дней вперёд (по Бишкеку); пустые дни — нулями. */
	List<DayCount> days(LocalDate from, int days, ZoneId zone) {
		var to = from.plusDays(days);
		var byDay = new HashMap<LocalDate, DayCount>();
		jdbc.sql("""
				select (start_at at time zone :zone)::date as day,
				       count(*) filter (where status <> 'CANCELLED') as booked,
				       count(*) filter (where status = 'REQUESTED') as requested
				from appointment
				where start_at >= :from and start_at < :to
				group by 1""")
			.param("zone", zone.getId())
			.param("from", Timestamp.from(from.atStartOfDay(zone).toInstant()))
			.param("to", Timestamp.from(to.atStartOfDay(zone).toInstant()))
			.query((rs, n) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("booked"),
					rs.getLong("requested")))
			.list()
			.forEach(c -> byDay.put(c.date(), c));
		return from.datesUntil(to).map(d -> byDay.getOrDefault(d, new DayCount(d, 0, 0))).toList();
	}

	/** Последние {@code weeks} недель по Бишкеку, текущая первой; недели без приёмов — нулями. */
	List<WeekStats> weeks(int weeks, Instant now, ZoneId zone) {
		var thisWeek = LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		var first = thisWeek.minusWeeks(weeks - 1L);
		var rows = jdbc.sql("""
				select (date_trunc('week', start_at at time zone :zone))::date as week,
				       count(*) filter (where status <> 'CANCELLED') as booked,
				       count(*) filter (where status = 'DONE') as done,
				       count(*) filter (where status = 'NO_SHOW') as no_show,
				       count(*) filter (where status = 'CANCELLED') as cancelled,
				       count(*) filter (where status = 'SCHEDULED' and end_at < :now) as unmarked,
				       count(*) filter (where status <> 'CANCELLED'
				                        and (reminder_opened_at is not null or reply <> 'NONE')) as reminded
				from appointment
				where start_at >= :from and start_at < :to
				group by 1""")
			.param("zone", zone.getId())
			.param("now", Timestamp.from(now))
			.param("from", Timestamp.from(first.atStartOfDay(zone).toInstant()))
			.param("to", Timestamp.from(thisWeek.plusWeeks(1).atStartOfDay(zone).toInstant()))
			.query((rs, n) -> new WeekStats(rs.getObject("week", LocalDate.class), rs.getLong("booked"),
					rs.getLong("done"), rs.getLong("no_show"), rs.getLong("cancelled"), rs.getLong("unmarked"),
					rs.getLong("reminded")))
			.list();
		var byWeek = new HashMap<LocalDate, WeekStats>();
		rows.forEach(r -> byWeek.put(r.weekStart(), r));
		var result = new ArrayList<WeekStats>();
		for (var week = thisWeek; !week.isBefore(first); week = week.minusWeeks(1)) {
			result.add(byWeek.getOrDefault(week, WeekStats.empty(week)));
		}
		return result;
	}

}

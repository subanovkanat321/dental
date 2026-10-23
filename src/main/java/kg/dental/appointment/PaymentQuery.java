package kg.dental.appointment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Касса — SQL, как и остальные отчёты: оплата ставится прямым UPDATE (без @Version — правка
 * суммы не должна давать врачу «Приём изменён»), выручка — агрегатами.
 */
@Repository
class PaymentQuery {

	/** Строка отчёта для бухгалтерии: без имени и телефона пациента. */
	record ExportRow(long id, Instant startAt, String doctor, String service, String status, Integer paidSom,
			String method) {
	}

	private final JdbcClient jdbc;

	PaymentQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return 1 — записано, 0 — приёма нет или он не состоялся */
	int set(long id, Integer amountSom, PaymentMethod method, Instant now) {
		return jdbc.sql("""
				update appointment set paid_som = :amount, payment_method = :method, paid_at = :now
				where id = :id and status = 'DONE'""")
			.param("amount", amountSom)
			.param("method", method == null ? null : method.name())
			.param("now", amountSom == null ? null : Timestamp.from(now))
			.param("id", id)
			.update();
	}

	RevenueView revenue(LocalDate from, LocalDate to, ZoneId zone, Long doctorId) {
		var start = Timestamp.from(from.atStartOfDay(zone).toInstant());
		var end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
		var where = """
				from appointment where status = 'DONE' and start_at >= :from and start_at < :to
				  and (cast(:doctorId as bigint) is null or doctor_id = :doctorId)""";
		var byMethod = new EnumMap<PaymentMethod, Long>(PaymentMethod.class);
		for (var m : PaymentMethod.values()) {
			byMethod.put(m, 0L);
		}
		jdbc.sql("select payment_method, coalesce(sum(paid_som), 0) as total " + where
				+ " and payment_method is not null group by payment_method")
			.param("from", start).param("to", end).param("doctorId", doctorId)
			.query((rs, n) -> byMethod.put(PaymentMethod.valueOf(rs.getString("payment_method")), rs.getLong("total")))
			.list();
		var counts = jdbc.sql("select count(paid_som) as paid, count(*) - count(paid_som) as unpaid " + where)
			.param("from", start).param("to", end).param("doctorId", doctorId)
			.query((rs, n) -> new long[] { rs.getLong("paid"), rs.getLong("unpaid") })
			.single();
		var days = jdbc.sql("select (start_at at time zone :zone)::date as day, sum(paid_som) as total " + where
				+ " and paid_som is not null group by 1 order by 1")
			.param("zone", zone.getId()).param("from", start).param("to", end).param("doctorId", doctorId)
			.query((rs, n) -> new RevenueView.Day(rs.getObject("day", LocalDate.class), rs.getLong("total")))
			.list();
		var total = byMethod.values().stream().mapToLong(Long::longValue).sum();
		return new RevenueView(from, to, total, counts[0], counts[1], byMethod, days);
	}

	/** Все приёмы периода (кроме заявок) — для отчёта в Excel. */
	List<ExportRow> export(LocalDate from, LocalDate to, ZoneId zone, Long doctorId) {
		return jdbc.sql("""
				select a.id, a.start_at, d.full_name as doctor, s.name as service, a.status, a.paid_som, a.payment_method
				from appointment a
				join doctor d on d.id = a.doctor_id
				left join service_type s on s.id = a.service_type_id
				where a.start_at >= :from and a.start_at < :to and a.status <> 'REQUESTED'
				  and (cast(:doctorId as bigint) is null or a.doctor_id = :doctorId)
				order by a.start_at, a.id""")
			.param("from", Timestamp.from(from.atStartOfDay(zone).toInstant()))
			.param("to", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
			.param("doctorId", doctorId)
			.query((rs, n) -> new ExportRow(rs.getLong("id"), rs.getTimestamp("start_at").toInstant(), rs.getString("doctor"),
					rs.getString("service"), rs.getString("status"), (Integer) rs.getObject("paid_som"),
					rs.getString("payment_method")))
			.list();
	}

}

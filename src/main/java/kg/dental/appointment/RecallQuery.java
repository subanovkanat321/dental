package kg.dental.appointment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import kg.dental.patient.PatientView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * «Пора позвать на профосмотр»: последний визит («Пришёл») раньше {@code before}, а впереди
 * ничего не запланировано. Кто ни разу не приходил, сюда не попадает. Сначала те, кого ещё не
 * звали, потом — кто дольше не был. Отчёт обычным SQL, как AppointmentStatsQuery.
 */
@Repository
class RecallQuery {

	private final JdbcClient jdbc;

	RecallQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<RecallView> due(Instant before, Instant now) {
		return jdbc.sql("""
				select p.id, p.full_name, p.phone, p.telegram_id, p.recall_invited_at, max(a.start_at) as last_visit
				from patient p
				join appointment a on a.patient_id = p.id and a.status = 'DONE'
				where not exists (
				    select 1 from appointment f
				    where f.patient_id = p.id and f.status = 'SCHEDULED' and f.end_at > :now)
				group by p.id
				having max(a.start_at) < :before
				order by p.recall_invited_at nulls first, last_visit""")
			.param("before", Timestamp.from(before))
			.param("now", Timestamp.from(now))
			.query((rs, n) -> new RecallView(
					new PatientView(rs.getLong("id"), rs.getString("full_name"), rs.getString("phone"),
							rs.getObject("telegram_id") != null),
					rs.getTimestamp("last_visit").toInstant(), instant(rs.getTimestamp("recall_invited_at"))))
			.list();
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

}

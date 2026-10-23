package kg.dental.appointment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * «Забрать» приёмы, о которых пора предупредить врача: одним UPDATE ... RETURNING. Отметка
 * ставится и строки возвращаются атомарно — второй запуск (или перезапуск сервера) те же
 * приёмы уже не получит, и уведомление не придёт дважды.
 */
@Repository
class PreNoticeQuery {

	private final JdbcClient jdbc;

	PreNoticeQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Запланированные приёмы, которые начнутся в (now, until] и о которых ещё не предупреждали. */
	List<PreNotice> claim(Instant now, Instant until) {
		return jdbc.sql("""
				update appointment set pre_notified_at = :now
				where status = 'SCHEDULED' and pre_notified_at is null
				  and start_at > :now and start_at <= :until
				returning id, doctor_id, start_at, end_at, reply, reminder_opened_at""")
			.param("now", Timestamp.from(now))
			.param("until", Timestamp.from(until))
			.query((rs, n) -> new PreNotice(rs.getLong("id"), rs.getLong("doctor_id"), rs.getTimestamp("start_at").toInstant(),
					rs.getTimestamp("end_at").toInstant(), PatientReply.valueOf(rs.getString("reply")),
					rs.getTimestamp("reminder_opened_at") == null ? null
							: rs.getTimestamp("reminder_opened_at").toInstant()))
			.list();
	}

	/** Перенесли — о новом времени нужно предупредить заново. */
	void reset(long appointmentId) {
		jdbc.sql("update appointment set pre_notified_at = null where id = :id").param("id", appointmentId).update();
	}

}

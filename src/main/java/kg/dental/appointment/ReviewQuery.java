package kg.dental.appointment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Отзывы после приёма — SQL, как и PreNoticeQuery: «забрать» приёмы, о которых пора спросить,
 * одним UPDATE ... RETURNING, чтобы повторный запуск не спросил пациента второй раз.
 */
@Repository
class ReviewQuery {

	private final JdbcClient jdbc;

	ReviewQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Пришедшие в [from, to), записавшиеся через бота и ещё не спрошенные. */
	List<ReviewRequest> claim(Instant from, Instant to, Instant now) {
		return jdbc.sql("""
				update appointment a set review_asked_at = :now
				from patient p
				where p.id = a.patient_id and p.telegram_id is not null
				  and a.status = 'DONE' and a.review_asked_at is null
				  and a.start_at >= :from and a.start_at < :to
				returning a.id, a.start_at, p.telegram_id, p.full_name""")
			.param("now", Timestamp.from(now))
			.param("from", Timestamp.from(from))
			.param("to", Timestamp.from(to))
			.query((rs, n) -> new ReviewRequest(rs.getLong("id"), rs.getTimestamp("start_at").toInstant(),
					rs.getLong("telegram_id"), rs.getString("full_name")))
			.list();
	}

	/** Оценённый приём: когда был и у какого врача — для уведомления врачу. */
	public record Rated(Instant startAt, long doctorId) {
	}

	/**
	 * Оценка кнопкой: только свой приём (тот же Telegram id), только если спрашивали и ещё не
	 * оценил. Версию приёма не меняет: оценка — не правка врача, его экран не устареет.
	 *
	 * @return приём — для уведомления врачу; пусто — оценку не приняли
	 */
	Optional<Rated> rate(long appointmentId, long telegramId, int rating) {
		return jdbc.sql("""
				update appointment a set rating = :rating
				from patient p
				where a.id = :id and p.id = a.patient_id and p.telegram_id = :telegramId
				  and a.status = 'DONE' and a.review_asked_at is not null and a.rating is null
				returning a.start_at, a.doctor_id""")
			.param("rating", rating)
			.param("id", appointmentId)
			.param("telegramId", telegramId)
			.query((rs, n) -> new Rated(rs.getTimestamp("start_at").toInstant(), rs.getLong("doctor_id")))
			.optional();
	}

	/** @param doctorId null — весь кабинет */
	RatingSummary summary(Instant since, Long doctorId) {
		return jdbc.sql("""
				select count(rating) as count, avg(rating) as average,
				       count(*) filter (where rating = 1) as r1, count(*) filter (where rating = 2) as r2,
				       count(*) filter (where rating = 3) as r3, count(*) filter (where rating = 4) as r4,
				       count(*) filter (where rating = 5) as r5
				from appointment where rating is not null and start_at >= :since
				  and (cast(:doctorId as bigint) is null or doctor_id = :doctorId)""")
			.param("since", Timestamp.from(since))
			.param("doctorId", doctorId)
			.query((rs, n) -> new RatingSummary(rs.getLong("count"),
					rs.getBigDecimal("average") == null ? null : rs.getBigDecimal("average").setScale(1, RoundingMode.HALF_UP),
					List.of(rs.getLong("r1"), rs.getLong("r2"), rs.getLong("r3"), rs.getLong("r4"), rs.getLong("r5"))))
			.single();
	}

}

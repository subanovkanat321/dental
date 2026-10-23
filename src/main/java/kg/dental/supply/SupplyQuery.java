package kg.dental.supply;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Склад — обычным SQL. Остаток меняется одним UPDATE с условием «не уйти в минус»: два врача
 * одновременно списали последнюю карпулу — у второго будет «столько нет», а не −1 на складе.
 */
@Repository
class SupplyQuery {

	/** Остаток до и после движения — чтобы понять, перешли ли порог. */
	record Moved(int before, int after, int minQuantity, String name, String unit) {
	}

	private final JdbcClient jdbc;

	SupplyQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<SupplyView> all() {
		return jdbc.sql("select id, name, unit, quantity, min_quantity from supply order by lower(name)")
			.query((rs, n) -> view(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getInt("quantity"),
					rs.getInt("min_quantity")))
			.list();
	}

	Optional<SupplyView> get(long id) {
		return jdbc.sql("select id, name, unit, quantity, min_quantity from supply where id = :id")
			.param("id", id)
			.query((rs, n) -> view(rs.getLong("id"), rs.getString("name"), rs.getString("unit"), rs.getInt("quantity"),
					rs.getInt("min_quantity")))
			.optional();
	}

	long create(String name, String unit, int quantity, int minQuantity) {
		return jdbc.sql("""
				insert into supply (name, unit, quantity, min_quantity) values (:name, :unit, :quantity, :min) returning id""")
			.param("name", name).param("unit", unit).param("quantity", quantity).param("min", minQuantity)
			.query(Long.class)
			.single();
	}

	int update(long id, String name, String unit, int minQuantity) {
		return jdbc.sql("update supply set name = :name, unit = :unit, min_quantity = :min where id = :id")
			.param("id", id).param("name", name).param("unit", unit).param("min", minQuantity)
			.update();
	}

	int delete(long id) {
		return jdbc.sql("delete from supply where id = :id").param("id", id).update();
	}

	boolean nameTaken(String name, long exceptId) {
		return jdbc.sql("select exists (select 1 from supply where lower(name) = lower(:name) and id <> :id)")
			.param("name", name).param("id", exceptId)
			.query(Boolean.class)
			.single();
	}

	/** @return пусто — материала нет или столько на складе нет (остаток ушёл бы в минус) */
	Optional<Moved> move(long id, int delta, String reason, Long doctorId, Instant now) {
		var moved = jdbc.sql("""
				update supply set quantity = quantity + :delta
				where id = :id and quantity + :delta >= 0
				returning quantity - :delta as before, quantity as after, min_quantity, name, unit""")
			.param("id", id).param("delta", delta)
			.query((rs, n) -> new Moved(rs.getInt("before"), rs.getInt("after"), rs.getInt("min_quantity"),
					rs.getString("name"), rs.getString("unit")))
			.optional();
		moved.ifPresent(m -> jdbc.sql("""
				insert into supply_movement (supply_id, delta, reason, doctor_id, created_at)
				values (:id, :delta, :reason, :doctorId, :now)""")
			.param("id", id).param("delta", delta).param("reason", reason).param("doctorId", doctorId)
			.param("now", Timestamp.from(now))
			.update());
		return moved;
	}

	List<MovementView> history(long id) {
		return jdbc.sql("""
				select m.delta, m.reason, d.full_name, m.created_at
				from supply_movement m left join doctor d on d.id = m.doctor_id
				where m.supply_id = :id order by m.created_at desc, m.id desc limit 50""")
			.param("id", id)
			.query((rs, n) -> new MovementView(rs.getInt("delta"), rs.getString("reason"), rs.getString("full_name"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	private static SupplyView view(long id, String name, String unit, int quantity, int min) {
		return new SupplyView(id, name, unit, quantity, min, quantity <= min);
	}

}

package kg.dental.supply;

import java.time.Clock;
import java.util.List;

import kg.dental.digest.DoctorNotifier;
import kg.dental.shared.ConflictException;
import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Склад расходников: остатки, списание и поставки, сигнал «заканчивается». */
@Service
public class SupplyService {

	/** Списали больше, чем есть. */
	static final String NOT_ENOUGH = "not-enough";

	private final SupplyQuery query;

	private final DoctorNotifier notifier;

	private final Clock clock;

	SupplyService(SupplyQuery query, DoctorNotifier notifier, Clock clock) {
		this.query = query;
		this.notifier = notifier;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<SupplyView> all() {
		return query.all();
	}

	@Transactional(readOnly = true)
	public List<MovementView> history(long id) {
		require(id);
		return query.history(id);
	}

	@Transactional
	public SupplyView create(String name, String unit, Integer quantity, Integer minQuantity, Long doctorId) {
		var cleanName = name(name, 0);
		var start = count(quantity, "quantity");
		var id = query.create(cleanName, unit(unit), 0, count(minQuantity, "minQuantity"));
		if (start > 0) {
			query.move(id, start, "DELIVERY", doctorId, clock.instant());
		}
		return require(id);
	}

	@Transactional
	public SupplyView update(long id, String name, String unit, Integer minQuantity) {
		require(id);
		query.update(id, name(name, id), unit(unit), count(minQuantity, "minQuantity"));
		return require(id);
	}

	@Transactional
	public void delete(long id) {
		if (query.delete(id) == 0) {
			throw new NotFoundException("Материал не найден");
		}
	}

	/**
	 * Списать (delta < 0), поставка (delta > 0) или поправка после пересчёта. Перешли порог вниз —
	 * всем врачам и админам в бот: «⚠️ Заканчивается …». Один раз, при переходе, а не на каждом −1.
	 */
	@Transactional
	public SupplyView move(long id, int delta, String reason, Long doctorId) {
		if (delta == 0 || Math.abs(delta) > 100_000) {
			throw new InvalidFieldException("delta", "Сколько — целое число, не 0");
		}
		var kind = reason == null ? (delta < 0 ? "USE" : "DELIVERY") : reason;
		if (!List.of("USE", "DELIVERY", "CORRECTION").contains(kind)) {
			throw new InvalidFieldException("reason", "Неизвестная причина");
		}
		var moved = query.move(id, delta, kind, doctorId, clock.instant()).orElseThrow(() -> {
			var current = require(id);
			return new ConflictException(NOT_ENOUGH, "На складе только " + current.quantity() + " " + current.unit());
		});
		if (moved.before() > moved.minQuantity() && moved.after() <= moved.minQuantity() && notifier.enabled()) {
			notifier.sendToStaff("Склад", lowText(moved), "supply=" + id);
		}
		return require(id);
	}

	static String lowText(SupplyQuery.Moved m) {
		return m.after() == 0 ? "⚠️ Закончился материал: " + m.name() + ". Пора заказать."
				: "⚠️ Заканчивается: " + m.name() + " — осталось " + m.after() + " " + m.unit() + ". Пора заказать.";
	}

	private SupplyView require(long id) {
		return query.get(id).orElseThrow(() -> new NotFoundException("Материал не найден"));
	}

	private String name(String name, long exceptId) {
		var clean = name == null ? "" : name.strip();
		if (clean.isEmpty() || clean.length() > 100) {
			throw new InvalidFieldException("name", "Название — до 100 символов");
		}
		if (query.nameTaken(clean, exceptId)) {
			throw new InvalidFieldException("name", "Такой материал уже есть");
		}
		return clean;
	}

	private static String unit(String unit) {
		var clean = unit == null || unit.isBlank() ? "шт" : unit.strip();
		if (clean.length() > 10) {
			throw new InvalidFieldException("unit", "Единица — коротко: шт, уп, мл");
		}
		return clean;
	}

	private static int count(Integer value, String field) {
		if (value == null) {
			return 0;
		}
		if (value < 0 || value > 1_000_000) {
			throw new InvalidFieldException(field, "Целое число от 0");
		}
		return value;
	}

}

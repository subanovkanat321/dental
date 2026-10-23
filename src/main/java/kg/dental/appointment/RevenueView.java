package kg.dental.appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Выручка за период — целые сомы (long: сумма за год не влезет в int только у очень большой клиники,
 * но считать в long ничего не стоит).
 *
 * @param paidCount сколько состоявшихся приёмов оплачено
 * @param unpaidCount сколько состоявшихся приёмов без отметки оплаты — подсказка «не забудьте»
 */
public record RevenueView(LocalDate from, LocalDate to, long totalSom, long paidCount, long unpaidCount,
		Map<PaymentMethod, Long> byMethod, List<Day> days) {

	public record Day(LocalDate date, long totalSom) {
	}

}

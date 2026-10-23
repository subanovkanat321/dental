package kg.dental.appointment;

import java.math.BigDecimal;
import java.util.List;

/**
 * Оценки пациентов за период.
 *
 * @param average средняя, один знак после запятой; null — оценок нет
 * @param byStars сколько оценок 1, 2, 3, 4 и 5
 */
public record RatingSummary(long count, BigDecimal average, List<Long> byStars) {
}

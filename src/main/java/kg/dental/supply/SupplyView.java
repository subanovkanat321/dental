package kg.dental.supply;

/**
 * Материал на складе.
 *
 * @param low остаток на пороге или ниже — пора заказывать
 */
public record SupplyView(long id, String name, String unit, int quantity, int minQuantity, boolean low) {
}

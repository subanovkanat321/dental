package kg.dental.servicetype;

/**
 * Услуга в ответах API.
 *
 * @param priceSom цена «от» в целых сомах; null — не показывать
 */
public record ServiceTypeView(long id, String name, int durationMinutes, boolean active, Integer priceSom) {

	static ServiceTypeView of(ServiceType s) {
		return new ServiceTypeView(s.getId(), s.getName(), s.getDurationMinutes(), s.isActive(), s.getPriceSom());
	}

}

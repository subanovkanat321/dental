package kg.dental.servicetype;

/** Услуга в ответах API. */
public record ServiceTypeView(long id, String name, int durationMinutes, boolean active) {

	static ServiceTypeView of(ServiceType s) {
		return new ServiceTypeView(s.getId(), s.getName(), s.getDurationMinutes(), s.isActive());
	}

}

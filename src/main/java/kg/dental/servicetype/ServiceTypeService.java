package kg.dental.servicetype;

import java.time.Clock;
import java.util.List;

import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceTypeService {

	private final ServiceTypeRepository services;

	private final Clock clock;

	ServiceTypeService(ServiceTypeRepository services, Clock clock) {
		this.services = services;
		this.clock = clock;
	}

	/** Для врача: все, сначала активные. */
	@Transactional(readOnly = true)
	public List<ServiceTypeView> all() {
		return services.findAllByOrderByActiveDescNameAsc().stream().map(ServiceTypeView::of).toList();
	}

	/** Для пациента: только активные. */
	@Transactional(readOnly = true)
	public List<ServiceTypeView> active() {
		return services.findByActiveTrueOrderByNameAsc().stream().map(ServiceTypeView::of).toList();
	}

	@Transactional
	public ServiceTypeView create(String name, int durationMinutes, Integer priceSom) {
		return ServiceTypeView.of(services.save(new ServiceType(name.strip(), durationMinutes, priceSom, clock.instant())));
	}

	@Transactional
	public ServiceTypeView update(long id, String name, int durationMinutes, Integer priceSom, boolean active) {
		var service = require(id);
		service.update(name.strip(), durationMinutes, priceSom, active);
		return ServiceTypeView.of(service);
	}

	/**
	 * Удалить услугу. По ней уже были записи — не удаляем, а скрываем: у прошлых приёмов
	 * остаётся название, в отчётах — выручка.
	 *
	 * @return true — удалена, false — скрыта
	 */
	@Transactional
	public boolean delete(long id) {
		var service = require(id);
		if (services.usage(id) > 0) {
			service.update(service.getName(), service.getDurationMinutes(), service.getPriceSom(), false);
			return false;
		}
		services.delete(service);
		return true;
	}

	/** Услуга, доступная пациенту: скрытую выбрать нельзя. */
	@Transactional(readOnly = true)
	public ServiceType requireActive(long id) {
		var service = require(id);
		if (!service.isActive()) {
			throw new NotFoundException("Услуга не найдена");
		}
		return service;
	}

	@Transactional(readOnly = true)
	public ServiceType require(long id) {
		return services.findById(id).orElseThrow(() -> new NotFoundException("Услуга не найдена"));
	}

}

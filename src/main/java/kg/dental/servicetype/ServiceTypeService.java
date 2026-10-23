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
	public ServiceTypeView create(String name, int durationMinutes) {
		return ServiceTypeView.of(services.save(new ServiceType(name.strip(), durationMinutes, clock.instant())));
	}

	@Transactional
	public ServiceTypeView update(long id, String name, int durationMinutes, boolean active) {
		var service = require(id);
		service.update(name.strip(), durationMinutes, active);
		return ServiceTypeView.of(service);
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

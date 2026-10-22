package kg.dental.web;

import kg.dental.DentalProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Настройки, нужные Mini App: подпись для текста напоминания (DD12). */
@RestController
class SettingsController {

	record Settings(String clinicSignature) {
	}

	private final DentalProperties properties;

	SettingsController(DentalProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/api/settings")
	Settings settings() {
		var signature = properties.clinicSignature();
		return new Settings(signature == null ? "" : signature.strip());
	}

}

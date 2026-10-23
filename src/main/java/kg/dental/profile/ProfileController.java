package kg.dental.profile;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Профиль врача: {@code /api/profile} — только врач. Само фото — {@code /profile/photo} без входа:
 * его показывает тег img на экране пациента, а он не умеет слать заголовок Authorization. Фото
 * не секрет: его и так видит любой, кто открыл запись.
 */
@RestController
class ProfileController {

	record Request(String doctorName, String about) {
	}

	private final ProfileService service;

	ProfileController(ProfileService service) {
		this.service = service;
	}

	@GetMapping("/api/profile")
	ProfileView view() {
		return service.view();
	}

	@PutMapping("/api/profile")
	ProfileView update(@RequestBody Request request) {
		return service.update(request.doctorName(), request.about());
	}

	@PostMapping("/api/profile/photo")
	ProfileView upload(@RequestParam("photo") MultipartFile photo) throws IOException {
		return service.setPhoto(photo.getBytes());
	}

	@DeleteMapping("/api/profile/photo")
	ProfileView deletePhoto() {
		return service.deletePhoto();
	}

	/** Адрес с версией (?v=N) меняется с каждым новым фото — поэтому кэшировать можно на год. */
	@GetMapping("/profile/photo")
	ResponseEntity<byte[]> photo() {
		return service.photo()
			.map(p -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(p.type()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.header("X-Content-Type-Options", "nosniff")
				.body(p.bytes()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

}

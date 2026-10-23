package kg.dental.doctor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.shared.ForbiddenException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Врачи. {@code /api/doctors} — сотрудники (вход проверил InitDataAuthFilter). Заводить врачей
 * и привязывать Telegram — только админ; профиль и фото — админ или сам врач. Фото —
 * {@code /doctors/{id}/photo} без входа: его показывает тег img у пациента.
 */
@RestController
class DoctorController {

	record AdminRequest(String fullName, String about, Long telegramId, Boolean active) {
	}

	record ProfileRequest(String fullName, String about) {
	}

	private final DoctorService service;

	private final Staff staff;

	DoctorController(DoctorService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	@GetMapping("/api/doctors")
	List<DoctorView> all() {
		return service.all();
	}

	@GetMapping("/api/doctors/admin")
	List<DoctorAdminView> adminList(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me) {
		requireAdmin(me);
		return service.adminList();
	}

	@PostMapping("/api/doctors")
	@ResponseStatus(HttpStatus.CREATED)
	DoctorAdminView create(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody AdminRequest r) {
		requireAdmin(me);
		return service.create(r.fullName(), r.about(), r.telegramId());
	}

	@PutMapping("/api/doctors/{id}")
	DoctorAdminView update(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@RequestBody AdminRequest r) {
		requireAdmin(me);
		return service.update(id, r.fullName(), r.about(), r.telegramId(), r.active() == null || r.active());
	}

	@PutMapping("/api/doctors/{id}/profile")
	DoctorView profile(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@RequestBody ProfileRequest r) {
		requireSelfOrAdmin(me, id);
		return service.updateProfile(id, r.fullName(), r.about());
	}

	@PostMapping("/api/doctors/{id}/photo")
	DoctorView upload(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@RequestParam("photo") MultipartFile photo) throws IOException {
		requireSelfOrAdmin(me, id);
		return service.setPhoto(id, photo.getBytes());
	}

	@DeleteMapping("/api/doctors/{id}/photo")
	DoctorView deletePhoto(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		requireSelfOrAdmin(me, id);
		return service.deletePhoto(id);
	}

	/** Адрес с версией (?v=N) меняется с каждым новым фото — поэтому кэшировать можно на год. */
	@GetMapping("/doctors/{id}/photo")
	ResponseEntity<byte[]> photo(@PathVariable long id) {
		return service.photo(id)
			.map(p -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(p.type()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.header("X-Content-Type-Options", "nosniff")
				.body(p.bytes()))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private void requireAdmin(long me) {
		if (!staff.isAdmin(me)) {
			throw new ForbiddenException("Врачей заводит администратор");
		}
	}

	private void requireSelfOrAdmin(long me, long doctorId) {
		if (!staff.isAdmin(me) && !staff.doctorIdOf(me).map(id -> id == doctorId).orElse(false)) {
			throw new ForbiddenException("Профиль меняет сам врач или администратор");
		}
	}

}

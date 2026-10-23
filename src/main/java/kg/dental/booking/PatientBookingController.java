package kg.dental.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kg.dental.auth.InitDataAuthFilter;
import kg.dental.patient.ChatLanguages;
import kg.dental.patient.Lang;
import kg.dental.shared.InvalidFieldException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API самозаписи. Открыт любому, кого подписал Telegram (InitDataAuthFilter пускает сюда не
 * только врача); кто это — Telegram id из подписанной initData, а не из тела запроса: подделать
 * чужую запись нельзя.
 */
@RestController
@RequestMapping("/api/patient")
class PatientBookingController {

	record Book(@NotNull(message = "Выберите врача") Long doctorId,
			@NotNull(message = "Выберите услугу") Long serviceTypeId,
			@NotNull(message = "Выберите время") Instant startAt, String fullName, boolean consent) {
	}

	private final BookingService service;

	private final ChatLanguages languages;

	PatientBookingController(BookingService service, ChatLanguages languages) {
		this.service = service;
		this.languages = languages;
	}

	record Language(String lang) {
	}

	/** Переключатель «Русский / Кыргызча»: на этом языке дальше пишет и бот (напоминания, ответы). */
	@PutMapping("/language")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void language(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId, @RequestBody Language request) {
		if (request.lang() == null || !List.of("ru", "ky").contains(request.lang())) {
			throw new InvalidFieldException("lang", "Язык: ru или ky");
		}
		languages.set(telegramId, Lang.of(request.lang()));
	}

	@GetMapping("/me")
	BookingViews.Me me(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId) {
		return service.me(telegramId);
	}

	@GetMapping("/slots")
	BookingViews.Slots slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam long doctorId, @RequestParam long serviceTypeId) {
		return service.slots(date, doctorId, serviceTypeId);
	}

	@PostMapping("/booking")
	@ResponseStatus(HttpStatus.CREATED)
	BookingViews.Booking book(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId,
			@Valid @RequestBody Book request) {
		return service.book(telegramId, request.doctorId(), request.serviceTypeId(), request.startAt(),
				request.fullName(), request.consent());
	}

	record Reschedule(@NotNull(message = "Выберите время") Instant startAt) {
	}

	/** Свободное время для переноса своей записи — у того же врача, той же длительности. */
	@GetMapping("/booking/slots")
	BookingViews.Slots rescheduleSlots(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.rescheduleSlots(telegramId, date);
	}

	@PostMapping("/booking/reschedule")
	BookingViews.Booking reschedule(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId,
			@Valid @RequestBody Reschedule request) {
		return service.reschedule(telegramId, request.startAt());
	}

	@PostMapping("/booking/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId) {
		service.cancel(telegramId);
	}

}

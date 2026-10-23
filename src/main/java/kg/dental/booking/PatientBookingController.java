package kg.dental.booking;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kg.dental.auth.InitDataAuthFilter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

	record Book(@NotNull(message = "Выберите услугу") Long serviceTypeId,
			@NotNull(message = "Выберите время") Instant startAt, String fullName, boolean consent) {
	}

	private final BookingService service;

	PatientBookingController(BookingService service) {
		this.service = service;
	}

	@GetMapping("/me")
	BookingViews.Me me(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId) {
		return service.me(telegramId);
	}

	@GetMapping("/slots")
	BookingViews.Slots slots(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam long serviceTypeId) {
		return service.slots(date, serviceTypeId);
	}

	@PostMapping("/booking")
	@ResponseStatus(HttpStatus.CREATED)
	BookingViews.Booking book(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId,
			@Valid @RequestBody Book request) {
		return service.book(telegramId, request.serviceTypeId(), request.startAt(), request.fullName(),
				request.consent());
	}

	@PostMapping("/booking/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long telegramId) {
		service.cancel(telegramId);
	}

}

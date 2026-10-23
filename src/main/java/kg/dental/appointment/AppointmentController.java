package kg.dental.appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.Staff;
import kg.dental.shared.ForbiddenException;
import kg.dental.shared.InvalidFieldException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Приёмы. Каждое действие карточки — своя команда ({@code /reschedule}, {@code /status},
 * {@code /reply}, {@code /undo}), а не общий PATCH: у каждой свои правила и своё тело,
 * и сервер не гадает, что значит запрос, где изменилось сразу три поля.
 */
@RestController
@RequestMapping("/api/appointments")
class AppointmentController {

	private final AppointmentService service;

	private final Staff staff;

	AppointmentController(AppointmentService service, Staff staff) {
		this.service = service;
		this.staff = staff;
	}

	/** Приём коллеги — только смотреть (Staff.requireCanEdit). */
	private void requireOwn(long me, long appointmentId) {
		staff.requireCanEdit(me, service.get(appointmentId).doctorId());
	}

	/** {@code ?date=2027-06-01&doctorId=1} — день врача по Бишкеку. */
	@GetMapping
	List<AppointmentView> day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam long doctorId) {
		return service.day(date, doctorId);
	}

	/** {@code /api/appointments/counts?from=2027-06-01&days=21}: сколько приёмов в каждый день — для ленты дней. */
	@GetMapping("/counts")
	List<DayCount> counts(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(defaultValue = "21") int days, @RequestParam long doctorId) {
		return service.dayCounts(from, Math.clamp(days, 1, 62), doctorId);
	}

	/** Удалить приём совсем — только админ (врачу хватает «Отменить»: статистика честнее). */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		requireAdmin(me);
		service.delete(id);
	}

	record DeleteAll(String confirm) {
	}

	record Deleted(int deleted) {
	}

	/** Удалить все приёмы — админ, с подтверждением словом «УДАЛИТЬ»: случайно не нажать. */
	@PostMapping("/delete-all")
	Deleted deleteAll(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody DeleteAll request) {
		requireAdmin(me);
		if (!"УДАЛИТЬ".equals(request.confirm() == null ? null : request.confirm().strip().toUpperCase(Locale.ROOT))) {
			throw new InvalidFieldException("confirm", "Напишите слово УДАЛИТЬ");
		}
		return new Deleted(service.deleteAll());
	}

	private void requireAdmin(long me) {
		if (!staff.isAdmin(me)) {
			throw new ForbiddenException("Удалять записи может администратор");
		}
	}

	record Payment(Integer amountSom, PaymentMethod method) {
	}

	/** Оплата у состоявшегося приёма — своего (врач) или любого (админ). */
	@PostMapping("/{id}/payment")
	AppointmentView payment(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@RequestBody Payment request) {
		requireOwn(me, id);
		return service.setPayment(id, request.amountSom(), request.method());
	}

	/**
	 * {@code /api/appointments/revenue?from=2026-10-01&to=2026-10-31&doctorId=1}: выручка. Врач видит
	 * свою, админ — любого врача и всего кабинета (без doctorId).
	 */
	@GetMapping("/revenue")
	RevenueView revenue(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long doctorId) {
		staff.requireMoneyAccess(me, doctorId);
		if (to.isBefore(from) || from.plusDays(366).isBefore(to)) {
			throw new InvalidFieldException("to", "Период — до года");
		}
		return service.revenue(from, to, doctorId);
	}

	/** {@code /api/appointments/ratings?days=90}: оценки пациентов — в «Статистику». */
	@GetMapping("/ratings")
	RatingSummary ratings(@RequestParam(defaultValue = "90") int days, @RequestParam(required = false) Long doctorId) {
		return service.ratings(Math.clamp(days, 1, 3650), doctorId);
	}

	@GetMapping("/{id}")
	AppointmentView get(@PathVariable long id) {
		return service.get(id);
	}

	/** {@code /api/appointments/stats?weeks=8&doctorId=1}: по неделям, текущая первой; без врача — весь кабинет. */
	@GetMapping("/stats")
	List<WeekStats> stats(@RequestParam(defaultValue = "8") int weeks, @RequestParam(required = false) Long doctorId) {
		return service.weeklyStats(Math.clamp(weeks, 1, 52), doctorId);
	}

	/** {@code /api/appointments/recall?months=6}: кого пора позвать на профосмотр. */
	@GetMapping("/recall")
	List<RecallView> recall(@RequestParam(defaultValue = "6") int months) {
		return service.recall(Math.clamp(months, 1, 36));
	}

	/** Карточка пациента живёт здесь, а не в модуле пациентов: пациенты о приёмах не знают. */
	@GetMapping("/by-patient/{patientId}")
	PatientCard card(@PathVariable long patientId) {
		return service.card(patientId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AppointmentView create(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @Valid @RequestBody AppointmentRequests.Create request) {
		staff.requireCanEdit(me, request.doctorId());
		return service.create(request);
	}

	@PostMapping("/{id}/reschedule")
	AppointmentView reschedule(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@Valid @RequestBody AppointmentRequests.Reschedule request) {
		requireOwn(me, id);
		return service.reschedule(id, request);
	}

	@PostMapping("/{id}/status")
	AppointmentView changeStatus(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@Valid @RequestBody AppointmentRequests.ChangeStatus request) {
		requireOwn(me, id);
		return service.changeStatus(id, request);
	}

	@PostMapping("/{id}/reply")
	AppointmentView changeReply(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id,
			@Valid @RequestBody AppointmentRequests.ChangeReply request) {
		requireOwn(me, id);
		return service.changeReply(id, request);
	}

	@PostMapping("/{id}/undo")
	AppointmentView undo(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id, @Valid @RequestBody AppointmentRequests.Undo request) {
		requireOwn(me, id);
		return service.undo(id, request);
	}

	/** Кнопка WhatsApp: шлётся с keepalive до перехода в WhatsApp, ответ не нужен. */
	@PostMapping("/{id}/reminder-opened")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void reminderOpened(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @PathVariable long id) {
		requireOwn(me, id);
		service.markReminderOpened(id);
	}

}

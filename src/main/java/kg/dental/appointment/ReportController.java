package kg.dental.appointment;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

import kg.dental.auth.InitDataAuthFilter;
import kg.dental.doctor.DoctorService;
import kg.dental.doctor.Staff;
import kg.dental.shared.ConflictException;
import kg.dental.telegram.TelegramClient;
import kg.dental.telegram.TelegramException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * «📤 Отчёт за месяц»: CSV для Excel приходит в бот тому, кто нажал. Через бот, а не загрузкой
 * в Mini App: Telegram на телефоне надёжно открывает и пересылает файлы из чата. В файле нет
 * имён и телефонов пациентов (AppointmentService.exportCsv).
 */
@RestController
class ReportController {

	record Request(YearMonth month, Long doctorId) {
	}

	private final AppointmentService appointments;

	private final DoctorService doctors;

	private final TelegramClient telegram;

	private final Staff staff;

	ReportController(AppointmentService appointments, DoctorService doctors, TelegramClient telegram, Staff staff) {
		this.appointments = appointments;
		this.doctors = doctors;
		this.telegram = telegram;
		this.staff = staff;
	}

	@PostMapping("/api/appointments/report")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void report(@RequestAttribute(InitDataAuthFilter.TELEGRAM_ID) long me, @RequestBody Request request) {
		staff.requireMoneyAccess(me, request.doctorId());
		var month = request.month() == null ? YearMonth.now() : request.month();
		var csv = appointments.exportCsv(month.atDay(1), month.atEndOfMonth(), request.doctorId());
		var who = request.doctorId() == null ? "kabinet" : "vrach-" + request.doctorId();
		var title = request.doctorId() == null ? "весь кабинет" : doctors.nameOf(request.doctorId());
		try {
			telegram.sendDocument(me, "otchet-" + month + "-" + who + ".csv", csv.getBytes(StandardCharsets.UTF_8),
					"📊 Отчёт за " + month + " — " + title + ". Откройте в Excel.");
		}
		catch (TelegramException e) {
			throw new ConflictException(ConflictException.NOT_DELIVERED, "Не удалось отправить отчёт в бот — нажмите /start в боте и повторите");
		}
	}

}

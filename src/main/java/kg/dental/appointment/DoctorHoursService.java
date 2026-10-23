package kg.dental.appointment;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import kg.dental.doctor.DoctorService;
import kg.dental.shared.InvalidFieldException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Часы работы врачей. Своих нет — часы клиники (WorkSchedule). Свободное время и сетка
 * самозаписи берут часы отсюда (SlotService) — у каждого врача своё время.
 */
@Service
public class DoctorHoursService {

	/** Часы врача в конкретный день: работает ли и с какого по какое время. */
	record DayHours(boolean working, LocalTime start, LocalTime end) {
	}

	private final DoctorHoursRepository repository;

	private final WorkSchedule clinic;

	private final DoctorService doctors;

	DoctorHoursService(DoctorHoursRepository repository, WorkSchedule clinic, DoctorService doctors) {
		this.repository = repository;
		this.clinic = clinic;
		this.doctors = doctors;
	}

	@Transactional(readOnly = true)
	public DoctorHoursView view(long doctorId) {
		doctors.get(doctorId);
		var rows = repository.findByDoctorIdOrderByWeekday(doctorId);
		if (rows.isEmpty()) {
			return new DoctorHoursView(false, Arrays.stream(DayOfWeek.values())
				.filter(clinic.days()::contains)
				.map(d -> new DoctorHoursView.Day(d, clinic.start(), clinic.end()))
				.toList());
		}
		return new DoctorHoursView(true,
				rows.stream().map(h -> new DoctorHoursView.Day(h.getWeekday(), h.getStartTime(), h.getEndTime())).toList());
	}

	/**
	 * Свои часы целиком (заменяют прежние). Пустой список — вернуться к часам клиники.
	 * Приёмы, уже записанные вне новых часов, остаются: врач перенесёт их сам.
	 */
	@Transactional
	public DoctorHoursView update(long doctorId, List<DoctorHoursView.Day> days) {
		doctors.get(doctorId);
		var seen = new HashSet<DayOfWeek>();
		for (var day : days) {
			if (day.weekday() == null || day.start() == null || day.end() == null) {
				throw new InvalidFieldException("days", "Для рабочего дня укажите начало и конец");
			}
			if (!day.start().isBefore(day.end())) {
				throw new InvalidFieldException("days", "Конец рабочего дня — позже начала");
			}
			if (!seen.add(day.weekday())) {
				throw new InvalidFieldException("days", "День недели повторяется");
			}
		}
		repository.deleteByDoctor(doctorId);
		repository.flush();
		days.forEach(d -> repository.save(new DoctorHours(doctorId, d.weekday(), d.start(), d.end())));
		return view(doctorId);
	}

	/** Часы врача в этот день: свои или клиники. */
	@Transactional(readOnly = true)
	DayHours on(long doctorId, LocalDate date) {
		var rows = repository.findByDoctorIdOrderByWeekday(doctorId);
		if (rows.isEmpty()) {
			return new DayHours(clinic.isWorkingDay(date), clinic.start(), clinic.end());
		}
		Optional<DoctorHours> day = rows.stream().filter(h -> h.getWeekday() == date.getDayOfWeek()).findFirst();
		return day.map(h -> new DayHours(true, h.getStartTime(), h.getEndTime()))
			.orElse(new DayHours(false, clinic.start(), clinic.end()));
	}

}

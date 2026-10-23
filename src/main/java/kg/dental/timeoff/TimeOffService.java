package kg.dental.timeoff;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import kg.dental.shared.InvalidFieldException;
import kg.dental.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Перерывы и выходные. Модуль приёмов спрашивает {@link #on}, чтобы вычесть их из свободного времени. */
@Service
public class TimeOffService {

	/** Отпуск дольше — скорее опечатка в годе, чем настоящий отпуск. */
	static final int MAX_DAYS = 366;

	private final TimeOffRepository repository;

	private final Clock clock;

	TimeOffService(TimeOffRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<TimeOffView> all() {
		return repository.findAllByOrderByWeekdayAscFirstDayAscStartTimeAsc().stream().map(TimeOffView::of).toList();
	}

	/** Что действует в этот день у врача (его и общие): сначала «весь день», потом по времени. */
	@Transactional(readOnly = true)
	public List<TimeOffView> on(LocalDate date, long doctorId) {
		return repository.findAll()
			.stream()
			.filter(t -> t.on(date, doctorId))
			.map(TimeOffView::of)
			.sorted((a, b) -> a.wholeDay() != b.wholeDay() ? (a.wholeDay() ? -1 : 1)
					: a.wholeDay() ? 0 : a.startTime().compareTo(b.startTime()))
			.toList();
	}

	/**
	 * @param doctorId чей перерыв; null — у всех врачей
	 * @param weekday каждую неделю; иначе — firstDay и lastDay (один день: lastDay = firstDay)
	 * @param startTime вместе с endTime; оба null — весь день
	 */
	@Transactional
	public TimeOffView create(Long doctorId, DayOfWeek weekday, LocalDate firstDay, LocalDate lastDay, LocalTime startTime,
			LocalTime endTime, String title, String emoji) {
		if (weekday == null) {
			if (firstDay == null) {
				throw new InvalidFieldException("firstDay", "Укажите день недели или дату");
			}
			lastDay = lastDay == null ? firstDay : lastDay;
			if (lastDay.isBefore(firstDay)) {
				throw new InvalidFieldException("lastDay", "Последний день — не раньше первого");
			}
			if (firstDay.plusDays(MAX_DAYS).isBefore(lastDay)) {
				throw new InvalidFieldException("lastDay", "Не больше года");
			}
		}
		else {
			firstDay = null;
			lastDay = null;
		}
		if ((startTime == null) != (endTime == null)) {
			throw new InvalidFieldException("endTime", "Укажите и начало, и конец — или «весь день»");
		}
		if (startTime != null && !startTime.isBefore(endTime)) {
			throw new InvalidFieldException("endTime", "Конец — позже начала");
		}
		var cleanTitle = title == null ? "" : title.strip();
		if (cleanTitle.isEmpty() || cleanTitle.length() > 60) {
			throw new InvalidFieldException("title", "Название — до 60 символов");
		}
		var cleanEmoji = emoji == null || emoji.isBlank() ? "⏸️" : emoji.strip();
		if (cleanEmoji.length() > 8) {
			throw new InvalidFieldException("emoji", "Один значок");
		}
		return TimeOffView.of(repository.save(new TimeOff(doctorId, weekday, firstDay, lastDay, startTime, endTime, cleanTitle,
				cleanEmoji, clock.instant())));
	}

	@Transactional
	public void delete(long id) {
		var timeOff = repository.findById(id).orElseThrow(() -> new NotFoundException("Перерыв не найден"));
		repository.delete(timeOff);
	}

}

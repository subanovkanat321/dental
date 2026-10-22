package kg.dental.digest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import kg.dental.appointment.DaySummary;

/**
 * Текст утренней сводки. Только числа и время: на вход приходит DaySummary, в котором имён
 * и телефонов нет вовсе, — так их нельзя «случайно» добавить в сообщение (design:134).
 */
final class DigestText {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("ru"));

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private DigestText() {
	}

	static String of(LocalDate tomorrow, DaySummary summary, ZoneId zone) {
		var text = new StringBuilder();
		if (summary.scheduled() == 0) {
			// O11/D23: и при пустом дне сводка приходит — тишина выглядела бы как поломка бота
			text.append("Завтра приёмов нет.");
		}
		else {
			text.append("Завтра, ")
				.append(DAY.format(tomorrow).replace(".", ""))
				.append(": ")
				.append(summary.scheduled())
				.append(' ')
				.append(appointments(summary.scheduled()))
				.append(", первый в ")
				.append(TIME.format(summary.firstStart().atZone(zone)))
				.append('.');
		}
		if (summary.unmarkedWeek() > 0) {
			text.append("\nНе отмечено за неделю: ")
				.append(summary.unmarkedWeek())
				.append(" — отметьте «Пришёл» или «Не пришёл».");
		}
		return text.toString();
	}

	/** 1 приём, 2 приёма, 5 приёмов, 11 приёмов, 21 приём. */
	static String appointments(long n) {
		var mod100 = n % 100;
		var mod10 = n % 10;
		if (mod100 >= 11 && mod100 <= 14) {
			return "приёмов";
		}
		return mod10 == 1 ? "приём" : mod10 >= 2 && mod10 <= 4 ? "приёма" : "приёмов";
	}

}

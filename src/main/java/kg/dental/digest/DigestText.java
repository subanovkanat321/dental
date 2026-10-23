package kg.dental.digest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;

import kg.dental.appointment.DayOverview;
import kg.dental.appointment.PatientReply;
import kg.dental.appointment.PreNotice;

/**
 * Тексты уведомлений врачу. Только числа и время: на вход приходят DayOverview и PreNotice,
 * в которых имён и телефонов нет вовсе, — так их нельзя «случайно» добавить в сообщение
 * (design:134: Telegram — сторонний сервис).
 */
final class DigestText {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("ru"));

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private DigestText() {
	}

	/**
	 * Утро: сегодня (сколько, с какого по какое время, ответы пациентов), завтра, неотмеченные
	 * за неделю. При пустых днях — «приёмов нет»: тишина выглядела бы как поломка бота (O11).
	 */
	static String morning(DayOverview today, DayOverview tomorrow, long unmarkedWeek, ZoneId zone) {
		var text = new StringBuilder();
		if (today.active() == 0) {
			text.append("Сегодня приёмов нет.");
		}
		else {
			text.append("Сегодня, ").append(day(today)).append(": ").append(count(today.active())).append(", ")
				.append(time(today.firstStart(), zone)).append('–').append(time(today.lastEnd(), zone)).append('.');
			var answers = new ArrayList<String>();
			var unanswered = today.active() - today.confirmed() - today.reschedule();
			if (today.confirmed() > 0) {
				answers.add("подтвердили: " + today.confirmed());
			}
			if (today.reschedule() > 0) {
				answers.add("просят перенос: " + today.reschedule());
			}
			if (unanswered > 0) {
				answers.add("без ответа: " + unanswered);
			}
			var line = String.join(" · ", answers);
			text.append('\n').append(Character.toUpperCase(line.charAt(0))).append(line.substring(1)).append('.');
		}
		text.append('\n');
		if (tomorrow.active() == 0) {
			text.append("Завтра приёмов нет.");
		}
		else {
			text.append("Завтра, ").append(day(tomorrow)).append(": ").append(count(tomorrow.active()))
				.append(", первый в ").append(time(tomorrow.firstStart(), zone)).append('.');
		}
		if (unmarkedWeek > 0) {
			text.append("\nНе отмечено за неделю: ").append(unmarkedWeek).append(" — отметьте «Приём состоялся» или «Неявка».");
		}
		return text.toString();
	}

	/**
	 * Вечер: кому ещё не напомнили на завтра и что не отмечено сегодня.
	 *
	 * @return null — всё сделано, писать не о чем
	 */
	static String evening(DayOverview today, DayOverview tomorrow) {
		var lines = new ArrayList<String>();
		var notReady = tomorrow.active() - tomorrow.ready();
		if (notReady > 0) {
			lines.add("Завтра не напомнили: " + notReady + " из " + tomorrow.active() + " — откройте обход.");
		}
		if (today.unmarked() > 0) {
			lines.add("Сегодня не отмечено «явка / неявка»: " + today.unmarked() + ".");
		}
		return lines.isEmpty() ? null : String.join("\n", lines);
	}

	/** «Через 15 мин, в 10:00 — приём (30 мин). Ответ пациента: придёт.» */
	static String preNotice(PreNotice notice, Instant now, ZoneId zone) {
		var inMinutes = Math.max(1, Math.round(Duration.between(now, notice.startAt()).toSeconds() / 60.0));
		var length = Duration.between(notice.startAt(), notice.endAt()).toMinutes();
		return "Через " + inMinutes + " мин, в " + time(notice.startAt(), zone) + " — приём (" + length + " мин). "
				+ replyText(notice);
	}

	private static String replyText(PreNotice notice) {
		if (notice.reply() == PatientReply.CONFIRMED) {
			return "Ответ пациента: придёт.";
		}
		if (notice.reply() == PatientReply.RESCHEDULE) {
			return "Ответ пациента: просит перенос.";
		}
		if (notice.reply() == PatientReply.NO_ANSWER) {
			return "На напоминание ответа нет.";
		}
		return notice.reminderOpenedAt() != null ? "Ответа пока нет." : "Напоминание не отправляли.";
	}

	private static String day(DayOverview overview) {
		return DAY.format(overview.date()).replace(".", "");
	}

	private static String time(Instant instant, ZoneId zone) {
		return TIME.format(instant.atZone(zone));
	}

	private static String count(long n) {
		return n + " " + appointments(n);
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

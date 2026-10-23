package kg.dental.patient;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Язык пациента: русский или кыргызский. Врачи и админ работают на русском. Даты для
 * кыргызского — своими таблицами, а не из Locale: «26-октябрь, дүйшөмбү» — такой порядок
 * и формы, как пишут в Бишкеке, не зависят от версии данных CLDR в JDK.
 */
public enum Lang {

	RU("ru"), KY("ky");

	private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"));

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static final String[] KY_MONTHS = { "январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
			"сентябрь", "октябрь", "ноябрь", "декабрь" };

	/** С понедельника: дүйшөмбү … жекшемби. */
	private static final String[] KY_WEEKDAYS = { "дүйшөмбү", "шейшемби", "шаршемби", "бейшемби", "жума", "ишемби",
			"жекшемби" };

	private final String code;

	Lang(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public Locale locale() {
		return Locale.forLanguageTag(code);
	}

	/** «ky» (и «ky-KG») — кыргызский; всё остальное и пусто — русский. */
	public static Lang of(String code) {
		return code != null && code.toLowerCase(Locale.ROOT).startsWith("ky") ? KY : RU;
	}

	/** «понедельник, 26 октября» / «26-октябрь, дүйшөмбү». */
	public String date(LocalDate date) {
		return this == KY ? date.getDayOfMonth() + "-" + KY_MONTHS[date.getMonthValue() - 1] + ", "
				+ KY_WEEKDAYS[date.getDayOfWeek().getValue() - 1] : RU_DATE.format(date);
	}

	/** «в 10:00» / «саат 10:00». */
	public String time(ZonedDateTime at) {
		return (this == KY ? "саат " : "в ") + TIME.format(at);
	}

	/** «понедельник, 26 октября, в 10:00» / «26-октябрь, дүйшөмбү, саат 10:00». */
	public String dateTime(ZonedDateTime at) {
		return date(at.toLocalDate()) + ", " + time(at);
	}

	/** «сегодня в 10:00», «завтра в 10:00» или дата целиком; по-кыргызски «бүгүн / эртең саат 10:00». */
	public String when(ZonedDateTime at, LocalDate today) {
		if (at.toLocalDate().equals(today)) {
			return (this == KY ? "бүгүн " : "сегодня ") + time(at);
		}
		if (at.toLocalDate().equals(today.plusDays(1))) {
			return (this == KY ? "эртең " : "завтра ") + time(at);
		}
		return dateTime(at);
	}

}

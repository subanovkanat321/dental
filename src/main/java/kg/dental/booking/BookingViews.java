package kg.dental.booking;

import java.time.Instant;
import java.util.List;

import kg.dental.appointment.AppointmentStatus;
import kg.dental.appointment.AppointmentView;
import kg.dental.appointment.SlotsView;
import kg.dental.servicetype.ServiceTypeView;

/** Ответы API самозаписи. Пациент видит только своё: ни чужих имён, ни расписания целиком. */
final class BookingViews {

	private BookingViews() {
	}

	/** Запись пациента: время, услуга и статус (REQUESTED — ждёт врача, SCHEDULED — подтверждена). */
	record Booking(long id, Instant startAt, Instant endAt, String serviceName, AppointmentStatus status) {

		static Booking of(AppointmentView a) {
			return new Booking(a.id(), a.startAt(), a.endAt(), a.serviceName(), a.status());
		}

	}

	/**
	 * Всё, что нужно первому экрану Mini App у не-врача.
	 *
	 * @param doctor это врач — Mini App покажет экран врача
	 * @param phone номер, которым человек поделился; null — ещё не делился
	 */
	record Me(boolean doctor, boolean bookingEnabled, String fullName, String phone, Booking booking,
			List<ServiceTypeView> services, String clinicContact, int daysAhead) {
	}

	/**
	 * Свободные начала для услуги в день — без чужих приёмов. Перерывы и выходной — с подписью
	 * («🕌 Джума-намаз»): пациенту понятно, почему этого времени нет.
	 */
	record Slots(boolean workingDay, List<Instant> starts, List<SlotsView.Break> breaks, SlotsView.Break dayOff) {
	}

}

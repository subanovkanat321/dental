package kg.dental.booking;

import java.time.Instant;

/** События самозаписи: по ним после коммита уходят сообщения в бот (BookingNotifications). */
final class BookingEvents {

	private BookingEvents() {
	}

	/** Пациент оставил заявку — сказать врачу. */
	record Requested(long appointmentId) {
	}

	/** Пациент сам отменил свою заявку или запись — сказать врачу. */
	record CancelledByPatient(long appointmentId) {
	}

	/** Пациент перенёс запись: было — старое начало. */
	record RescheduledByPatient(long appointmentId, Instant previousStart) {
	}

}

package kg.dental.appointment;

import java.time.Instant;

/** Кого спросить «Как прошёл приём?»: приём, его время и Telegram пациента. */
public record ReviewRequest(long appointmentId, Instant startAt, long telegramId, String fullName) {
}

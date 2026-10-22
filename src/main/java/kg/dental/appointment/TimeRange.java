package kg.dental.appointment;

import java.time.Instant;

/** Полуоткрытый интервал времени [start, end) — как tstzrange(start_at, end_at, '[)') в базе. */
public record TimeRange(Instant start, Instant end) {
}

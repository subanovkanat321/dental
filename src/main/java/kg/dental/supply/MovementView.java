package kg.dental.supply;

import java.time.Instant;

/** Строка истории: «−1, списал Нурлан, сегодня 10:15». */
public record MovementView(int delta, String reason, String doctorName, Instant createdAt) {
}

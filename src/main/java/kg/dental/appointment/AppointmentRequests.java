package kg.dental.appointment;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Тела запросов к приёмам. Цена ошибки в длительности — чужой час в расписании, поэтому
 * сервер сам проверяет границы: от 15 минут до 4 часов. В каждом изменении — версия приёма,
 * которую видел врач (D18).
 */
public final class AppointmentRequests {

	static final String DURATION_MESSAGE = "Длительность от 15 минут до 4 часов";

	static final String VERSION_MESSAGE = "Обновите экран";

	private AppointmentRequests() {
	}

	public record Create(@NotNull(message = "Выберите пациента") Long patientId,
			@NotNull(message = "Выберите время") Instant startAt,
			@NotNull(message = DURATION_MESSAGE) @Min(value = 15, message = DURATION_MESSAGE)
			@Max(value = 240, message = DURATION_MESSAGE) Integer durationMinutes) {
	}

	public record Reschedule(@NotNull(message = VERSION_MESSAGE) Long version, @NotNull(message = "Выберите время") Instant startAt,
			@NotNull(message = DURATION_MESSAGE) @Min(value = 15, message = DURATION_MESSAGE)
			@Max(value = 240, message = DURATION_MESSAGE) Integer durationMinutes) {
	}

	public record ChangeStatus(@NotNull(message = VERSION_MESSAGE) Long version, @NotNull(message = "Укажите статус") AppointmentStatus status) {
	}

	public record ChangeReply(@NotNull(message = VERSION_MESSAGE) Long version, @NotNull(message = "Укажите ответ") PatientReply reply) {
	}

	/** @param previousStatus статус до отмены или до «Не пришёл» */
	public record Undo(@NotNull(message = VERSION_MESSAGE) Long version, @NotNull(message = "Укажите прежний статус") AppointmentStatus previousStatus) {
	}

}

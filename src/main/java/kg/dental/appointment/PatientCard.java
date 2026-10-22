package kg.dental.appointment;

import java.util.List;

import kg.dental.patient.PatientView;

/**
 * Карточка пациента: история приёмов и сколько раз пришёл, не пришёл, отменял.
 * Медицинских записей (что лечили) здесь нет намеренно — до шага 8 (согласие пациента).
 *
 * @param upcoming запланированные и ещё не закончившиеся, ближайший первым
 * @param past остальные, последний первым (не больше 50)
 */
public record PatientCard(PatientView patient, long done, long noShow, long cancelled, List<AppointmentView> upcoming,
		List<AppointmentView> past) {
}

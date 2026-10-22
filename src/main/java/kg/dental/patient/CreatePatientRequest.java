package kg.dental.patient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param allowDuplicate {@code true} — врач увидел «Такой пациент уже есть» и нажал
 * «Всё равно создать» (D19)
 */
record CreatePatientRequest(
		@NotBlank(message = "Укажите имя") @Size(max = 200, message = "Не длиннее 200 символов") String fullName,
		@NotBlank(message = "Укажите телефон") String phone,
		boolean allowDuplicate) {
}

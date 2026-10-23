package kg.dental.profile;

/**
 * Профиль врача в ответах API.
 *
 * @param photoUrl адрес фото с версией ({@code /profile/photo?v=3}); null — фото нет
 */
public record ProfileView(String doctorName, String about, String photoUrl) {
}

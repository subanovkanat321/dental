package kg.dental.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Профиль врача: имя и фото на экране самозаписи. */
@IntegrationTest
class ProfileApiTests {

	/** Начало настоящего JPEG — сервер узнаёт тип по первым байтам. */
	static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F' };

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void reset() {
		jdbc.update("update clinic_profile set doctor_name = null, about = null, photo = null, photo_type = null, photo_version = 0");
	}

	@Test
	void doctorSetsNameAndPhotoPatientSeesThem() {
		assertThat(mvc.put().uri("/api/profile").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.contentType(MediaType.APPLICATION_JSON).content("""
					{"doctorName": "  Айгерим Садыкова ", "about": "Стоматолог-терапевт, 10 лет опыта"}"""))
			.hasStatusOk().bodyJson().extractingPath("$.doctorName").isEqualTo("Айгерим Садыкова");

		assertThat(upload(JPEG)).hasStatusOk().bodyJson().extractingPath("$.photoUrl").isEqualTo("/profile/photo?v=1");

		var me = assertThat(mvc.get().uri("/api/patient/me").header(HttpHeaders.AUTHORIZATION, stranger())).bodyJson();
		me.extractingPath("$.profile.doctorName").isEqualTo("Айгерим Садыкова");
		me.extractingPath("$.profile.about").isEqualTo("Стоматолог-терапевт, 10 лет опыта");
		me.extractingPath("$.profile.photoUrl").isEqualTo("/profile/photo?v=1");
	}

	@Test
	void photoIsServedWithoutLoginWithTypeFromBytesAndLongCache() {
		upload(JPEG);

		var photo = mvc.get().uri("/profile/photo?v=1").exchange();
		assertThat(photo).hasStatusOk().hasContentType("image/jpeg");
		assertThat(photo).headers().hasValue("X-Content-Type-Options", "nosniff");
		assertThat(photo.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=31536000", "immutable");
		assertThat(photo.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
	}

	@Test
	void newPhotoGetsNewAddressAndDeleteRemovesIt() {
		upload(JPEG);
		assertThat(upload(JPEG)).bodyJson().extractingPath("$.photoUrl").isEqualTo("/profile/photo?v=2");

		assertThat(mvc.delete().uri("/api/profile/photo").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.bodyJson().extractingPath("$.photoUrl").isNull();
		assertThat(mvc.get().uri("/profile/photo?v=2")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void notAnImageIs422EvenWithImageName() {
		assertThat(upload("<script>alert(1)</script>".getBytes())).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("photo");
	}

	@Test
	void patientCannotChangeProfile() {
		assertThat(mvc.put().uri("/api/profile").header(HttpHeaders.AUTHORIZATION, stranger())
			.contentType(MediaType.APPLICATION_JSON).content("{\"doctorName\": \"x\"}")).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void imageTypeByMagicBytes() {
		assertThat(ProfileService.imageType(JPEG)).contains("image/jpeg");
		assertThat(ProfileService.imageType(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A })).contains("image/png");
		assertThat(ProfileService.imageType("RIFF0000WEBPVP8 ".getBytes())).contains("image/webp");
		assertThat(ProfileService.imageType("GIF89a".getBytes())).isEmpty();
	}

	private MvcTestResult upload(byte[] bytes) {
		return mvc.post().uri("/api/profile/photo").header(HttpHeaders.AUTHORIZATION, TestInitData.doctor())
			.multipart().file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", bytes)).exchange();
	}

	private static String stranger() {
		return "tma " + TestInitData.signed(TestInitData.STRANGER_ID, Instant.now());
	}

}

package kg.dental.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import kg.dental.IntegrationTest;
import kg.dental.TestInitData;
import kg.dental.telegram.TelegramClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Склад: остатки, списание без ухода в минус, сигнал «заканчивается» один раз, удаление — админ. */
@IntegrationTest
@TestPropertySource(properties = "dental.telegram.mode=webhook")
class SupplyApiTests {

	/** Врач кабинета 1 — не админ. */
	static final long DOCTOR = 222;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	TelegramClient telegram;

	@BeforeEach
	void setUp() {
		jdbc.execute("delete from supply");
		jdbc.update("update doctor set telegram_id = ? where id = 1", DOCTOR);
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("delete from supply");
		jdbc.update("update doctor set telegram_id = null where id = 1");
	}

	@Test
	void createListAndStartingQuantityIsInHistory() {
		var id = create("Карпула артикаин", "шт", 20, 5);

		assertThat(mvc.get().uri("/api/supplies").header(HttpHeaders.AUTHORIZATION, tma(DOCTOR))).bodyJson()
			.extractingPath("$[0]").isEqualTo(java.util.Map.of("id", (int) id, "name", "Карпула артикаин", "unit", "шт",
					"quantity", 20, "minQuantity", 5, "low", false));
		assertThat(history(id)).hasStatusOk().bodyJson().extractingPath("$[0].reason").isEqualTo("DELIVERY");
	}

	@Test
	void sameNameTwiceIs422() {
		create("Перчатки", "уп", 3, 1);

		assertThat(post("/api/supplies", "{\"name\": \" перчатки \"}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
	}

	@Test
	void useWritesWhoAndCannotGoBelowZero() {
		var id = create("Карпула", "шт", 2, 0);

		assertThat(move(id, -2)).hasStatusOk().bodyJson().extractingPath("$.quantity").isEqualTo(0);
		assertThat(move(id, -1)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code")
			.isEqualTo(SupplyService.NOT_ENOUGH);
		assertThat(jdbc.queryForObject("select quantity from supply where id = ?", Integer.class, id)).isZero();
		assertThat(history(id)).bodyJson().extractingPath("$[0].doctorName").isNotNull();
	}

	@Test
	void crossingThresholdTellsStaffOnceWithoutPersonalData() {
		var id = create("Слепочная масса", "уп", 4, 2);

		move(id, -1);
		verify(telegram, never()).sendMessage(anyLong(), anyString(), anyList());
		move(id, -1);
		move(id, -1);

		var text = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TelegramClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
		verify(telegram).sendMessage(eq(DOCTOR), text.capture(), buttons.capture());
		assertThat(text.getValue()).isEqualTo("⚠️ Заканчивается: Слепочная масса — осталось 2 уп. Пора заказать.");
		assertThat(buttons.getValue()).hasSize(1);
		verify(telegram).sendMessage(eq(TestInitData.DOCTOR_ID), anyString(), anyList());
	}

	@Test
	void deliveryRaisesAndNextDropAlertsAgain() {
		var id = create("Боры", "шт", 1, 0);

		move(id, -1);
		assertThat(move(id, 10)).bodyJson().extractingPath("$.low").isEqualTo(false);
		move(id, -10);

		verify(telegram, times(2)).sendMessage(eq(DOCTOR), eq("⚠️ Закончился материал: Боры. Пора заказать."), anyList());
	}

	@Test
	void zeroDeltaIs422() {
		var id = create("Вата", "уп", 1, 0);

		assertThat(move(id, 0)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void onlyAdminDeletes() {
		var id = create("Шприцы", "шт", 1, 0);

		assertThat(mvc.delete().uri("/api/supplies/" + id).header(HttpHeaders.AUTHORIZATION, tma(DOCTOR)))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(mvc.delete().uri("/api/supplies/" + id).header(HttpHeaders.AUTHORIZATION, TestInitData.doctor()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(jdbc.queryForObject("select count(*) from supply_movement", Integer.class)).isZero();
	}

	@Test
	void patientCannotSeeSupplies() {
		assertThat(mvc.get().uri("/api/supplies").header(HttpHeaders.AUTHORIZATION, tma(TestInitData.STRANGER_ID)))
			.hasStatus(HttpStatus.FORBIDDEN);
	}

	private long create(String name, String unit, int quantity, int min) {
		var body = post("/api/supplies", """
				{"name": "%s", "unit": "%s", "quantity": %d, "minQuantity": %d}""".formatted(name, unit, quantity, min))
			.getResponse();
		try {
			assertThat(body.getStatus()).isEqualTo(201);
			return ((Number) JsonPath.read(body.getContentAsString(), "$.id")).longValue();
		}
		catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private MvcTestResult move(long id, int delta) {
		return post("/api/supplies/" + id + "/move", "{\"delta\": " + delta + "}");
	}

	private MvcTestResult history(long id) {
		return mvc.get().uri("/api/supplies/" + id + "/history").header(HttpHeaders.AUTHORIZATION, tma(DOCTOR)).exchange();
	}

	private MvcTestResult post(String uri, String json) {
		return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, tma(DOCTOR)).contentType(MediaType.APPLICATION_JSON)
			.content(json).exchange();
	}

	private static String tma(long userId) {
		return "tma " + TestInitData.signed(userId, Instant.now());
	}

}

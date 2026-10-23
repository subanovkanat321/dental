package kg.dental.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.OptionalLong;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kg.dental.doctor.Staff;
import kg.dental.telegram.BotTexts;
import kg.dental.telegram.TelegramProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Вход в API Mini App. Каждый запрос к {@code /api/**} несёт заголовок
 * {@code Authorization: tma <initData>} (так принято у Mini App: «tma» — Telegram Mini App).
 * <ul>
 * <li>нет заголовка, подпись не та или вход старше 24 ч — 401 «откройте заново из бота»;</li>
 * <li>подпись верна, но id не в ALLOWED_TELEGRAM_IDS — 403 с тем же текстом, что бот пишет
 * посторонним (DD13): ни одного байта расписания.</li>
 * <li>исключение — {@code /api/patient/**} (самозапись): туда пускает любого, кого подписал
 * Telegram; кто он — атрибут {@link #TELEGRAM_ID}, а не данные из запроса.</li>
 * </ul>
 * Фильтр, а не проверка в контроллерах: нельзя забыть её в новом контроллере.
 * Без токена бота не пускает никого (fail closed), а не всех.
 */
@Component
public class InitDataAuthFilter extends OncePerRequestFilter {

	static final String SCHEME = "tma ";

	/** Самозапись: открыта любому, кого подписал Telegram. */
	static final String PATIENT_API = "/api/patient/";

	static final String SESSION_EXPIRED = "Сессия устарела — откройте заново из бота";

	/** Атрибут запроса с Telegram id вошедшего — для будущих «кто изменил». */
	public static final String TELEGRAM_ID = "dental.telegramId";

	private final TelegramProperties properties;

	private final InitDataValidator validator;

	private final Clock clock;

	private final JsonMapper json;

	private final Staff staff;

	InitDataAuthFilter(TelegramProperties properties, Clock clock, JsonMapper json, Staff staff) {
		this.properties = properties;
		this.staff = staff;
		this.validator = properties.configured()
				? new InitDataValidator(properties.botToken(), properties.initDataMaxAge(), json) : null;
		this.clock = clock;
		this.json = json;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var header = request.getHeader(HttpHeaders.AUTHORIZATION);
		var userId = validator == null || header == null || !header.startsWith(SCHEME) ? OptionalLong.empty()
				: validator.validate(header.substring(SCHEME.length()), clock.instant());
		if (userId.isEmpty()) {
			problem(response, HttpStatus.UNAUTHORIZED, SESSION_EXPIRED);
			return;
		}
		var patientApi = request.getRequestURI().startsWith(request.getContextPath() + PATIENT_API);
		// Сотрудник — админ из ALLOWED_TELEGRAM_IDS или врач с привязанным Telegram (Меню → Врачи)
		if (!patientApi && !staff.isStaff(userId.getAsLong())) {
			problem(response, HttpStatus.FORBIDDEN, BotTexts.stranger(properties.clinicContact()));
			return;
		}
		request.setAttribute(TELEGRAM_ID, userId.getAsLong());
		chain.doFilter(request, response);
	}

	/** Тот же формат Problem Details, что у остальных ошибок API (ApiErrors). */
	private void problem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
		var body = new LinkedHashMap<String, Object>();
		body.put("type", "about:blank");
		body.put("title", status.getReasonPhrase());
		body.put("status", status.value());
		body.put("detail", detail);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		json.writeValue(response.getOutputStream(), body);
	}

}

package kg.dental.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import kg.dental.shared.NotFoundException;
import kg.dental.telegram.TelegramProperties;
import kg.dental.telegram.TelegramProperties.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** Выдача входа на ноутбуке: только напрямую с этого компьютера и без туннеля. */
class DevInitDataControllerTests {

	static final String TOKEN = "123456:local";

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);

	@Test
	void localBrowserGetsSignedInitDataOfFirstAllowedId() {
		var initData = controller(null).initData(localRequest());

		var validator = new InitDataValidator(TOKEN, Duration.ofHours(24), JsonMapper.builder().build());
		assertThat(validator.validate(initData, CLOCK.instant())).hasValue(7);
	}

	@Test
	void notWithTunnelConfigured() {
		var controller = controller(URI.create("https://abc.trycloudflare.com"));
		assertThatThrownBy(() -> controller.initData(localRequest()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void notThroughProxyOrFromAnotherComputer() {
		var proxied = localRequest();
		proxied.addHeader("Cf-Connecting-Ip", "203.0.113.5");
		var forwarded = localRequest();
		forwarded.addHeader("X-Forwarded-For", "203.0.113.5");
		var remote = new MockHttpServletRequest();
		remote.setRemoteAddr("192.168.1.20");

		for (var request : new MockHttpServletRequest[] { proxied, forwarded, remote }) {
			assertThatThrownBy(() -> controller(null).initData(request))
				.isInstanceOf(NotFoundException.class);
		}
	}

	private static DevInitDataController controller(URI publicUrl) {
		var properties = new TelegramProperties(TOKEN, "", Set.of(9L, 7L), Mode.OFF, publicUrl, "", null);
		return new DevInitDataController(properties, CLOCK);
	}

	private static MockHttpServletRequest localRequest() {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr("127.0.0.1");
		return request;
	}

}

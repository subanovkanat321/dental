package kg.dental.telegram;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class TelegramConfig {

	/** Long polling ждёт до 25 с, поэтому read-таймаут больше; обычные вызовы отвечают за доли секунды. */
	static final int POLL_TIMEOUT_SECONDS = 25;

	@Bean
	TelegramClient telegramClient(RestClient.Builder builder, TelegramProperties properties) {
		// Таймауты обязательны: без них зависший запрос к Telegram навсегда занял бы поток
		var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 10));
		var http = builder.clone().baseUrl("https://api.telegram.org").requestFactory(requestFactory).build();
		return new TelegramClient(http, properties.botToken());
	}

}

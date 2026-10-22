package kg.dental.telegram;

import java.util.concurrent.atomic.AtomicBoolean;

import kg.dental.telegram.TelegramProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Подключает бота при старте приложения:
 * <ul>
 * <li>webhook (сервер) — один раз сообщает Telegram адрес {@code <public-url>/telegram/webhook}
 * и секрет;</li>
 * <li>polling (ноутбук, где нет публичного адреса) — сам спрашивает Telegram о новых
 * сообщениях в отдельном потоке;</li>
 * <li>off — ничего (тесты, запуск без токена).</li>
 * </ul>
 * SmartLifecycle, а не @PostConstruct: Spring вызывает start() после создания всех бинов,
 * а stop() — при остановке, до закрытия пула соединений и прочего.
 */
@Component
class TelegramStartup implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TelegramStartup.class);

	private final TelegramClient telegram;

	private final BotUpdateHandler handler;

	private final TelegramProperties properties;

	private final AtomicBoolean running = new AtomicBoolean();

	private Thread poller;

	TelegramStartup(TelegramClient telegram, BotUpdateHandler handler, TelegramProperties properties) {
		this.telegram = telegram;
		this.handler = handler;
		this.properties = properties;
	}

	@Override
	public void start() {
		running.set(true);
		if (properties.mode() == Mode.OFF) {
			return;
		}
		if (!properties.configured()) {
			log.warn("Бот не запущен: режим {}, но TELEGRAM_BOT_TOKEN не задан", properties.mode());
			return;
		}
		switch (properties.mode()) {
			case WEBHOOK -> registerWebhook();
			case POLLING -> startPolling();
			case OFF -> {
			}
		}
	}

	private void registerWebhook() {
		if (properties.publicUrl() == null || properties.webhookSecret().isEmpty()) {
			log.warn("Webhook не установлен: нужны PUBLIC_URL и TELEGRAM_WEBHOOK_SECRET");
			return;
		}
		try {
			telegram.setWebhook(properties.publicUrl().resolve("/telegram/webhook"), properties.webhookSecret());
			log.info("Webhook установлен");
		}
		catch (TelegramException e) {
			// Приложение продолжает работать: Mini App доступна, бот заработает после перезапуска
			log.error("Webhook не установлен: {}", e.getMessage());
		}
	}

	private void startPolling() {
		poller = Thread.ofVirtual().name("telegram-polling").start(this::poll);
	}

	private void poll() {
		try {
			telegram.deleteWebhook();
		}
		catch (TelegramException e) {
			log.warn("{}", e.getMessage());
		}
		log.info("Бот слушает Telegram (polling)");
		long offset = 0;
		while (running.get()) {
			try {
				for (var update : telegram.getUpdates(offset, TelegramConfig.POLL_TIMEOUT_SECONDS)) {
					// offset = последний + 1: Telegram больше не пришлёт уже полученные обновления
					offset = update.updateId() + 1;
					try {
						handler.handle(update);
					}
					catch (RuntimeException e) {
						log.warn("Обновление {} не обработано: {}", update.updateId(), e.getClass().getSimpleName());
					}
				}
			}
			catch (TelegramException e) {
				if (!running.get()) {
					return;
				}
				log.warn("{}; повтор через 5 с", e.getMessage());
				pause();
			}
		}
	}

	private void pause() {
		try {
			Thread.sleep(5_000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running.set(false);
		}
	}

	@Override
	public void stop() {
		running.set(false);
		if (poller != null) {
			poller.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

}

import { defineConfig } from '@playwright/test';

/**
 * Тесты идут против уже запущенного приложения (E2E_BASE_URL) с настоящей базой (DATABASE_URL):
 * в CI — job e2e (.github/workflows/ci.yml), на ноутбуке — ./mvnw spring-boot:run.
 * Один поток: тесты чистят общую базу перед собой.
 */
export default defineConfig({
  testDir: './tests',
  workers: 1,
  fullyParallel: false,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:8080',
    viewport: { width: 390, height: 844 },
    locale: 'ru-RU',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // В облачной песочнице Chromium предустановлен; в CI ставит npx playwright install
    launchOptions: process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {},
  },
});

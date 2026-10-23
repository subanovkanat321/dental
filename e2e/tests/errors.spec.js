// Состояния ошибок (DD5) и вход (initData, DD13).
import { test } from '@playwright/test';
import { expect, openApp, resetDb, signInitData, DOCTOR, STRANGER } from './helpers.js';

test.beforeEach(resetDb);

test('сервер ответил 500 → «Повторить»; после повтора «День» загружается', async ({ page }) => {
  let fail = true;
  await page.route('**/api/appointments?*', (route) => (fail ? route.fulfill({ status: 500, body: '{}' }) : route.continue()));
  await openApp(page);

  await expect(page.getByRole('button', { name: 'Повторить' })).toBeVisible();
  fail = false;
  await page.getByRole('button', { name: 'Повторить' }).click();
  await expect(page.locator('.rows, .message.hint').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Повторить' })).toHaveCount(0);
});

test('вход старше 24 часов → «Сессия устарела — откройте заново из бота»', async ({ page }) => {
  await openApp(page, { initData: signInitData(DOCTOR, Math.floor(Date.now() / 1000) - 25 * 3600) });

  await expect(page.getByText('Сессия устарела — откройте заново из бота')).toBeVisible();
});

test('посторонний с настоящей подписью видит запись, а не расписание врача', async ({ page }) => {
  await openApp(page, { initData: signInitData(STRANGER) });

  await expect(page.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
  await expect(page.locator('.rows')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Меню' })).toHaveCount(0);
});

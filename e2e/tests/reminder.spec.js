// Кнопка WhatsApp (D14), вечерний обход (DD6, ER3), «Не сохранено» (ER2).
import { test } from '@playwright/test';
import { expect, openApp, resetDb, addPatient, addAppointment, tomorrow, mainButtonText, pressMainButton, calls, db } from './helpers.js';

test.beforeEach(resetDb);

test('отметка уходит с keepalive раньше openLink; ссылка wa.me с текстом напоминания', async ({ page }) => {
  await addAppointment(await addPatient('Айгуль Т.', '996555123456'), tomorrow(), '09:30');
  await openApp(page, { path: '/?day=tomorrow' });

  await page.locator('.row', { hasText: 'Айгуль Т.' }).locator('.wa').click();

  await expect(page.locator('.row', { hasText: 'Айгуль Т.' })).toContainText('Ждём ответа');
  const log = await calls(page);
  expect(log.map((c) => c[0])).toEqual(['mark', 'openLink']);
  expect(log[0][2]).toBe(true); // keepalive
  const url = new URL(log[1][1]);
  expect(`${url.origin}${url.pathname}`).toBe('https://wa.me/996555123456');
  expect(url.searchParams.get('text')).toMatch(
    /^Здравствуйте, Айгуль Т\.! Напоминаем: завтра в 09:30 приём у стоматолога\. Ответьте 1 — приду, 2 — перенести\./);
  await expect.poll(async () => (await db.query('select reminder_opened_at from appointment')).rows[0].reminder_opened_at)
    .not.toBeNull();
});

test('«Завтра»: «Готово X из N» и «Напомнить следующему» по очереди', async ({ page }) => {
  await addAppointment(await addPatient('Бакыт М.'), tomorrow(), '10:00');
  await addAppointment(await addPatient('Айгуль Т.'), tomorrow(), '09:30');
  await openApp(page, { path: '/?day=tomorrow' });

  await expect(page.locator('.day-note')).toHaveText('Готово 0 из 2');
  expect(await mainButtonText(page)).toBe('Напомнить следующему (Айгуль Т., 09:30)');
  await pressMainButton(page);
  await expect(page.locator('.day-note')).toHaveText('Готово 1 из 2');
  expect(await mainButtonText(page)).toBe('Напомнить следующему (Бакыт М., 10:00)');
  await pressMainButton(page);
  await expect(page.locator('.day-note')).toHaveText('Готово 2 из 2');
  expect(await mainButtonText(page)).toBe('Новая запись');
  expect((await calls(page)).filter((c) => c[0] === 'openLink')).toHaveLength(2);
});

test('отметка не дошла → «Не сохранено · повторить»; повтор не открывает WhatsApp второй раз', async ({ page }) => {
  await addAppointment(await addPatient('Айгуль Т.'), tomorrow(), '09:30');
  await openApp(page, { path: '/?day=tomorrow' });
  await page.route('**/reminder-opened', (route) => route.fulfill({ status: 500, body: '{}' }));

  const row = page.locator('.row', { hasText: 'Айгуль Т.' });
  await row.locator('.wa').click();
  await expect(row).toContainText('Не сохранено · повторить');
  await expect(page.locator('.day-note')).toHaveText('Готово 0 из 1'); // недошедшая отметка не считается

  await page.unroute('**/reminder-opened');
  await row.locator('.wa').click();
  await expect(row).toContainText('Ждём ответа');
  expect((await calls(page)).filter((c) => c[0] === 'openLink')).toHaveLength(1);
});

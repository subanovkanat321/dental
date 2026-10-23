// Как связаться с пациентом: бот (если записывался через него), WhatsApp или звонок.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, addPatient, addAppointment, tomorrow, db, STRANGER, calls,
} from './helpers.js';

test.beforeEach(resetDb);

async function botPatient() {
  const id = await addPatient('Бакыт Маматов', '996700112233');
  await db.query('update patient set telegram_id = $1, consent_at = now() where id = $2', [STRANGER, id]);
  return id;
}

test('записавшемуся через бота — три способа связи, главный — Telegram', async ({ page }) => {
  const id = await addAppointment(await botPatient(), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await expect(page.getByRole('button', { name: 'Напомнить в Telegram' })).toHaveClass(/primary/);
  await expect(page.getByRole('button', { name: 'Напомнить в WhatsApp' })).not.toHaveClass(/primary/);
  await expect(page.getByRole('link', { name: 'Позвонить' })).toHaveAttribute('href', 'tel:+996700112233');
});

test('без бота — WhatsApp и звонок, кнопки Telegram нет', async ({ page }) => {
  const id = await addAppointment(await addPatient('Айгуль', '996555123456'), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await expect(page.getByRole('button', { name: 'Напомнить в WhatsApp' })).toHaveClass(/primary/);
  await expect(page.getByRole('button', { name: 'Напомнить в Telegram' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Позвонить' })).toHaveAttribute('href', 'tel:+996555123456');
});

test('напоминание в Telegram: сервер отправил — «Ждём ответа», WhatsApp не открывается', async ({ page }) => {
  const id = await addAppointment(await botPatient(), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });
  // Бот в e2e выключен (TELEGRAM_MODE=off): ответ сервера после доставки подставляем сами
  await page.route(`**/api/appointments/${id}/remind-telegram`, async (route) => {
    await db.query('update appointment set reminder_opened_at = now() where id = $1', [id]);
    const real = await route.fetch({ url: route.request().url().replace('/remind-telegram', ''), method: 'GET' });
    await route.fulfill({ response: real });
  });

  await page.getByRole('button', { name: 'Напомнить в Telegram' }).click();

  await expect(page.locator('#toast')).toContainText('Напоминание отправлено в Telegram');
  await expect(page.getByText('Ждём ответа')).toBeVisible();
  expect(await calls(page)).toEqual([]);
});

test('не доставлено — ошибка, отметки нет', async ({ page }) => {
  const id = await addAppointment(await botPatient(), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await page.getByRole('button', { name: 'Напомнить в Telegram' }).click();

  await expect(page.locator('#toast')).toContainText('Бот не настроен на сервере');
  await expect(page.getByText('Не напомнили')).toBeVisible();
  expect((await db.query('select reminder_opened_at from appointment')).rows[0].reminder_opened_at).toBeNull();
});

test('в строке дня у записавшегося через бота — кнопка TG', async ({ page }) => {
  await addAppointment(await botPatient(), tomorrow(), '10:00');
  await addAppointment(await addPatient('Айгуль'), tomorrow(), '11:00');
  await openApp(page, { path: '/?day=tomorrow' });

  await expect(page.getByRole('button', { name: 'Telegram: Бакыт Маматов, 10:00' })).toHaveText('TG');
  await expect(page.getByRole('button', { name: 'WhatsApp: Айгуль, 11:00' })).toHaveText('WA');
});

test('«Позвонить» в Telegram открывает страницу звонка, номер — только во фрагменте', async ({ page }) => {
  const id = await addAppointment(await addPatient('Айгуль', '996555123456'), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await page.getByRole('link', { name: 'Позвонить' }).click();

  const [[kind, url]] = await calls(page);
  expect(kind).toBe('openLink');
  expect(url).toMatch(/\/a\/[0-9a-z]+\/call\.html#996555123456$/);
});

test('страница звонка: кнопка с номером и ссылкой tel:', async ({ page }) => {
  // Переход на tel: в Chromium без звонилки ничего не делает — проверяем саму ссылку
  // Страница сразу уходит на tel:, и «load» в Chromium не наступает — ждём только начала загрузки
  await page.goto('/call.html#996555123456', { waitUntil: 'commit' });

  await expect(page.getByRole('link', { name: 'Позвонить +996 555 123 456' })).toHaveAttribute('href', 'tel:+996555123456');
});

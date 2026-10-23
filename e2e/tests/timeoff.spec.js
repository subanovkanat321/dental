// Перерывы и выходные: джума-намаз по пятницам, отпуск, экран в меню.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, nextWeekday, db, saveContact, signInitData, STRANGER, pressMainButton,
} from './helpers.js';

test.beforeEach(resetDb);

test('пятница: у врача строка «🕌 Джума-намаз», свободное время вокруг неё', async ({ page }) => {
  const friday = nextWeekday(5, 1);
  await openApp(page, { path: '/' });
  await page.locator('.pick-date input').fill(friday);

  const pause = page.locator('.row.pause');
  await expect(pause).toContainText('🕌 Джума-намаз');
  await expect(pause).toContainText('13:00');
  await expect(pause).toContainText('15:00');
  await expect(page.locator('.row.free').first()).toContainText('свободно до 13:00');
});

test('пятница: пациенту — подпись про джума-намаз, 13:00–14:45 в сетке нет', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  await openApp(page, { initData: signInitData(STRANGER) });
  await page.getByRole('button', { name: 'Осмотр и консультация · 30 мин' }).click();
  // Пятница в пределах ленты самозаписи (14 дней), не раньше послезавтра
  const friday = nextWeekday(5, 2);
  const days = page.locator('.days .day');
  const index = Math.round((new Date(friday) - new Date(await page.evaluate(() => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bishkek' }).format(new Date())))) / 86400000);
  await days.nth(index).click();

  await expect(page.getByText('🕌 С 13:00 до 15:00 — джума-намаз, врач не принимает')).toBeVisible();
  await expect(page.locator('.slot', { hasText: /^12:30$/ })).toBeVisible();
  await expect(page.locator('.slot', { hasText: /^13:00$/ })).toHaveCount(0);
  await expect(page.locator('.slot', { hasText: /^14:45$/ })).toHaveCount(0);
  await expect(page.locator('.slot', { hasText: /^15:00$/ })).toBeVisible();
});

test('врач добавляет отпуск — день закрыт и у врача, и у пациента', async ({ page }) => {
  const monday = nextWeekday(1, 2);
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Перерывы и выходные' }).click();
  await expect(page.getByText('Каждую пятницу · 13:00–15:00')).toBeVisible();
  await page.getByRole('button', { name: '+ Добавить' }).click();
  await page.getByRole('button', { name: 'По датам' }).click();
  await page.locator('input[type=date]').nth(0).fill(monday);
  await page.locator('input[type=date]').nth(1).fill(monday);
  await page.locator('#whole-day').check();
  await page.locator('input[type=text]').fill('Отпуск');
  await page.getByRole('button', { name: '🏖️' }).click();
  await pressMainButton(page);

  await expect(page.locator('.timeoff-title', { hasText: /^Отпуск$/ })).toBeVisible();
  const { rows } = await db.query("select first_day, last_day, start_time from time_off where title = 'Отпуск'");
  expect(rows).toHaveLength(1);
  expect(rows[0].start_time).toBeNull();

  await openApp(page, { path: '/' });
  await page.locator('.pick-date input').fill(monday);
  await expect(page.getByText('Отпуск — записей нет')).toBeVisible();
});

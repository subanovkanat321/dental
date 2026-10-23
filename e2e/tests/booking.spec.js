// Запись: «свободно» → новая запись, сетка времени, «Время занято» (DD2, DD5, DD11).
import { test } from '@playwright/test';
import { expect, openApp, resetDb, addPatient, addAppointment, tomorrow, mainButtonText, pressMainButton, slot } from './helpers.js';

test.beforeEach(resetDb);

test('«свободно» → новый пациент → запись на 10:00 → строка в «Дне»', async ({ page }) => {
  await openApp(page, { path: '/?day=tomorrow' });

  await page.locator('.row.free').first().click();
  await expect(page.getByRole('heading', { name: 'Новая запись' })).toBeVisible();
  await expect(page.locator('.slot[aria-pressed=true]')).toHaveText('09:00'); // время из окна подставлено
  await page.getByLabel('Поиск пациента').fill('Айгуль Т.');
  await page.getByRole('button', { name: /Новый пациент/ }).click();
  await page.locator('input[type=tel]').fill('0555 12 34 56');
  await slot(page, '10:00').click();
  expect(await mainButtonText(page)).toBe('Записать');
  await pressMainButton(page);

  const row = page.locator('.row', { hasText: 'Айгуль Т.' });
  await expect(row).toContainText('10:00');
  await expect(row).toContainText('Не напомнили');
  await expect(page.locator('.row.free').first()).toContainText('свободно до 10:00');
});

test('занятое время сетка не предлагает; вручную — «Время занято» и снова сетка', async ({ page }) => {
  await addAppointment(await addPatient('Бакыт М.'), tomorrow(), '10:00');
  await addPatient('Айгуль Т.');
  await openApp(page, { path: '/?day=tomorrow' });

  await page.locator('.row.free').first().click();
  await page.getByLabel('Поиск пациента').fill('Айгуль');
  await page.getByRole('button', { name: /^Айгуль Т\./ }).click();
  await expect(slot(page, '09:30')).toBeVisible();
  await expect(slot(page, '09:45')).toHaveCount(0); // 30 минут не помещаются до 10:00
  await expect(slot(page, '10:00')).toHaveCount(0);

  await page.getByRole('button', { name: 'Другое время' }).click();
  await page.locator('input[type=time]').fill('10:15');
  await pressMainButton(page);
  await expect(page.getByText('Время занято')).toBeVisible();
  await expect(slot(page, '10:30')).toBeVisible(); // форма вернулась к сетке свободного
});

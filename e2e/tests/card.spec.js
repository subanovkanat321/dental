// Карточка приёма: «Отменить» → «Вернуть» (DD7, ER1), перенос по сетке (ER4).
import { test } from '@playwright/test';
import { expect, openApp, resetDb, addPatient, addAppointment, tomorrow, pressMainButton, slot, db } from './helpers.js';

test.beforeEach(resetDb);

test('«Отменить приём» → «Вернуть» возвращает приём вместе с ответом пациента', async ({ page }) => {
  await addAppointment(await addPatient('Айгуль Т.'), tomorrow(), '10:00', { reply: 'CONFIRMED' });
  await openApp(page, { path: '/?day=tomorrow' });

  await page.locator('.row', { hasText: 'Айгуль Т.' }).click();
  await expect(page.getByRole('button', { name: 'Придёт' })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Отменить приём' }).click();
  await expect(page.locator('#toast')).toContainText('Отменено');
  await page.locator('#toast').getByRole('button', { name: 'Вернуть' }).click();

  await expect(page.locator('p.tone-ok', { hasText: 'Придёт' })).toBeVisible();
  const { rows } = await db.query('select status, reply from appointment');
  expect(rows[0]).toEqual({ status: 'SCHEDULED', reply: 'CONFIRMED' });
});

test('перенос: своё время в сетке выбрано, после переноса — «напомните заново»', async ({ page }) => {
  await addAppointment(await addPatient('Айгуль Т.'), tomorrow(), '10:00', { reminded: true });
  await openApp(page, { path: '/?day=tomorrow' });

  await page.locator('.row', { hasText: 'Айгуль Т.' }).click();
  await page.getByRole('button', { name: 'Перенести' }).click();
  await expect(page.locator('.slot[aria-pressed=true]')).toHaveText('10:00'); // свой приём не мешает себе
  await slot(page, '12:00').click();
  await pressMainButton(page);

  await expect(page.getByText('Время изменилось — напомните пациенту заново')).toBeVisible();
  await expect(page.getByText('Не напомнили')).toBeVisible();
});

test('«Открыть приём» из уведомления перед приёмом: ?appointment=ID — сразу карточка', async ({ page }) => {
  const id = await addAppointment(await addPatient('Айгуль Т.'), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await expect(page.getByRole('heading', { name: '10:00–10:30' })).toBeVisible();
  await expect(page.locator('.name-link')).toContainText('Айгуль Т.');
});

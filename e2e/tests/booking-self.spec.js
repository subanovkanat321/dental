// Самозапись пациента через бота: запись → врач подтверждает → пациент видит → отменяет.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, signInitData, saveContact, STRANGER, pressMainButton, mainButtonText, db,
} from './helpers.js';

test.beforeEach(resetDb);

test('пациент записывается сам, врач подтверждает, пациент отменяет', async ({ browser }) => {
  const patient = await browser.newPage();
  await openApp(patient, {
    initData: signInitData(STRANGER),
    shareContact: () => saveContact(STRANGER, '996700112233', 'Бакыт'),
  });

  await expect(patient.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
  // Услуг несколько (V6) — пока не выбрана, времени нет
  await expect(patient.getByText('Сначала выберите услугу')).toBeVisible();
  await expect(patient.getByRole('button', { name: 'Консультация по брекетам · 30 мин' })).toBeVisible();
  await patient.getByRole('button', { name: 'Осмотр и консультация · 30 мин' }).click();
  await expect(patient.getByRole('button', { name: 'Осмотр и консультация · 30 мин' })).toHaveAttribute('aria-pressed', 'true');
  await patient.locator('.day').nth(2).click(); // послезавтра — сетка точно полная
  await patient.locator('.slot', { hasText: /^10:00$/ }).click();
  await patient.locator('input[autocomplete=name]').fill('Бакыт Маматов');
  await patient.getByRole('button', { name: 'Поделиться номером' }).click();
  await expect(patient.getByText('+996 700 112 233')).toBeVisible();

  // Пока нет согласия — кнопка ведёт к шагу «Ваши данные», а не пытается записать
  expect(await mainButtonText(patient)).toBe('Далее: ваши данные ↓');
  await pressMainButton(patient);
  await expect(patient.getByText('Нужно согласие на обработку данных')).toBeVisible();
  await patient.locator('#consent').check();
  expect(await mainButtonText(patient)).toBe('Записаться');
  await pressMainButton(patient);

  await expect(patient.getByRole('heading', { name: 'Ваша запись' })).toBeVisible();
  await expect(patient.getByText(/Ждёт подтверждения врача/)).toBeVisible();

  // Врач: заявка в карточке — «Подтвердить»
  const { rows } = await db.query('select id from appointment');
  const doctor = await browser.newPage();
  await openApp(doctor, { path: `/?appointment=${rows[0].id}` });
  await expect(doctor.getByText('Заявка — подтвердите')).toBeVisible();
  await expect(doctor.getByText('Запись через бота · Осмотр и консультация')).toBeVisible();
  await doctor.getByRole('button', { name: 'Подтвердить' }).click();
  await expect(doctor.getByText('Не напомнили')).toBeVisible();

  await patient.reload();
  await expect(patient.getByText('Подтверждена')).toBeVisible();
  await patient.getByRole('button', { name: 'Отменить запись' }).click();
  await patient.getByRole('button', { name: /Точно отменить/ }).click();
  await expect(patient.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
  expect((await db.query('select status from appointment')).rows[0].status).toBe('CANCELLED');
});

test('занятое время пациенту не показывается', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  const { rows } = await db.query("insert into patient (full_name, phone) values ('Кто-то', '996555000001') returning id");
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bishkek' })
    .format(new Date(Date.now() + 2 * 86400000));
  await db.query(`insert into appointment (doctor_id, patient_id, start_at, end_at) values (1, $1, $2, $3)`,
    [rows[0].id, new Date(`${day}T10:00:00+06:00`), new Date(`${day}T10:30:00+06:00`)]);
  await openApp(page, { initData: signInitData(STRANGER) });

  await page.getByRole('button', { name: 'Осмотр и консультация · 30 мин' }).click();
  await page.locator('.day').nth(2).click();
  await expect(page.locator('.slot', { hasText: /^09:30$/ })).toBeVisible();
  await expect(page.locator('.slot', { hasText: /^10:00$/ })).toHaveCount(0);
  await expect(page.locator('.slot', { hasText: /^10:15$/ })).toHaveCount(0);
  await expect(page.getByText('Кто-то')).toHaveCount(0); // чужие имена пациенту не видны
});

test('врач добавляет услугу — пациент видит её в записи', async ({ browser }) => {
  const doctor = await browser.newPage();
  await openApp(doctor);
  await doctor.getByRole('button', { name: 'Меню' }).click();
  await doctor.getByRole('button', { name: 'Услуги для самозаписи' }).click();
  await doctor.getByRole('button', { name: '+ Добавить услугу' }).click();
  await doctor.locator('input[type=text]').fill('Имплантация');
  await doctor.locator('input[type=number]').first().fill('120');
  await pressMainButton(doctor);
  await expect(doctor.getByRole('button', { name: 'Имплантация · 120 мин' })).toBeVisible();

  const patient = await browser.newPage();
  await openApp(patient, { initData: signInitData(STRANGER) });
  await expect(patient.getByRole('button', { name: 'Имплантация · 120 мин' })).toBeVisible();
  await db.query("delete from service_type where name = 'Имплантация'");
});

test('пациент переносит запись — новое время ждёт подтверждения врача', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  const { rows: [p] } = await db.query(
    `insert into patient (full_name, phone, telegram_id) values ('Бакыт', '996700112233', $1) returning id`, [STRANGER]);
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bishkek' }).format(new Date(Date.now() + 2 * 86400000));
  await db.query(`insert into appointment (doctor_id, patient_id, start_at, end_at, status, source)
    values (1, $1, $2, $3, 'SCHEDULED', 'PATIENT')`,
  [p.id, new Date(`${day}T10:00:00+06:00`), new Date(`${day}T10:30:00+06:00`)]);
  await openApp(page, { initData: signInitData(STRANGER) });

  await expect(page.getByText('✅ Подтверждена')).toBeVisible();
  await page.getByRole('button', { name: 'Перенести на другое время' }).click();
  await expect(page.getByRole('heading', { name: 'Перенести запись' })).toBeVisible();
  await page.locator('.slot', { hasText: /^12:00$/ }).click();
  await pressMainButton(page);

  await expect(page.getByRole('heading', { name: 'Ваша запись' })).toBeVisible();
  await expect(page.getByText(/Ждёт подтверждения врача/)).toBeVisible();
  await expect(page.locator('.big')).toContainText('в 12:00');
  const { rows } = await db.query('select status from appointment');
  expect(rows[0].status).toBe('REQUESTED');
});

test('кнопка внизу ведёт по шагам: услуга → время → данные', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  await openApp(page, { initData: signInitData(STRANGER) });

  await expect(page.getByRole('heading', { name: '① Услуга' })).toBeVisible();
  expect(await mainButtonText(page)).toBe('Выберите услугу ↑');
  await pressMainButton(page);
  await expect(page.locator('.step').first()).toHaveClass(/step-attention/);
  expect(await db.query('select count(*)::int as n from appointment').then((r) => r.rows[0].n)).toBe(0);

  await page.getByRole('button', { name: 'Осмотр и консультация · 30 мин' }).click();
  expect(await mainButtonText(page)).toBe('Далее: выбрать время ↓');
  // Экран сам уехал к дням и времени
  await expect(page.getByRole('heading', { name: '② День и время' })).toBeInViewport();
  await pressMainButton(page);
  await expect(page.getByText('Выберите время')).toBeVisible();

  await page.locator('.day').nth(2).click();
  await page.locator('.slot', { hasText: /^10:00$/ }).click();
  expect(await mainButtonText(page)).toBe('Далее: ваши данные ↓');
  await expect(page.getByRole('heading', { name: '③ Ваши данные' })).toBeInViewport();
});

// Несколько врачей в одном кабинете: у каждого своё расписание, пациент выбирает врача.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, addPatient, addAppointment, addDoctor, tomorrow, db, saveContact, signInitData, STRANGER,
  pressMainButton, slot, mainButtonText,
} from './helpers.js';

test.beforeEach(resetDb);

test('админ добавляет врача с Telegram — он появляется в списке', async ({ page }) => {
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Врачи' }).click();
  await page.getByRole('button', { name: '+ Добавить врача' }).click();
  await page.locator('input[type=text]').nth(0).fill('Айгерим Садыкова');
  await page.locator('input[type=text]').nth(1).fill('Ортодонт');
  await page.locator('input[type=text]').nth(2).fill('222');
  await pressMainButton(page);

  await expect(page.getByRole('button', { name: /Айгерим Садыкова.*Telegram 222/ })).toBeVisible();
  const { rows } = await db.query("select telegram_id from doctor where full_name = 'Айгерим Садыкова'");
  expect(rows[0].telegram_id).toBe('222');
});

test('у каждого врача свой «День»; новая запись — к выбранному врачу', async ({ page }) => {
  const second = await addDoctor('Айгерим Садыкова');
  await addAppointment(await addPatient('Пациент первого'), tomorrow(), '10:00');
  await addAppointment(await addPatient('Пациент второй'), tomorrow(), '10:00', { doctor: second });
  await openApp(page, { path: '/?day=tomorrow' });

  const switcher = page.getByRole('group', { name: 'Врач' });
  await expect(switcher.getByRole('button', { name: /Врач/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('Пациент первого')).toBeVisible();
  await expect(page.getByText('Пациент второй')).toHaveCount(0);

  await switcher.getByRole('button', { name: /Айгерим/ }).click();
  await expect(page.getByText('Пациент второй')).toBeVisible();
  await expect(page.getByText('Пациент первого')).toHaveCount(0);

  // Свободное окно второго врача → новая запись к нему
  await page.locator('.row.free').first().click();
  await expect(page.getByText('к врачу Айгерим Садыкова')).toBeVisible();
  await page.getByLabel('Поиск пациента').fill('Пациент первого');
  await page.getByRole('button', { name: /Пациент первого/ }).first().click();
  await slot(page, '09:00').click();
  await pressMainButton(page);
  await expect(page.locator('.row', { hasText: 'Пациент первого' })).toBeVisible();
  const { rows } = await db.query("select doctor_id from appointment where start_at::time = '03:00' order by id desc limit 1");
  expect(Number(rows[0].doctor_id)).toBe(second);
});

test('пациент выбирает врача, записывается к нему', async ({ page }) => {
  const second = await addDoctor('Айгерим Садыкова', null, 'Ортодонт, брекеты');
  await saveContact(STRANGER, '996700112233');
  await openApp(page, { initData: signInitData(STRANGER), shareContact: () => {} });

  await expect(page.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
  await page.getByRole('button', { name: /Айгерим Садыкова/ }).click();
  await expect(page.locator('.hero-doctor')).toHaveText('Айгерим Садыкова');
  await page.getByRole('button', { name: 'Осмотр и консультация · 30 мин' }).click();
  await page.locator('.days .day').nth(2).click();
  await page.locator('.slot', { hasText: /^10:00$/ }).click();
  await page.locator('input[autocomplete=name]').fill('Бакыт');
  await page.locator('#consent').check();
  await pressMainButton(page);

  await expect(page.getByRole('heading', { name: 'Ваша запись' })).toBeVisible();
  await expect(page.locator('.hero-doctor')).toHaveText('Айгерим Садыкова');
  const { rows } = await db.query('select doctor_id from appointment');
  expect(Number(rows[0].doctor_id)).toBe(second);
});

test('врач со своим Telegram входит и видит свой «День»', async ({ page }) => {
  const second = await addDoctor('Айгерим Садыкова', 222);
  await addAppointment(await addPatient('Её пациент'), tomorrow(), '11:00', { doctor: second });
  await openApp(page, { path: '/?day=tomorrow', initData: signInitData(222) });

  await expect(page.getByRole('group', { name: 'Врач' }).getByRole('button', { name: 'Айгерим (я)' }))
    .toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('Её пациент')).toBeVisible();
  // Врачей заводит админ — у врача этого пункта нет
  await page.getByRole('button', { name: 'Меню' }).click();
  await expect(page.getByRole('button', { name: 'Врачи' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Мой профиль' })).toBeVisible();
});

test('врач видит «День» коллеги, но не меняет его', async ({ page }) => {
  const second = await addDoctor('Айгерим Садыкова', 222);
  const colleagues = await addAppointment(await addPatient('Пациент коллеги'), tomorrow(), '10:00');
  await addAppointment(await addPatient('Мой пациент'), tomorrow(), '11:00', { doctor: second });
  await openApp(page, { path: '/?day=tomorrow', initData: signInitData(222) });
  await expect(page.getByText('Мой пациент')).toBeVisible();
  expect(await mainButtonText(page)).not.toBeNull();

  await page.getByRole('group', { name: 'Врач' }).getByRole('button', { name: /^Врач$/ }).click();
  await expect(page.getByText('👀 Расписание коллеги — только просмотр')).toBeVisible();
  await expect(page.getByText('Пациент коллеги')).toBeVisible();
  await expect(page.getByRole('button', { name: /WhatsApp: Пациент коллеги/ })).toHaveCount(0);
  expect(await mainButtonText(page)).toBeNull(); // главной кнопки «Новая запись» нет

  await page.getByText('Пациент коллеги').click();
  await expect(page.getByText(/Это приём коллеги/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Перенести' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Отменить приём' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Позвонить' })).toBeVisible();
  expect((await db.query('select status from appointment where id = $1', [colleagues])).rows[0].status).toBe('SCHEDULED');
});

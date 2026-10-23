// Адрес кабинета со ссылкой 2ГИС и техподдержка приложения.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, db, saveContact, signInitData, STRANGER, pressMainButton, calls,
} from './helpers.js';

const MAP = 'https://2gis.kg/bishkek/search/%D0%A8%D0%B5%D0%B2%D1%87%D0%B5%D0%BD%D0%BA%D0%BE%206';

test.beforeEach(resetDb);
test.afterEach(async () => {
  await db.query('update clinic_settings set address = null, map_url = null, support_telegram = null, support_phone = null');
});

test('админ заполняет «О клинике» — пациент видит адрес, открывает 2ГИС и поддержку', async ({ browser }) => {
  const admin = await browser.newPage();
  await openApp(admin);
  await admin.getByRole('button', { name: 'Меню' }).click();
  await admin.getByRole('button', { name: 'О клинике' }).click();
  await admin.getByPlaceholder('Например: Бишкек, ул. Шевченко 6').fill('Бишкек, ул. Шевченко 6');
  await admin.locator('input[type=url]').fill('https://evil.example/');
  await pressMainButton(admin);
  await expect(admin.getByText('Нужна ссылка из 2ГИС')).toBeVisible();
  await admin.locator('input[type=url]').fill(MAP);
  await admin.locator('input[type=tel]').fill('0773 565 895');
  await pressMainButton(admin);
  await expect(admin.getByRole('heading', { name: 'Меню' })).toBeVisible();
  const { rows } = await db.query('select address, support_phone from clinic_settings');
  expect(rows[0]).toEqual({ address: 'Бишкек, ул. Шевченко 6', support_phone: '996773565895' });

  await saveContact(STRANGER, '996700112233');
  const patient = await browser.newPage();
  await openApp(patient, { initData: signInitData(STRANGER) });
  await expect(patient.locator('.clinic-address')).toHaveText('Бишкек, ул. Шевченко 6');
  await patient.getByRole('button', { name: 'Открыть в 2ГИС' }).click();
  await patient.getByRole('button', { name: '🛟 Техподдержка' }).click();
  const opened = (await calls(patient)).map(([, url]) => url);
  expect(opened[0]).toBe(MAP);
  expect(opened[1]).toMatch(/\/call\.html#996773565895$/);
});

test('врач: «Техподдержка» — позвонить и частые вопросы', async ({ page }) => {
  await db.query("update clinic_settings set support_phone = '996773565895'");
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Техподдержка' }).click();

  await expect(page.getByRole('link', { name: 'Позвонить' })).toHaveAttribute('href', 'tel:+996773565895');
  await page.getByText('Не появилась кнопка «Открыть день»').click();
  await expect(page.getByText('Нажмите /start в чате с ботом.')).toBeVisible();
});

// Кыргызский язык для пациента: по языку Telegram и переключателем; выбор сохраняется для бота.
import { test } from '@playwright/test';
import { expect, openApp, resetDb, db, saveContact, signInitData, STRANGER } from './helpers.js';

test.beforeEach(resetDb);

async function lang() {
  const { rows } = await db.query('select lang from chat_language where telegram_id = $1', [STRANGER]);
  return rows[0]?.lang;
}

test('Telegram на кыргызском — запись по-кыргызски, язык сохранён для бота', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  await openApp(page, { initData: signInitData(STRANGER), languageCode: 'ky' });

  await expect(page.getByRole('heading', { name: 'Тиш дарыгерине жазылуу' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Бүгүн' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Эртең' })).toBeVisible();
  await expect(page.getByText('Бейтаптын аты')).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'ky');
  await expect.poll(lang).toBe('ky');
});

test('переключатель «Русский» — экран по-русски, выбранная услуга не теряется', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  await openApp(page, { initData: signInitData(STRANGER), languageCode: 'ky' });
  await page.getByRole('button', { name: 'Осмотр и консультация · 30 мүн.' }).click();

  await page.getByRole('button', { name: 'Русский' }).click();

  await expect(page.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Осмотр и консультация · 30 мин' })).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(lang).toBe('ru');

  // Выбор важнее языка Telegram: открыл снова — по-русски
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Запись к стоматологу' })).toBeVisible();
});

test('запись на кыргызском: «Сиздин жазылууңуз», дата по-кыргызски', async ({ page }) => {
  await saveContact(STRANGER, '996700112233');
  await db.query("insert into chat_language (telegram_id, lang) values ($1, 'ky')", [STRANGER]);
  const { rows: [p] } = await db.query(
    `insert into patient (full_name, phone, telegram_id) values ('Бакыт', '996700112233', $1) returning id`, [STRANGER]);
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bishkek' }).format(new Date(Date.now() + 86400000));
  await db.query(`insert into appointment (doctor_id, patient_id, start_at, end_at, status, source)
    values (1, $1, $2, $3, 'SCHEDULED', 'PATIENT')`, [p.id, new Date(`${day}T10:00:00+06:00`), new Date(`${day}T10:30:00+06:00`)]);
  await openApp(page, { initData: signInitData(STRANGER) });

  await expect(page.getByRole('heading', { name: 'Сиздин жазылууңуз' })).toBeVisible();
  await expect(page.locator('.booking-card .big')).toHaveText('📅 Эртең, саат 10:00');
  await expect(page.getByText('✅ Ырасталды')).toBeVisible();
});

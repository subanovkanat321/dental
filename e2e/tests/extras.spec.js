// Цены услуг, профиль врача, «Записать на следующий визит», оценки пациентов.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, addPatient, addAppointment, today, addDays, db, saveContact, signInitData, STRANGER,
  pressMainButton,
} from './helpers.js';

test.beforeEach(resetDb);
test.afterEach(async () => {
  await db.query("update service_type set price_som = null");
});

test('врач ставит цену — пациент видит «от 2 000 сом» на плитке', async ({ browser }) => {
  const doctor = await browser.newPage();
  await openApp(doctor);
  await doctor.getByRole('button', { name: 'Меню' }).click();
  await doctor.getByRole('button', { name: 'Услуги для самозаписи' }).click();
  await doctor.getByRole('button', { name: /^Профессиональная чистка/ }).click();
  await doctor.locator('input[type=number]').nth(1).fill('2000');
  await pressMainButton(doctor);
  await expect(doctor.getByRole('button', { name: /Профессиональная чистка · 60 мин · от 2\s000 сом/ })).toBeVisible();

  const patient = await browser.newPage();
  await openApp(patient, { initData: signInitData(STRANGER) });
  await expect(patient.getByRole('button', { name: /Профессиональная чистка/ }).locator('.price')).toHaveText(/от 2\s000 сом/);
});

test('профиль врача: имя и «о себе» — в шапке записи у пациента', async ({ browser }) => {
  await db.query('update doctor set telegram_id = 111 where id = 1'); // админ работает и врачом
  const doctor = await browser.newPage();
  await openApp(doctor);
  await doctor.getByRole('button', { name: 'Меню' }).click();
  await doctor.getByRole('button', { name: 'Мой профиль' }).click();
  await doctor.locator('input[type=text]').fill('Айгерим Садыкова');
  await doctor.locator('textarea').fill('Стоматолог-терапевт, 10 лет опыта');
  await pressMainButton(doctor);
  await expect(doctor.getByRole('heading', { name: 'Меню' })).toBeVisible();

  await saveContact(STRANGER, '996700112233');
  const patient = await browser.newPage();
  await openApp(patient, { initData: signInitData(STRANGER) });
  await expect(patient.locator('.hero-doctor')).toHaveText('Айгерим Садыкова');
  await expect(patient.locator('.hero')).toContainText('Стоматолог-терапевт, 10 лет опыта');
});

test('профиль врача: загруженное фото видно врачу и пациенту', async ({ browser }) => {
  await db.query('update doctor set telegram_id = 111 where id = 1');
  const doctor = await browser.newPage();
  await openApp(doctor);
  await doctor.getByRole('button', { name: 'Меню' }).click();
  await doctor.getByRole('button', { name: 'Мой профиль' }).click();
  // Картинка 2×2 PNG: браузер уменьшит и пережмёт её в JPEG перед отправкой
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFklEQVR4nGP8z8Dwn4GBgYGJgYGBAQAJBgEBnLtq9wAAAABJRU5ErkJggg==', 'base64');
  await doctor.locator('#photo-file').setInputFiles({ name: 'me.png', mimeType: 'image/png', buffer: png });
  await expect(doctor.getByAltText('Фото врача')).toBeVisible();
  const { rows } = await db.query('select photo_type, photo_version from doctor where id = 1');
  expect(rows[0]).toEqual({ photo_type: 'image/jpeg', photo_version: 1 });

  const patient = await browser.newPage();
  await openApp(patient, { initData: signInitData(STRANGER) });
  await expect(patient.locator('.hero img.hero-photo')).toHaveAttribute('src', '/doctors/1/photo?v=1');
});

test('«Приём состоялся» → «Записать на следующий визит»: тот же пациент и длительность, через неделю', async ({ page }) => {
  const id = await addAppointment(await addPatient('Айгуль'), today(), '09:00', { status: 'DONE', minutes: 45 });
  await openApp(page, { path: `/?appointment=${id}` });

  await page.getByRole('button', { name: 'Записать на следующий визит' }).click();

  await expect(page.getByRole('heading', { name: 'Новая запись' })).toBeVisible();
  await expect(page.getByText('Айгуль')).toBeVisible();
  await expect(page.locator('input[type=date]').first()).toHaveValue(addDays(today(), 7));
  await expect(page.getByRole('group', { name: 'Длительность' }).getByRole('button', { name: '45' })).toHaveAttribute('aria-pressed', 'true');
});

test('оценки: в карточке приёма и в «Статистике»', async ({ page }) => {
  const p = await addPatient('Бакыт');
  const id = await addAppointment(p, addDays(today(), -1), '10:00', { status: 'DONE' });
  await addAppointment(p, addDays(today(), -2), '10:00', { status: 'DONE' });
  await db.query("update appointment set rating = 5, review_asked_at = now() where id = $1", [id]);
  await db.query("update appointment set rating = 4, review_asked_at = now() where id <> $1", [id]);

  await openApp(page, { path: `/?appointment=${id}` });
  await expect(page.getByLabel('Оценка пациента: 5 из 5')).toBeVisible();

  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Статистика неявок' }).click();
  await expect(page.locator('.ratings-avg')).toHaveText('⭐ 4,5');
  await expect(page.locator('.ratings')).toContainText('2 оценки за 90 дней');
});

// Склад расходников и удаление: приём, все записи, услуга.
import { test } from '@playwright/test';
import {
  expect, openApp, resetDb, db, addPatient, addAppointment, tomorrow, pressMainButton,
} from './helpers.js';

test.beforeEach(resetDb);

async function openSupplies(page) {
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Склад' }).click();
}

test('добавить материал, «−1» в списке, у порога — «мало»', async ({ page }) => {
  await openSupplies(page);
  await page.getByRole('button', { name: '+ Добавить материал' }).click();
  await page.getByPlaceholder('Например: Карпула артикаин').fill('Карпула');
  await page.getByRole('button', { name: 'уп', exact: true }).click();
  await page.getByLabel('Сколько есть сейчас').fill('3');
  await page.getByLabel('Сколько оставить в запасе').fill('2');
  await pressMainButton(page);

  const row = page.locator('.row.supply', { hasText: 'Карпула' });
  await expect(row.locator('.stock')).toHaveText('3 уп');
  await row.getByRole('button', { name: 'Списать 1: Карпула' }).click();
  await expect(row.locator('.stock')).toHaveText('⚠️ 2 уп — мало');
  await expect(row).toHaveAttribute('data-tone', 'bad');
  const { rows } = await db.query('select quantity from supply');
  expect(rows[0].quantity).toBe(2);
});

test('поставка, списание больше остатка — ошибка, история', async ({ page }) => {
  await db.query("insert into supply (name, unit, quantity, min_quantity) values ('Перчатки', 'уп', 1, 0)");
  await openSupplies(page);
  await page.locator('.row.supply', { hasText: 'Перчатки' }).locator('.name').click();
  await expect(page.getByText('Осталось: 1 уп')).toBeVisible();

  await page.getByLabel('Сколько, уп').fill('5');
  await page.getByRole('button', { name: 'Пришла поставка' }).click();
  await expect(page.getByText('Осталось: 6 уп')).toBeVisible();

  await page.getByLabel('Сколько, уп').fill('10');
  await page.getByRole('button', { name: 'Списать' }).click();
  await expect(page.getByText('На складе только 6 уп')).toBeVisible();
  await expect(page.locator('.history li').first()).toContainText('+5 поставка');
});

test('кнопка из бота ?supply=ID открывает материал; админ удаляет его двумя нажатиями', async ({ page }) => {
  const { rows: [s] } = await db.query("insert into supply (name, quantity) values ('Боры', 0) returning id");
  await openApp(page, { path: `/?supply=${s.id}` });
  await expect(page.getByText('❌ Закончился')).toBeVisible();

  const remove = page.getByRole('button', { name: 'Удалить материал' });
  await remove.click();
  await expect(page.getByRole('button', { name: 'Точно? Нажмите ещё раз' })).toBeVisible();
  await page.getByRole('button', { name: 'Точно? Нажмите ещё раз' }).click();
  await expect(page.getByRole('heading', { name: 'Склад' })).toBeVisible();
  expect((await db.query('select count(*)::int as n from supply')).rows[0].n).toBe(0);
});

test('админ удаляет запись из карточки', async ({ page }) => {
  const id = await addAppointment(await addPatient('Айгуль'), tomorrow(), '10:00');
  await openApp(page, { path: `/?appointment=${id}` });

  await page.getByRole('button', { name: 'Удалить запись' }).click();
  await page.getByRole('button', { name: 'Точно? Нажмите ещё раз' }).click();

  await expect(page.locator('#toast')).toHaveText('🗑️ Запись удалена');
  expect((await db.query('select count(*)::int as n from appointment')).rows[0].n).toBe(0);
});

test('«Начать с чистого листа»: без слова — ошибка, с «УДАЛИТЬ» — записей нет, пациенты остались', async ({ page }) => {
  const p = await addPatient('Айгуль');
  await addAppointment(p, tomorrow(), '10:00');
  await addAppointment(p, tomorrow(), '11:00');
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: /Адрес и техподдержка не заданы/ }).click();

  await page.getByRole('button', { name: 'Удалить все записи' }).click();
  await expect(page.locator('.danger-zone .error')).not.toBeEmpty();
  await page.getByLabel('Слово для подтверждения').fill('УДАЛИТЬ');
  await page.getByRole('button', { name: 'Удалить все записи' }).click();

  await expect(page.locator('#toast')).toHaveText('🗑️ Удалено записей: 2');
  expect((await db.query('select count(*)::int as n from appointment')).rows[0].n).toBe(0);
  expect((await db.query('select count(*)::int as n from patient')).rows[0].n).toBe(1);
});

test('удалить услугу без записей — её больше нет в списке', async ({ page }) => {
  await db.query("insert into service_type (name, duration_minutes) values ('Тест-услуга', 30)");
  await openApp(page);
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: 'Услуги для самозаписи' }).click();
  await page.getByRole('button', { name: /^Тест-услуга/ }).click();
  await page.getByRole('button', { name: 'Удалить услугу' }).click();
  await page.getByRole('button', { name: 'Точно? Нажмите ещё раз' }).click();

  await expect(page.locator('#toast')).toHaveText('🗑️ Услуга удалена');
  await expect(page.getByRole('button', { name: /^Тест-услуга/ })).toHaveCount(0);
});

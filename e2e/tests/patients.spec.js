// Карточка пациента, лист ожидания, статистика, профосмотры.
import { test } from '@playwright/test';
import { expect, openApp, resetDb, addPatient, addAppointment, addDays, today, tomorrow, pressMainButton, calls } from './helpers.js';

test.beforeEach(resetDb);

async function openMenu(page, item) {
  await page.getByRole('button', { name: 'Меню' }).click();
  await page.getByRole('button', { name: item }).click();
}

test('карточка пациента: счётчики, история, правка телефона', async ({ page }) => {
  const p = await addPatient('Айгуль Т.', '996555123456');
  await addAppointment(p, addDays(today(), -30), '10:00', { status: 'DONE' });
  await addAppointment(p, addDays(today(), -20), '10:00', { status: 'NO_SHOW' });
  await addAppointment(p, tomorrow(), '10:00');
  await openApp(page);

  await openMenu(page, 'Пациенты и лист ожидания');
  await page.getByLabel('Поиск пациента').fill('Айгуль');
  await page.getByRole('button', { name: /^Айгуль Т\./ }).click();
  await expect(page.getByText('Пришёл: 1 · не пришёл: 1 · отменял: 0')).toBeVisible();
  await expect(page.locator('h2:has-text("Впереди") + ul .row')).toHaveCount(1);
  await expect(page.locator('h2:has-text("История") + ul .row')).toHaveCount(2);

  await page.getByRole('button', { name: 'Изменить имя или телефон' }).click();
  await page.locator('input[type=tel]').fill('12');
  await pressMainButton(page);
  await expect(page.getByText(/Номер в формате/)).toBeVisible();
  await page.locator('input[type=tel]').fill('0700 99 88 77');
  await pressMainButton(page);
  await expect(page.getByText('+996 700 998 877')).toBeVisible();
});

test('лист ожидания: «ждут» у окна раньше приёма → «Перенести сюда»', async ({ page }) => {
  const p = await addPatient('Айгуль Т.');
  await addAppointment(p, tomorrow(), '15:00');
  await openApp(page);

  await openMenu(page, 'Пациенты и лист ожидания');
  await page.getByLabel('Поиск пациента').fill('Айгуль');
  await page.getByRole('button', { name: /^Айгуль Т\./ }).click();
  await page.getByRole('button', { name: /В лист ожидания/ }).click();
  await expect(page.getByRole('button', { name: /Убрать из листа ожидания/ })).toBeVisible();

  await page.goto('/?day=tomorrow');
  await expect(page.locator('.row.free').first()).toContainText('ждут: 1');
  await expect(page.locator('.row.free').last()).not.toContainText('ждут'); // окно после её приёма
  await page.locator('.row.free').first().click();
  const candidate = page.locator('.candidate', { hasText: 'Айгуль Т.' });
  await candidate.getByRole('button', { name: 'WhatsApp' }).click();
  const link = (await calls(page)).find((c) => c[0] === 'openLink')[1];
  expect(new URL(link).searchParams.get('text')).toContain('Освободилось время у стоматолога: завтра в 09:00');
  await candidate.getByRole('button', { name: 'Перенести сюда' }).click();

  await expect(page.locator('.row', { hasText: 'Айгуль Т.' })).toContainText('09:00');
  await expect(page.locator('.row', { hasText: 'Айгуль Т.' })).toHaveCount(1); // перенесён, а не продублирован
  await expect(page.locator('.waiting')).toHaveCount(0);
});

test('статистика: неявки прошлой недели и среднее', async ({ page }) => {
  const p = await addPatient('Айгуль Т.');
  await addAppointment(p, addDays(today(), -7), '10:00', { status: 'DONE' });
  await addAppointment(p, addDays(today(), -7), '11:00', { status: 'NO_SHOW' });
  await openApp(page);

  await openMenu(page, 'Статистика неявок');
  await expect(page.getByText('Не пришли: 1 (50%)')).toBeVisible();
  await expect(page.getByText('В среднем 1 неявка в неделю. До приложения было 2–3.')).toBeVisible();
});

test('профосмотр: WhatsApp зовёт и отмечает «позвали»', async ({ page }) => {
  const p = await addPatient('Бакыт М.', '996700112233');
  await addAppointment(p, addDays(today(), -200), '10:00', { status: 'DONE' });
  await openApp(page);

  await openMenu(page, 'Позвать на профосмотр');
  const row = page.locator('.candidate', { hasText: 'Бакыт М.' });
  await row.getByRole('button', { name: 'WhatsApp' }).click();
  const link = (await calls(page)).find((c) => c[0] === 'openLink')[1];
  expect(new URL(link).searchParams.get('text')).toContain('пора на профилактический осмотр');

  await expect.poll(async () => {
    await page.reload();
    await openMenu(page, 'Позвать на профосмотр');
    return page.locator('.candidate', { hasText: 'Бакыт М.' }).innerText();
  }).toContain('позвали сегодня');
});

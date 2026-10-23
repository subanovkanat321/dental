// Лента дней в шапке: листается вправо, число записей у дня, выбор любой даты.
import { test } from '@playwright/test';
import { expect, openApp, resetDb, addPatient, addAppointment, addDays, today } from './helpers.js';

test.beforeEach(resetDb);

test('лента дней: число записей у дня, нажатие открывает день', async ({ page }) => {
  const later = addDays(today(), 5);
  const p = await addPatient('Айгуль');
  await addAppointment(p, later, '10:00');
  await addAppointment(await addPatient('Бакыт'), later, '11:00', { status: 'REQUESTED' });
  await openApp(page);

  const chip = page.locator('.strip-day').nth(5);
  await expect(chip).toHaveAttribute('aria-label', /: 2 записи$/);
  await expect(chip.locator('.badge')).toHaveText('2');
  await expect(chip.locator('.badge')).toHaveClass(/requested/);
  await expect(page.locator('.strip-day').first()).toHaveAttribute('aria-pressed', 'true');

  await chip.click();
  await expect(page.locator('.strip-day').nth(5)).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('Айгуль')).toBeVisible();
});

test('«📅 Дата»: любая дата, лента перестраивается вокруг неё', async ({ page }) => {
  const far = addDays(today(), 60);
  await addAppointment(await addPatient('Нурлан'), far, '12:00');
  await openApp(page);

  await page.locator('.pick-date input').fill(far);

  await expect(page.getByText('Нурлан')).toBeVisible();
  await expect(page.locator('.strip-day').nth(3)).toHaveAttribute('aria-pressed', 'true');
});

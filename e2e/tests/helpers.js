// Общее для тестов: вход как в Telegram, заглушка Telegram.WebApp, подготовка данных в базе.
import crypto from 'node:crypto';
import pg from 'pg';
import { expect } from '@playwright/test';

export { expect };

const TOKEN = process.env.E2E_BOT_TOKEN || 'local-dev-token';
export const DOCTOR = Number(process.env.E2E_USER_ID || 1);
export const STRANGER = 999999;

export const db = new pg.Pool({
  connectionString: process.env.DATABASE_URL || 'postgres://dental:dental@localhost:5433/dental',
});

// Даты по Бишкеку (UTC+6 круглый год) — своя реализация, независимая от state.js приложения:
// тест сверяет приложение с ней, а не с его же кодом
export function today() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bishkek' }).format(new Date());
}

export function addDays(date, days) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export const tomorrow = () => addDays(today(), 1);

/** initData, подписанная токеном бота, — как её подписывает Telegram (InitDataValidator). */
export function signInitData(userId = DOCTOR, authDate = Math.floor(Date.now() / 1000)) {
  const fields = { auth_date: String(authDate), query_id: 'e2e', user: JSON.stringify({ id: userId, first_name: 'E2E' }) };
  const dataCheckString = Object.keys(fields).sort().map((k) => `${k}=${fields[k]}`).join('\n');
  const secret = crypto.createHmac('sha256', 'WebAppData').update(TOKEN).digest();
  const hash = crypto.createHmac('sha256', secret).update(dataCheckString).digest('hex');
  return new URLSearchParams({ ...fields, hash }).toString();
}

export async function resetDb() {
  await db.query('truncate waitlist_entry, appointment, patient, telegram_contact restart identity cascade');
  await db.query('delete from supply');
  // Врачи — как после миграции V11: один врач №1 без Telegram
  await db.query('delete from time_off where doctor_id is not null');
  await db.query('delete from doctor_hours');
  await db.query('delete from doctor where id <> 1');
  await db.query(`update doctor set full_name = 'Врач', about = null, telegram_id = null, photo = null,
    photo_type = null, photo_version = 0, active = true where id = 1`);
  // Перерывы — как после миграции V7: только пятничный джума-намаз
  await db.query('delete from time_off');
  await db.query(`insert into time_off (weekday, start_time, end_time, title, emoji)
    values (5, '13:00', '15:00', 'Джума-намаз', '🕌')`);
}

/** Ближайшая дата с этим днём недели (1 — пн … 7 — вс), не раньше чем через minDays дней. */
export function nextWeekday(weekday, minDays = 0) {
  for (let i = minDays; ; i += 1) {
    const d = addDays(today(), i);
    if ((new Date(`${d}T00:00:00Z`).getUTCDay() || 7) === weekday) return d;
  }
}

export async function addPatient(fullName, phone = `99655${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`) {
  const { rows } = await db.query('insert into patient (full_name, phone) values ($1, $2) returning id', [fullName, phone]);
  return rows[0].id;
}

/** Приём на дату и время по Бишкеку. */
export async function addAppointment(patientId, date, time, { status = 'SCHEDULED', minutes = 30, reply = 'NONE', reminded = false, doctor = 1 } = {}) {
  const start = new Date(`${date}T${time}:00+06:00`);
  const end = new Date(start.getTime() + minutes * 60000);
  const { rows } = await db.query(`insert into appointment (doctor_id, patient_id, start_at, end_at, status, reply, reminder_opened_at)
    values ($7, $1, $2, $3, $4, $5, $6) returning id`, [patientId, start, end, status, reply, reminded ? new Date() : null, doctor]);
  return rows[0].id;
}

/**
 * Открыть Mini App «внутри Telegram»: заглушка window.Telegram.WebApp с подписанной initData,
 * MainButton, BackButton и openLink, которые записывают вызовы. Настоящий скрипт telegram.org
 * заблокирован — иначе он заменил бы заглушку.
 */
export async function openApp(page, { path = '/', initData = signInitData(), shareContact } = {}) {
  await page.route('https://telegram.org/**', (route) => route.abort());
  if (shareContact) await page.exposeFunction('shareContact', shareContact);
  await page.addInitScript((data) => {
    window.__calls = [];
    const button = () => ({
      text: '', visible: false, handlers: [],
      setText(t) { this.text = t; }, onClick(h) { this.handlers.push(h); },
      offClick(h) { this.handlers = this.handlers.filter((x) => x !== h); },
      show() { this.visible = true; }, hide() { this.visible = false; },
      enable() {}, disable() {}, showProgress() {}, hideProgress() {},
    });
    const originalFetch = window.fetch;
    window.fetch = (url, options) => {
      if (String(url).includes('reminder-opened')) window.__calls.push(['mark', String(url), Boolean(options?.keepalive)]);
      return originalFetch.call(window, url, options);
    };
    window.Telegram = {
      WebApp: {
        initData: data, colorScheme: 'light', platform: 'ios',
        ready() {}, expand() {}, onEvent() {},
        MainButton: button(), BackButton: button(),
        HapticFeedback: { notificationOccurred() {} },
        openLink(url) { window.__calls.push(['openLink', url]); },
        // «Поделиться номером»: номер получает бот — в тесте его кладёт в базу shareContact (exposeFunction)
        requestContact(callback) {
          if (window.shareContact) window.shareContact().then(() => callback(true));
          else callback(false);
        },
      },
    };
  }, initData);
  await page.goto(path);
}

/** Главная кнопка Telegram (MainButton): текст и нажатие. */
export async function mainButtonText(page) {
  return page.evaluate(() => (window.Telegram.WebApp.MainButton.visible ? window.Telegram.WebApp.MainButton.text : null));
}

export async function pressMainButton(page) {
  await page.evaluate(() => window.Telegram.WebApp.MainButton.handlers.forEach((h) => h()));
}

export async function pressBackButton(page) {
  await page.evaluate(() => window.Telegram.WebApp.BackButton.handlers.forEach((h) => h()));
}

/** Вызовы отметки и openLink по порядку. */
export async function calls(page) {
  return page.evaluate(() => window.__calls);
}

/** Кнопка сетки свободного времени с точным временем. */
export function slot(page, time) {
  return page.locator('.slot', { hasText: new RegExp(`^${time}$`) });
}

/** Номер, которым человек поделился в боте, — так его сохраняет BotUpdateHandler. */
export async function saveContact(telegramId, phone, firstName = 'Пациент') {
  await db.query(`insert into telegram_contact (telegram_id, phone, first_name) values ($1, $2, $3)
    on conflict (telegram_id) do update set phone = excluded.phone`, [telegramId, phone, firstName]);
}

/** Второй врач (как из «Меню → Врачи»): id. */
export async function addDoctor(fullName, telegramId = null, about = null) {
  const { rows } = await db.query('insert into doctor (full_name, telegram_id, about) values ($1, $2, $3) returning id',
    [fullName, telegramId, about]);
  return Number(rows[0].id);
}

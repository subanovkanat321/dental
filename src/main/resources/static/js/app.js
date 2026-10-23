// Mini App врача: «День», карточка приёма, «Новая запись». Без сборки и фреймворка (R2):
// три экрана и десяток запросов не окупают React и node в сборке.
import {
  rowState, todayIn, addDays, toInstant, timeOf, dateOf, minutesBetween, dayTitle, canMarkVisit,
  formatPhone, looksLikePhone, dayItems, windowDuration,
  reminderText, whatsappUrl, canRemind, readyCount, nextToRemind, fitsWindow, freeSlotText,
  weekLabel, noShowPercent, averageNoShows, noShowsPhrase, recallText, agoText, stripDays, recordsPhrase, timeOffWhen, priceText, ratingsPhrase, initials, sentText,
} from './state.js';
import { startPatient } from './patient.js';

const tg = window.Telegram?.WebApp;
const DURATIONS = [15, 30, 45, 60, 90];
const SESSION_EXPIRED = 'Сессия устарела — откройте заново из бота';

let initData = tg?.initData || '';
let settings = { clinicSignature: '' };

/**
 * Врачи кабинета — для сотрудника: кто я (mine), чей «День» сейчас открыт (current).
 * У каждого врача своё кресло; переключатель в шапке «Дня» — посмотреть коллегу.
 */
const team = { doctors: [], admin: false, mine: null, current: null, clinic: {} };

function doctorById(id) {
  return team.doctors.find((d) => d.id === id);
}

/** Менять расписание врача может он сам или админ; коллега — только смотрит (проверяет и сервер). */
function canEdit(doctorId) {
  return team.admin || doctorId === team.mine;
}

/** Кружок врача: фото или инициалы. */
function avatar(d, size = 28) {
  return d?.photoUrl
    ? h('img', { class: 'avatar', src: d.photoUrl, alt: '', width: size, height: size })
    : h('span', { class: 'avatar', 'aria-hidden': 'true', style: `width:${size}px;height:${size}px` }, initials(d?.fullName || '?'));
}

/** «Айгерим Садыкова» → «Айгерим»: переключатель узкий. */
function shortName(name) {
  return name.trim().split(/\s+/)[0];
}

// ---------- DOM ----------

/**
 * Элемент из тега, свойств и детей. Текст вставляется как текст, не как HTML: имя пациента
 * «<img onerror=…>» останется именем, а не выполнится (поэтому нигде нет innerHTML).
 */
function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'class') el.className = value;
    else if (key in el && key !== 'list') el[key] = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  el.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  // Кнопке-действию — эмодзи-иконка по подписи. aria-hidden: скринридер читает только подпись
  const icon = /\bbtn\b/.test(props.class || '') && typeof children[0] === 'string' && ICONS[children[0]];
  if (icon) el.prepend(h('span', { class: 'ico', 'aria-hidden': 'true' }, icon));
  return el;
}

/** Иконки кнопок — в одном месте, чтобы не расставлять их по всему коду. */
const ICONS = {
  Подтвердить: '✅',
  Перенести: '🗓️',
  'Отклонить заявку': '🚫',
  'Напомнить в Telegram': '✈️',
  'Напомнить в WhatsApp': '💬',
  'Написать в WhatsApp': '💬',
  Позвонить: '📞',
  Пришёл: '✅',
  'Не пришёл': '🚷',
  'Отменить приём': '✖️',
  Восстановить: '↩️',
  'Исправить: не пришёл': '🚷',
  'Исправить: пришёл': '✅',
  Придёт: '👍',
  Перенос: '🔄',
  'Нет ответа': '📵',
  'Пациенты и лист ожидания': '👥',
  'Позвать на профосмотр': '🔔',
  'Статистика неявок': '📊',
  'Услуги для самозаписи': '🦷',
  'Перерывы и выходные': '☕',
  'Мой профиль': '🧑‍⚕️',
  'Врачи': '👩‍⚕️',
  'О клинике': '🏥',
  'Техподдержка': '🛟',
  'Написать в Telegram': '✈️',
  'Открыть в 2ГИС': '🗺️',
  'Фото и профиль': '📷',
  'Записать снова': '➕',
  'Записать на следующий визит': '➕',
  'Изменить имя или телефон': '✏️',
  'Поделиться номером': '📱',
  'Отменить запись': '✖️',
  Повторить: '🔄',
};

/** Значок SVG (белый, 24×24) для круглых кнопок WhatsApp и Telegram. */
function svgIcon(path, filled) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const shape = document.createElementNS(ns, 'path');
  shape.setAttribute('d', path);
  shape.setAttribute('fill', filled ? 'currentColor' : 'none');
  if (!filled) {
    shape.setAttribute('stroke', 'currentColor');
    shape.setAttribute('stroke-width', '2');
    shape.setAttribute('stroke-linejoin', 'round');
  }
  svg.append(shape);
  return svg;
}

const CHAT_ICON = 'M12 3a9 9 0 0 0-7.8 13.5L3 21l4.6-1.2A9 9 0 1 0 12 3z';
const PLANE_ICON = 'M21.4 3.6 2.9 10.7c-1 .4-1 1.5 0 1.8l4.6 1.4 1.8 5.6c.2.7 1.1.9 1.6.4l2.6-2.4 4.8 3.5c.6.4 1.4.1 1.6-.6l3.2-15.2c.2-.9-.7-1.6-1.5-1.2zM9.6 14.2l8.2-7.3-6.4 8.5-.3 3z';

const app = document.getElementById('app');
function render(...nodes) {
  app.replaceChildren(...nodes);
  window.scrollTo(0, 0);
}

// ---------- API ----------

class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || `HTTP ${status}`);
    this.status = status;
    this.problem = problem || {};
  }

  get code() { return this.problem.code; }

  /** Ошибки полей из 422: { phone: 'Номер в формате…' } */
  get fields() {
    return Object.fromEntries((this.problem.errors || []).map((e) => [e.field, e.message]));
  }
}

/** Каждый запрос несёт initData: по нему сервер узнаёт врача (InitDataAuthFilter). */
async function api(method, path, body) {
  const headers = { Authorization: `tma ${initData}` };
  // FormData (загрузка фото) — как есть: браузер сам поставит multipart с границей
  const form = body instanceof FormData;
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, body: body === undefined || form ? body : JSON.stringify(body) });
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

// ---------- Кнопки Telegram ----------

const insideTelegram = Boolean(tg?.initData);
const fallback = document.getElementById('main-button-fallback');

/** Главная кнопка внизу: MainButton в Telegram, своя кнопка в браузере на ноутбуке. */
const mainButton = {
  handler: null,
  set(text, handler) {
    this.hide();
    this.handler = handler;
    if (insideTelegram) {
      tg.MainButton.setText(text);
      tg.MainButton.onClick(handler);
      tg.MainButton.enable();
      tg.MainButton.show();
    } else {
      const button = fallback.querySelector('button');
      button.textContent = text;
      button.onclick = handler;
      button.disabled = false;
      fallback.hidden = false;
    }
  },
  hide() {
    if (this.handler && insideTelegram) {
      tg.MainButton.offClick(this.handler);
      tg.MainButton.hideProgress();
      tg.MainButton.hide();
    }
    fallback.hidden = true;
    this.handler = null;
  },
  busy(on) {
    if (insideTelegram) {
      if (on) { tg.MainButton.showProgress(); tg.MainButton.disable(); }
      else { tg.MainButton.hideProgress(); tg.MainButton.enable(); }
    } else {
      fallback.querySelector('button').disabled = on;
    }
  },
};

/** «Назад» — системная BackButton Telegram; в браузере — ссылка вверху экрана. */
const backButton = {
  handler: null,
  set(handler) {
    this.hide();
    this.handler = handler;
    if (insideTelegram) {
      tg.BackButton.onClick(handler);
      tg.BackButton.show();
    }
  },
  hide() {
    if (this.handler && insideTelegram) {
      tg.BackButton.offClick(this.handler);
      tg.BackButton.hide();
    }
    this.handler = null;
  },
  /** Видимая кнопка «‹ Назад» для браузера вне Telegram. */
  link() {
    return insideTelegram ? null
      : h('button', { type: 'button', class: 'arrow', onclick: () => this.handler?.(), 'aria-label': 'Назад' }, '‹');
  },
};

function haptic(type) {
  tg?.HapticFeedback?.notificationOccurred(type);
}

let toastTimer;
/** Плашка внизу: «Отменено · Вернуть» на 5 с (DD7). */
/**
 * Плашка: «Отменено · Вернуть» внизу на 5 с (DD7). tone 'ok' / 'bad' — итог отправки: заметная,
 * сверху экрана, зелёная или красная, — чтобы было видно, дошло сообщение или нет.
 */
function toast(text, actionText, action, tone = '') {
  const el = document.getElementById('toast');
  clearTimeout(toastTimer);
  el.className = tone ? `toast-${tone}` : '';
  // Без кнопки — только текст: replaceChildren(null) вставил бы слово «null»
  el.replaceChildren(...[h('span', {}, text), actionText ? h('button', {
    type: 'button',
    onclick: () => { el.hidden = true; action(); },
  }, actionText) : null].filter(Boolean));
  el.hidden = false;
  toastTimer = setTimeout(() => { el.hidden = true; }, 5000);
}

function hideToast() {
  clearTimeout(toastTimer);
  document.getElementById('toast').hidden = true;
}

// ---------- Общие ошибки ----------

/** 401 и 403 — весь экран: дальше работать нельзя. Остальное решает экран сам. */
function fatal(error) {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    mainButton.hide();
    backButton.hide();
    render(h('div', { class: 'message' }, error.status === 401 ? SESSION_EXPIRED : error.message));
    return true;
  }
  return false;
}

function errorText(error) {
  return error instanceof ApiError ? error.message : 'Нет связи с сервером';
}

// Номер текущего экрана: ответ, пришедший после ухода с экрана, не рисуется поверх нового
let screenId = 0;

// ---------- Напоминание в WhatsApp (D14, DD6, ER2, ER3) ----------

const pressed = new Set(); // id приёмов, по которым в этой сессии уже нажимали WhatsApp
const unsaved = new Set(); // id, у которых отметка «напомнили» не дошла до сервера

function openLink(url) {
  if (insideTelegram) tg.openLink(url);
  else window.open(url, '_blank', 'noopener');
}

/** Отметка «напоминание открыто». keepalive: браузер доставит запрос, даже если окно ушло в фон. */
function markReminder(a, onChange, previous = a.reminderOpenedAt) {
  fetch(`/api/appointments/${a.id}/reminder-opened`, {
    method: 'POST', keepalive: true, headers: { Authorization: `tma ${initData}` },
  }).then((response) => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    unsaved.delete(a.id);
  }).catch(() => {
    // Сервер не знает об отметке — и «Готово X из N» не должен её считать
    a.reminderOpenedAt = previous;
    unsaved.add(a.id);
    onChange();
  });
}

/**
 * Кнопка WhatsApp. В одном обработчике и без await: сначала запрос-отметка, сразу за ним
 * openLink. Telegram открывает ссылку, только если её открыли прямо из нажатия; await перед
 * openLink на части телефонов «съел» бы нажатие, и кнопка молча не работала бы (D14).
 */
function remind(a, onChange) {
  pressed.add(a.id);
  const previous = a.reminderOpenedAt;
  markReminder(a, onChange, previous);
  openLink(whatsappUrl(a.patient.phone, reminderText(a, settings.clinicSignature)));
  // Локально сразу «Ждём ответа»; сервер поставит своё время отметки
  if (!a.reminderOpenedAt) a.reminderOpenedAt = new Date().toISOString();
  onChange();
}

/**
 * «Напомнить в Telegram» — пациенту, который записывался через бота. Сообщение с кнопками
 * «Приду» / «Нужно перенести» отправляет сервер от имени бота: врач никуда не переходит, а
 * ответ пациента сам появится в карточке. Отметку «напомнили» сервер ставит только после
 * доставки, поэтому здесь ждём ответ, а не отмечаем заранее, как у WhatsApp.
 */
const sending = new Set(); // id, по которым напоминание в Telegram уже отправляется: двойное нажатие — одно сообщение

async function remindTelegram(a, onChange, button) {
  if (sending.has(a.id)) return;
  sending.add(a.id);
  pressed.add(a.id);
  // Пока сервер отправляет (около секунды) — видно, что кнопка нажата
  const label = button ? [...button.childNodes] : [];
  if (button) {
    button.disabled = true;
    button.classList.add('sending');
    if (!button.classList.contains('wa')) button.replaceChildren('⏳ Отправляем…');
  }
  try {
    Object.assign(a, await api('POST', `/api/appointments/${a.id}/remind-telegram`));
    haptic('success');
    toast(`✅ Отправлено в Telegram: ${a.patient.fullName}`, null, null, 'ok');
  } catch (e) {
    if (fatal(e)) return;
    pressed.delete(a.id);
    haptic('error');
    toast(`⚠️ ${errorText(e)}`, null, null, 'bad');
    if (button) {
      button.disabled = false;
      button.classList.remove('sending');
      if (!button.classList.contains('wa')) button.replaceChildren(...label);
    }
  } finally {
    sending.delete(a.id);
  }
  onChange();
}

/** Быстрая кнопка в строке и «Напомнить следующему»: записавшимся через бота — в бот, остальным — WhatsApp. */
function remindBest(a, onChange, button) {
  if (a.patient.telegram) remindTelegram(a, onChange, button);
  else remind(a, onChange);
}

/**
 * «Позвонить». Внутри Telegram ссылка tel: не срабатывает, а openLink принимает только http(s):
 * открываем свою страницу call.html (рядом с app.js, с той же версией), она передаёт tel:
 * телефону. Номер — во фрагменте #…: он не уходит на сервер. Вне Telegram — обычная ссылка.
 */
function callButton(phone) {
  const tel = `tel:+${phone}`;
  return h('a', {
    class: 'btn', href: tel,
    onclick: (e) => {
      if (!insideTelegram) return;
      e.preventDefault();
      openLink(`${new URL('../call.html', import.meta.url)}#${phone}`);
    },
  }, 'Позвонить');
}

// ---------- «День» ----------

async function dayScreen(date, flashId) {
  const id = ++screenId;
  const doctorId = team.current;
  hideToast();
  backButton.hide();
  if (canEdit(doctorId)) mainButton.set('Новая запись', () => newScreen({ date }));
  else mainButton.hide();

  const today = todayIn();
  const dateInput = h('input', {
    type: 'date', value: date, 'aria-label': 'Выбрать дату',
    onchange: (e) => e.target.value && dayScreen(e.target.value),
  });
  // Лента дней: листается вправо; число — сколько записей, оранжевое — есть заявки
  const days = stripDays(date, today);
  const chips = days.map((d) => h('button', {
    type: 'button', class: `strip-day${d.today ? ' today' : ''}${d.weekend ? ' weekend' : ''}`,
    'aria-pressed': String(d.date === date), 'aria-label': `${d.weekday}, ${d.day} ${d.month}`,
    onclick: () => dayScreen(d.date),
  }, h('span', { class: 'wd' }, d.today ? 'Сегодня' : d.weekday), h('span', { class: 'dn' }, String(d.day)),
  h('span', { class: 'badge', 'aria-hidden': 'true' })));
  const strip = h('div', { class: 'strip', role: 'group', 'aria-label': 'Дни' }, chips);
  // Врачи кабинета: у каждого своё кресло — переключиться на коллегу
  const doctorSwitch = team.doctors.length > 1 ? h('div', { class: 'doctor-switch', role: 'group', 'aria-label': 'Врач' },
    team.doctors.map((d) => h('button', {
      type: 'button', 'aria-pressed': String(d.id === doctorId),
      onclick: () => { team.current = d.id; dayScreen(date); },
    }, avatar(d, 24), h('span', {}, d.id === team.mine ? `${shortName(d.fullName)} (я)` : shortName(d.fullName))))) : null;
  const head = h('header', { class: 'day-head' },
    doctorSwitch,
    canEdit(doctorId) ? null : h('div', { class: 'readonly-note' }, '👀 Расписание коллеги — только просмотр'),
    h('div', { class: 'day-title' },
      h('button', { type: 'button', class: 'arrow', 'aria-label': 'Предыдущий день', onclick: () => dayScreen(addDays(date, -1)) }, '‹'),
      h('label', {}, h('h1', {}, dayTitle(date)), dateInput),
      h('button', { type: 'button', class: 'arrow', 'aria-label': 'Следующий день', onclick: () => dayScreen(addDays(date, 1)) }, '›')),
    strip,
    h('div', { class: 'segments' },
      h('button', { type: 'button', 'aria-pressed': String(date === today), onclick: () => dayScreen(today) }, 'Сегодня'),
      // Невидимое поле даты поверх кнопки: нажатие сразу открывает системный календарь (и на iPhone)
      h('label', { class: 'pick-date' }, '📅 Дата', h('input', {
        type: 'date', value: date, 'aria-label': 'Выбрать дату',
        onchange: (e) => e.target.value && dayScreen(e.target.value),
      })),
      h('button', { type: 'button', onclick: () => menuScreen(() => dayScreen(date)) }, 'Меню')));

  render(head, ...Array.from({ length: 6 }, () => h('div', { class: 'skeleton' })));
  chips[days.findIndex((d) => d.date === date)]?.scrollIntoView({ inline: 'center', block: 'nearest' });
  // Числа в ленте — отдельным запросом: не нужны, чтобы показать день, и не держат его
  api('GET', `/api/appointments/counts?from=${days[0].date}&days=${days.length}&doctorId=${doctorId}`).then((counts) => {
    if (id !== screenId) return;
    counts.forEach((c, i) => {
      if (!chips[i] || !c.booked) return;
      const badge = chips[i].querySelector('.badge');
      badge.textContent = String(c.booked);
      badge.classList.toggle('requested', c.requested > 0);
      chips[i].setAttribute('aria-label', `${chips[i].getAttribute('aria-label')}: ${recordsPhrase(c.booked)}`);
    });
  }).catch(() => {});

  let appointments;
  let slots;
  let waitlist;
  try {
    // Параллельно: приёмы дня, свободные окна (DD2) и лист ожидания — кого позвать в окно
    [appointments, slots, waitlist] = await Promise.all([
      api('GET', `/api/appointments?date=${date}&doctorId=${doctorId}`),
      api('GET', `/api/slots?date=${date}&doctorId=${doctorId}`),
      api('GET', '/api/waitlist'),
    ]);
  } catch (error) {
    if (id !== screenId || fatal(error)) return;
    render(head, h('div', { class: 'message' }, h('div', {}, errorText(error)),
      h('button', { type: 'button', class: 'btn primary', onclick: () => dayScreen(date) }, 'Повторить')));
    return;
  }
  if (id !== screenId) return;

  const note = h('div', { class: 'hint day-note' });
  head.append(note);
  const list = h('ul', { class: 'rows', role: 'list' });
  let flash = flashId; // подсветить новую строку один раз, а не при каждой перерисовке

  /** Перерисовать список из уже загруженных данных — после нажатия WhatsApp, без запроса. */
  function draw() {
    if (id !== screenId) return;
    const now = new Date();
    const isTomorrow = date === addDays(todayIn(now), 1);
    // Нерабочий день (WORK_DAYS): «Выходной», строк «свободно» нет (ER5)
    const { ready, total } = readyCount(appointments);
    note.textContent = [!slots.workingDay && (slots.dayOff ? `${slots.dayOff.emoji} ${slots.dayOff.title}` : 'Выходной'), isTomorrow && total > 0 && `Готово ${ready} из ${total}`]
      .filter(Boolean).join(' · ');
    const items = dayItems(appointments, slots.free, slots.breaks);
    const next = date === todayIn(now)
      ? appointments.find((a) => a.status === 'SCHEDULED' && new Date(a.startAt) > now) : null;
    list.replaceChildren(...items.map((item) => (item.pause ? pauseRow(item) : item.free
      ? freeRow(date, item, waitlist, doctorId) : row(item, item === next, item.id === flash, now, draw))));
    flash = null;

    // Вечерний обход (DD6): на «Завтра» главная кнопка ведёт к следующему, кому не напомнили
    const nextUp = isTomorrow && canEdit(doctorId) ? nextToRemind(appointments, pressed, now) : null;
    if (!canEdit(doctorId)) {
      mainButton.hide();
    } else if (nextUp) {
      mainButton.set(`Напомнить следующему (${short(nextUp.patient.fullName)}, ${timeOf(nextUp.startAt)})`,
        () => {
          // Telegram — запрос к серверу: главная кнопка с «часиками», пока он идёт
          if (nextUp.patient.telegram) mainButton.busy(true);
          Promise.resolve(remindBest(nextUp, draw)).finally(() => mainButton.busy(false));
        });
    } else {
      mainButton.set('Новая запись', () => newScreen({ date }));
    }
  }

  if (appointments.length === 0 && slots.free.length === 0 && slots.breaks.length === 0) {
    render(head, h('div', { class: 'message hint' },
      h('div', { class: 'empty-emoji', 'aria-hidden': 'true' }, slots.dayOff?.emoji || (slots.workingDay ? '🗓️' : '🌴')),
      slots.dayOff ? `${slots.dayOff.title} — записей нет` : 'На этот день записей нет'));
    note.textContent = slots.workingDay || slots.dayOff ? '' : 'Выходной';
    return;
  }
  render(head, list);
  draw();
  if (flashId) haptic('success');
}

/** Имя для подписи главной кнопки: она узкая. */
function short(name) {
  return name.length > 18 ? `${name.slice(0, 17)}…` : name;
}

/**
 * «свободно 11:00–12:00»: нажатие — «Новая запись» с этим временем (DD2). Если кто-то из листа
 * ожидания помещается в окно — «ждут: N», и в записи они будут первыми.
 */
/** Перерыв в «Дне»: «🕌 13:00 Джума-намаз до 15:00» — не нажимается, записи на это время нет. */
function pauseRow(p) {
  return h('li', { class: 'row pause', role: 'listitem', 'aria-label': `Перерыв с ${timeOf(p.startAt)} до ${timeOf(p.endAt)}: ${p.title}` },
    h('span', { class: 'time' }, timeOf(p.startAt)),
    h('span', { class: 'name' }, h('span', { 'aria-hidden': 'true' }, `${p.emoji} `), p.title),
    h('span', { class: 'time hint' }, timeOf(p.endAt)));
}

function freeRow(date, slot, waitlist = [], doctorId = team.current) {
  if (!canEdit(doctorId)) {
    // Чужое расписание: окно видно, но записать в него может только сам врач
    return h('li', { class: 'row free readonly', role: 'listitem' },
      h('span', { class: 'time' }, timeOf(slot.startAt)), h('span', { class: 'hint' }, `свободно до ${timeOf(slot.endAt)}`));
  }
  const candidates = waitlist.filter((entry) => fitsWindow(entry, slot, doctorId));
  const open = () => newScreen({ date, time: timeOf(slot.startAt), duration: windowDuration(slot), candidates });
  return h('li', {
    class: 'row free', role: 'listitem', tabindex: 0, onclick: open,
    onkeydown: (e) => e.key === 'Enter' && open(),
    'aria-label': `Свободно с ${timeOf(slot.startAt)} до ${timeOf(slot.endAt)}, записать`,
  },
  h('span', { class: 'time' }, timeOf(slot.startAt)),
  h('span', { class: 'hint' }, `свободно до ${timeOf(slot.endAt)}`,
    candidates.length ? h('span', { class: 'waiting' }, ` · ждут: ${candidates.length}`) : null));
}

function row(a, isNext, flash, now, redraw) {
  const failed = unsaved.has(a.id);
  // ER2: недошедшая отметка — локальное состояние строки, не из базы
  const state = failed ? { label: 'Не сохранено · повторить', icon: '⚠️', tone: 'bad' } : rowState(a, now);
  const classes = ['row', isNext && 'next', flash && 'flash', state.struck && 'struck'].filter(Boolean).join(' ');
  const viaBot = a.patient.telegram && !failed;
  const wa = canRemind(a, now) && canEdit(a.doctorId) ? h('button', {
    type: 'button', class: viaBot ? 'wa tg' : 'wa',
    'aria-label': `${viaBot ? 'Telegram' : 'WhatsApp'}: ${a.patient.fullName}, ${timeOf(a.startAt)}`,
    onclick: (e) => {
      e.stopPropagation(); // не открывать карточку
      if (failed) {
        // «повторить» — только отметку: WhatsApp уже открывали
        unsaved.delete(a.id);
        markReminder(a, redraw);
        a.reminderOpenedAt = a.reminderOpenedAt || new Date().toISOString();
        redraw();
      } else {
        remindBest(a, redraw, e.currentTarget);
      }
    },
  }, failed ? '↻' : [svgIcon(viaBot ? PLANE_ICON : CHAT_ICON, viaBot), h('span', { class: 'sr-only' }, viaBot ? 'TG' : 'WA')]) : null;
  return h('li', {
    class: classes, role: 'listitem', tabindex: 0, 'data-tone': state.tone,
    onclick: () => cardScreen(a),
    onkeydown: (e) => e.key === 'Enter' && cardScreen(a),
  },
  h('span', { class: 'time' }, timeOf(a.startAt)),
  h('span', { class: 'name' }, a.patient.fullName),
  wa,
  h('span', { class: 'time hint' }, timeOf(a.endAt)),
  h('span', { class: `status tone-${state.tone}` }, `${state.icon} ${state.label}`));
}

// ---------- Карточка приёма ----------

const REPLIES = [['CONFIRMED', 'Придёт'], ['RESCHEDULE', 'Перенос'], ['NO_ANSWER', 'Нет ответа']];

function cardScreen(a, notice) {
  const id = ++screenId;
  // Ответ сервера после ухода с карточки не должен вернуть врача обратно на неё
  const refresh = () => id === screenId && cardScreen(a);
  mainButton.hide();
  // «Назад» — в «День» врача этого приёма (из карточки пациента можно открыть приём коллеги)
  if (doctorById(a.doctorId)) team.current = a.doctorId;
  backButton.set(() => dayScreen(dateOf(a.startAt)));

  const state = rowState(a);
  const error = h('div', { class: 'error', role: 'alert' });
  const buttons = [];
  const button = (text, onclick, cls = 'btn') => {
    const b = h('button', { type: 'button', class: cls, onclick }, text);
    buttons.push(b);
    return b;
  };

  /** Любое действие: версия, которую видит врач, уходит вместе с ним (D18). */
  async function act(action, body, onDone) {
    buttons.forEach((b) => { b.disabled = true; });
    error.replaceChildren();
    try {
      const updated = await api('POST', `/api/appointments/${a.id}/${action}`, { version: a.version, ...body });
      haptic('success');
      cardScreen(updated);
      onDone?.(updated);
    } catch (e) {
      if (fatal(e)) return;
      buttons.forEach((b) => { b.disabled = false; });
      haptic('error');
      if (e.code === 'stale') {
        error.replaceChildren(e.message, ' ', h('button', { type: 'button', class: 'link', onclick: () => reload(a.id) }, 'Обновить'));
      } else {
        error.replaceChildren(errorText(e));
      }
    }
  }

  const undoable = (label) => (updated) => toast(label, 'Вернуть', () => undo(updated, a.status));

  const actions = [];
  if (!canEdit(a.doctorId)) {
    // Приём коллеги: только посмотреть и при необходимости позвонить пациенту
    actions.push(h('p', { class: 'readonly-note' },
      `👀 Это приём коллеги — менять его может ${doctorById(a.doctorId)?.fullName || 'его врач'} или администратор.`),
    callButton(a.patient.phone));
  } else if (a.status === 'REQUESTED') {
    // Заявка пациента из бота: ответ уйдёт пациенту в бот сам (RequestDecided)
    actions.push(
      button('Подтвердить', () => act('status', { status: 'SCHEDULED' }), 'btn primary'),
      button('Перенести', () => rescheduleScreen(a)),
      callButton(a.patient.phone),
      h('div', { class: 'danger-zone' },
        button('Отклонить заявку', () => act('status', { status: 'CANCELLED' }), 'btn danger')));
  } else if (a.status === 'SCHEDULED') {
    actions.push(
      h('div', { class: 'replies' }, REPLIES.map(([reply, text]) => {
        const b = button(text, () => act('reply', { reply: a.reply === reply ? 'NONE' : reply }));
        b.setAttribute('aria-pressed', String(a.reply === reply));
        return b;
      })),
      button('Перенести', () => rescheduleScreen(a)),
    );
    if (canRemind(a)) {
      // Как связаться — выбирает врач: бот (если пациент записывался через него), WhatsApp или звонок
      actions.unshift(
        a.patient.telegram ? button('Напомнить в Telegram', (e) => remindTelegram(a, refresh, e.currentTarget), 'btn primary') : null,
        button('Напомнить в WhatsApp', () => remind(a, refresh), a.patient.telegram ? 'btn' : 'btn primary'),
        callButton(a.patient.phone),
      );
    }
    if (canMarkVisit(a)) actions.push(button('Пришёл', () => act('status', { status: 'DONE' }), 'btn primary'));
    // Опасные — отдельной группой внизу, без диалогов, но с «Вернуть» (DD7)
    actions.push(h('div', { class: 'danger-zone' },
      canMarkVisit(a) ? button('Не пришёл', () => act('status', { status: 'NO_SHOW' }, undoable('Отмечено: не пришёл')), 'btn danger') : null,
      button('Отменить приём', () => act('status', { status: 'CANCELLED' }, undoable('Отменено')), 'btn danger')));
  } else if (a.status === 'CANCELLED') {
    actions.push(h('p', { class: 'hint' }, 'Восстановленный приём попросит напомнить пациенту заново.'),
      button('Восстановить', () => act('status', { status: 'SCHEDULED' }), 'btn primary'));
  } else if (a.status === 'DONE') {
    // Лечение часто в несколько визитов: сразу записать на следующий — через неделю, тот же пациент и длительность
    actions.push(button('Записать на следующий визит', () => newScreen({
      date: addDays(dateOf(a.startAt) > todayIn() ? dateOf(a.startAt) : todayIn(), 7),
      duration: minutesBetween(a.startAt, a.endAt), patient: a.patient, doctorId: a.doctorId,
    }), 'btn primary'));
    actions.push(h('div', { class: 'danger-zone' },
      button('Исправить: не пришёл', () => act('status', { status: 'NO_SHOW' }, undoable('Отмечено: не пришёл')), 'btn danger')));
  } else if (a.status === 'NO_SHOW') {
    actions.push(button('Исправить: пришёл', () => act('status', { status: 'DONE' })));
  }

  render(h('section', { class: 'screen' },
    backButton.link(),
    h('h1', { class: 'time' }, `${timeOf(a.startAt)}–${timeOf(a.endAt)}`),
    h('div', { class: 'hint' }, dayTitle(dateOf(a.startAt))),
    team.doctors.length > 1 ? h('div', { class: 'for-doctor' }, avatar(doctorById(a.doctorId)),
      h('span', {}, doctorById(a.doctorId)?.fullName || 'Врач скрыт')) : null,
    h('p', {}, h('button', {
      type: 'button', class: 'link name-link', onclick: () => patientScreen(a.patient.id, () => cardScreen(a)),
    }, a.patient.fullName, ' ›'), h('br'), formatPhone(a.patient.phone)),
    h('p', { class: `tone-${state.tone}` }, `${state.icon} ${state.label}`),
    a.source === 'PATIENT' ? h('p', { class: 'hint' }, `Записался сам через бота${a.serviceName ? ` · ${a.serviceName}` : ''}`) : null,
    // Напоминали ли — видно и потом, а не только в плашке после нажатия
    a.reminderOpenedAt && a.status === 'SCHEDULED'
      ? h('p', { class: 'sent-note' }, `🔔 Напоминание отправлено ${sentText(a.reminderOpenedAt)}`) : null,
    a.rating ? h('p', { class: 'rating', 'aria-label': `Оценка пациента: ${a.rating} из 5` }, '⭐'.repeat(a.rating),
      h('span', { class: 'hint' }, ` оценка пациента ${a.rating} из 5`)) : null,
    notice ? h('p', { class: 'hint' }, notice) : null,
    error,
    ...actions));
}

async function undo(updated, previousStatus) {
  try {
    cardScreen(await api('POST', `/api/appointments/${updated.id}/undo`, { version: updated.version, previousStatus }));
    haptic('success');
  } catch (e) {
    if (fatal(e)) return;
    toast(e.code === 'time-taken' ? 'Не вернуть: время уже занято' : errorText(e));
  }
}

async function reload(id) {
  try {
    cardScreen(await api('GET', `/api/appointments/${id}`));
  } catch (e) {
    if (!fatal(e)) toast(errorText(e));
  }
}

// ---------- Перенос ----------

function rescheduleScreen(a) {
  ++screenId;
  backButton.set(() => cardScreen(a));
  // exclude: старое время переносимого приёма в сетке считается свободным (ER4)
  const form = timeFields({
    date: dateOf(a.startAt), time: timeOf(a.startAt), duration: minutesBetween(a.startAt, a.endAt), exclude: a.id,
    doctorId: a.doctorId, // перенос — у того же врача
  });
  render(h('section', { class: 'screen' }, backButton.link(),
    h('h1', {}, 'Перенести'), h('div', {}, a.patient.fullName), form.node));
  mainButton.set('Перенести', async () => {
    if (!form.validate()) return;
    mainButton.busy(true);
    try {
      const updated = await api('POST', `/api/appointments/${a.id}/reschedule`,
        { version: a.version, startAt: form.startAt(), durationMinutes: form.duration });
      haptic('success');
      cardScreen(updated, 'Время изменилось — напомните пациенту заново');
    } catch (e) {
      mainButton.busy(false);
      if (!fatal(e)) form.showError(e);
    }
  });
}

// ---------- Дата, длительность, время (запись и перенос) ----------

/**
 * Дата → длительность → сетка свободных начал с шагом 15 минут (DD11). Сетку считает сервер
 * (/api/slots) тем же условием, что и защита базы от двойной записи. «Другое время» —
 * ручной ввод, в том числе вне рабочих часов.
 */
function timeFields({ date, time = '', duration = 30, exclude, doctorId = team.current }) {
  const errors = { startAt: h('div', { class: 'error', role: 'alert' }), durationMinutes: h('div', { class: 'error' }) };
  const dateInput = h('input', { type: 'date', value: date, required: true, onchange: () => { chosen = ''; loadGrid(); } });
  const timeInput = h('input', { type: 'time', step: 900 });
  const grid = h('div', { class: 'slots', role: 'group', 'aria-label': 'Свободное время' });
  const manualBox = h('label', { class: 'field', hidden: true }, h('span', {}, 'Другое время'), timeInput);
  const toggle = h('button', { type: 'button', class: 'link', onclick: () => setManual(!manual) }, 'Другое время');
  let chosen = time; // выбранное в сетке 'HH:MM'
  let manual = false;
  let slots = null;
  let gridSeq = 0;

  const fields = {
    duration,
    node: null,
    date: () => dateInput.value,
    time: () => (manual ? timeInput.value : chosen),
    startAt: () => toInstant(dateInput.value, fields.time()),
    validate() {
      errors.startAt.textContent = dateInput.value && fields.time() ? '' : 'Выберите время';
      return !errors.startAt.textContent;
    },
    /** 409 «Время занято» — под сеткой, и сетка обновляется (DD5, DD11); 422 — под своим полем. */
    showError(e) {
      haptic('error');
      if (e.code === 'time-taken') {
        errors.startAt.textContent = e.message;
        // Время ввели вручную и оно занято — вернуть сетку: выбирать дальше удобнее из свободного
        if (manual) setManual(false);
        chosen = '';
        loadGrid({ keepError: true });
        return true;
      }
      const f = e instanceof ApiError ? e.fields : {};
      if (f.startAt || f.durationMinutes) {
        errors.startAt.textContent = f.startAt || '';
        errors.durationMinutes.textContent = f.durationMinutes || '';
        return true;
      }
      errors.startAt.textContent = errorText(e);
      return true;
    },
  };

  function setManual(on) {
    manual = on;
    manualBox.hidden = !on;
    toggle.textContent = on ? 'Выбрать свободное время' : 'Другое время';
    if (on && !timeInput.value) timeInput.value = chosen;
    drawGrid();
  }

  function drawGrid() {
    if (manual || !slots) {
      grid.replaceChildren();
      return;
    }
    const starts = slots.starts.map(timeOf);
    if (starts.length === 0) {
      grid.replaceChildren(h('div', { class: 'hint' }, slots.workingDay ? 'Свободного времени нет' : 'Выходной'));
      return;
    }
    grid.replaceChildren(...starts.map((t) => h('button', {
      type: 'button', class: 'slot time', 'aria-pressed': String(t === chosen),
      onclick: () => { chosen = t; errors.startAt.textContent = ''; drawGrid(); },
    }, t)));
  }

  async function loadGrid({ keepError = false } = {}) {
    const seq = ++gridSeq;
    if (!keepError) errors.startAt.textContent = '';
    grid.replaceChildren(h('div', { class: 'hint' }, 'Загрузка…'));
    const query = `date=${dateInput.value}&doctorId=${doctorId}&duration=${fields.duration}${exclude ? `&exclude=${exclude}` : ''}`;
    let loaded;
    try {
      loaded = await api('GET', `/api/slots?${query}`);
    } catch (e) {
      if (seq !== gridSeq || fatal(e)) return;
      grid.replaceChildren(h('div', { class: 'error' }, errorText(e)));
      return;
    }
    if (seq !== gridSeq) return; // ответ на старую дату или длительность
    slots = loaded;
    const free = slots.starts.map(timeOf);
    if (chosen && !free.includes(chosen)) {
      // Подставленное время не в сетке (вне рабочих часов или уже занято): показать его вручную
      if (!manual && time && chosen === time) setManual(true);
      else chosen = '';
    }
    drawGrid();
  }

  const options = DURATIONS.includes(duration) ? DURATIONS : [...DURATIONS, duration].sort((x, y) => x - y);
  const segments = h('div', { class: 'segments', role: 'group', 'aria-label': 'Длительность' }, options.map((m) => h('button', {
    type: 'button', 'aria-pressed': String(m === duration),
    onclick: (e) => {
      fields.duration = m;
      segments.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b === e.currentTarget)));
      loadGrid(); // другая длительность — другие начала
    },
  }, String(m))));
  fields.node = h('div', {},
    h('label', { class: 'field' }, h('span', {}, 'Дата'), dateInput),
    h('div', { class: 'field' }, h('span', {}, 'Длительность, мин'), segments, errors.durationMinutes),
    // Ошибка над сеткой, а не под ней: сетка длинная, и «Время занято» ушло бы за край экрана
    h('div', { class: 'field' }, h('span', {}, 'Время'), errors.startAt, grid, toggle, manualBox));
  loadGrid();
  return fields;
}

// ---------- Меню ----------

/** Всё, что не «День»: пациенты и лист ожидания, статистика. */
function menuScreen(back) {
  ++screenId;
  hideToast();
  mainButton.hide();
  backButton.set(back);
  const self = () => menuScreen(back);
  // Пока адрес и поддержка пустые, пациенты их не видят — админу напоминание с переходом
  const c = team.clinic || {};
  const clinicEmpty = !c.address && !c.mapUrl && !c.supportUrl && !c.supportPhone;
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Меню'),
    team.admin && clinicEmpty ? h('button', { type: 'button', class: 'setup-note', onclick: () => clinicScreen(self) },
      '⚠️ Адрес и техподдержка не заданы — пациенты их не видят. Нажмите, чтобы заполнить.') : null,
    h('button', { type: 'button', class: 'btn', onclick: () => patientsScreen(self) }, 'Пациенты и лист ожидания'),
    h('button', { type: 'button', class: 'btn', onclick: () => recallScreen(self) }, 'Позвать на профосмотр'),
    h('button', { type: 'button', class: 'btn', onclick: () => statsScreen(self) }, 'Статистика неявок'),
    h('button', { type: 'button', class: 'btn', onclick: () => servicesScreen(self) }, 'Услуги для самозаписи'),
    h('button', { type: 'button', class: 'btn', onclick: () => timeOffScreen(self) }, 'Перерывы и выходные'),
    team.mine ? h('button', { type: 'button', class: 'btn', onclick: () => profileScreen(self) }, 'Мой профиль') : null,
    team.admin ? h('button', { type: 'button', class: 'btn', onclick: () => doctorsScreen(self) }, 'Врачи') : null,
    team.admin ? h('button', { type: 'button', class: 'btn', onclick: () => clinicScreen(self) }, 'О клинике') : null,
    h('button', { type: 'button', class: 'btn', onclick: () => supportScreen(self) }, 'Техподдержка')));
}

// ---------- О клинике и техподдержка ----------

/** Адрес кабинета со ссылкой 2ГИС и контакты техподдержки — их видят пациенты и врачи. Только админ. */
function clinicScreen(back) {
  ++screenId;
  backButton.set(back);
  const c = team.clinic || {};
  const address = h('input', { type: 'text', maxLength: 200, value: c.address || '', placeholder: 'Например: Бишкек, ул. Шевченко 6' });
  const mapUrl = h('input', { type: 'url', maxLength: 300, value: c.mapUrl || '', placeholder: 'https://2gis.kg/bishkek/…' });
  const telegram = h('input', { type: 'text', maxLength: 40, value: c.supportTelegram ? `@${c.supportTelegram}` : '', placeholder: '@ник' });
  const phone = h('input', { type: 'tel', maxLength: 20, value: c.supportPhone ? formatPhone(c.supportPhone) : '', placeholder: '0555 123 456' });
  const errors = Object.fromEntries(['address', 'mapUrl', 'supportTelegram', 'supportPhone'].map((k) => [k, h('div', { class: 'error' })]));
  const error = h('div', { class: 'error', role: 'alert' });
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'О клинике'),
    h('p', { class: 'hint' }, 'Адрес и ссылку на карту пациенты видят при записи и в напоминаниях бота.'),
    h('label', { class: 'field' }, h('span', {}, 'Адрес'), address, errors.address),
    h('label', { class: 'field' }, h('span', {}, 'Ссылка 2ГИС'), mapUrl, errors.mapUrl),
    h('p', { class: 'hint' }, 'В 2ГИС найдите кабинет → «Поделиться» → «Скопировать ссылку» и вставьте сюда.'),
    h('h2', {}, 'Техподдержка приложения'),
    h('label', { class: 'field' }, h('span', {}, 'Telegram'), telegram, errors.supportTelegram),
    h('label', { class: 'field' }, h('span', {}, 'Телефон'), phone, errors.supportPhone),
    error));
  mainButton.set('Сохранить', async () => {
    Object.values(errors).forEach((e) => { e.textContent = ''; });
    error.textContent = '';
    mainButton.busy(true);
    try {
      team.clinic = await api('PUT', '/api/clinic', {
        address: address.value, mapUrl: mapUrl.value, supportTelegram: telegram.value, supportPhone: phone.value,
      });
      haptic('success');
      toast('✅ Сохранено', null, null, 'ok');
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      const f = e instanceof ApiError ? e.fields : {};
      Object.entries(errors).forEach(([k, el]) => { el.textContent = f[k] || ''; });
      if (!Object.keys(errors).some((k) => f[k])) error.textContent = errorText(e);
    }
  });
}

/** Частые вопросы врачей — ответ сразу, без звонка в поддержку. */
const FAQ = [
  ['Не появилась кнопка «Открыть день»', 'Нажмите /start в чате с ботом. Если кнопки всё равно нет — попросите администратора проверить ваш Telegram id в «Меню → Врачи».'],
  ['Вижу старую версию приложения', 'Закройте приложение (свайп вниз) и откройте снова из бота. Помогает и /start.'],
  ['Не приходят уведомления', 'Проверьте, что чат с ботом не на «Без звука» и вы нажимали /start. Уведомления приходят врачу его приёмов.'],
  ['Пациенту не дошло напоминание в Telegram', 'Пациент мог удалить чат с ботом. Напомните в WhatsApp или позвоните — кнопки в карточке приёма.'],
  ['«Время занято», хотя окно пустое', 'Его только что занял другой пациент или заявка. Обновите «День» — сетка покажет свободное.'],
];

/** Техподдержка приложения: написать или позвонить тому, кто его поддерживает, и частые вопросы. */
function supportScreen(back) {
  ++screenId;
  mainButton.hide();
  backButton.set(back);
  const c = team.clinic || {};
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Техподдержка'),
    h('p', {}, 'Не открывается приложение, не приходят уведомления или что-то работает не так — напишите. ',
      'Опишите, что делали, и приложите скриншот.'),
    c.supportUrl ? h('button', { type: 'button', class: 'btn primary', onclick: () => openLink(c.supportUrl) }, 'Написать в Telegram') : null,
    c.supportPhone ? callButton(c.supportPhone) : null,
    !c.supportUrl && !c.supportPhone ? h('p', { class: 'hint' },
      team.admin ? 'Контакты поддержки не заданы — добавьте их в «Меню → О клинике».' : 'Контакты поддержки пока не заданы — спросите администратора.') : null,
    h('h2', {}, 'Частые вопросы'),
    FAQ.map(([q, a]) => h('details', { class: 'faq' }, h('summary', {}, q), h('p', {}, a)))));
}

// ---------- Профиль врача ----------

/**
 * Уменьшить фото до 800 px по длинной стороне и сжать в JPEG — прямо в браузере. Фото с
 * телефона весит 3–8 МБ; на сервер уходит ~150 КБ, и пациенты быстро грузят экран записи.
 */
function resizePhoto(file, max = 800) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, max / Math.max(img.width, img.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      URL.revokeObjectURL(url);
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('resize'))), 'image/jpeg', 0.85);
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('not an image')); };
    img.src = url;
  });
}

/**
 * Профиль врача: имя, пара слов о себе и фото — пациент видит их, когда выбирает врача.
 * Врач правит свой профиль; админ — любого (из «Врачей»).
 */
async function profileScreen(back, doctorId = team.mine ?? team.current) {
  const id = ++screenId;
  backButton.set(back);
  mainButton.hide();
  let profile;
  try {
    profile = (await api('GET', '/api/doctors')).find((d) => d.id === doctorId);
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e))));
    return;
  }
  if (id !== screenId || !profile) return;
  const self = () => profileScreen(back, doctorId);
  const name = h('input', { type: 'text', maxLength: 100, value: profile.fullName || '', placeholder: 'Например: Айгерим Садыкова' });
  const about = h('textarea', { maxLength: 300, rows: 3, placeholder: 'Например: стоматолог-терапевт, 10 лет опыта' });
  about.value = profile.about || '';
  const errors = { fullName: h('div', { class: 'error' }), about: h('div', { class: 'error' }), photo: h('div', { class: 'error' }) };
  const error = h('div', { class: 'error', role: 'alert' });
  const file = h('input', { type: 'file', accept: 'image/*', class: 'sr-only', id: 'photo-file' });
  file.addEventListener('change', async () => {
    const picked = file.files[0];
    if (!picked) return;
    errors.photo.textContent = 'Загружаем…';
    try {
      const form = new FormData();
      form.append('photo', await resizePhoto(picked), 'photo.jpg');
      await api('POST', `/api/doctors/${doctorId}/photo`, form);
      haptic('success');
      await refreshTeam();
      self();
    } catch (e) {
      if (fatal(e)) return;
      errors.photo.textContent = e instanceof ApiError ? (e.fields.photo || errorText(e)) : 'Не получилось открыть фото — выберите другое';
    }
  });
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, doctorId === team.mine ? 'Мой профиль' : 'Профиль врача'),
    h('p', { class: 'hint' }, 'Пациенты видят это, когда выбирают врача в боте.'),
    h('div', { class: 'profile-photo' },
      profile.photoUrl ? h('img', { src: profile.photoUrl, alt: 'Фото врача' })
        : h('div', { class: 'photo-empty', 'aria-hidden': 'true' }, '🧑‍⚕️'),
      h('div', {},
        h('label', { class: 'btn', for: 'photo-file' }, h('span', { class: 'ico', 'aria-hidden': 'true' }, '📷'),
          profile.photoUrl ? 'Сменить фото' : 'Загрузить фото', file),
        profile.photoUrl ? h('button', {
          type: 'button', class: 'link danger-link',
          onclick: async () => {
            try {
              await api('DELETE', `/api/doctors/${doctorId}/photo`);
              await refreshTeam();
              self();
            } catch (e) {
              if (!fatal(e)) errors.photo.textContent = errorText(e);
            }
          },
        }, 'Удалить фото') : null)),
    errors.photo,
    h('label', { class: 'field' }, h('span', {}, 'Имя врача'), name, errors.fullName),
    h('label', { class: 'field' }, h('span', {}, 'О себе'), about, errors.about),
    error));
  mainButton.set('Сохранить', async () => {
    Object.values(errors).forEach((e) => { e.textContent = ''; });
    error.textContent = '';
    mainButton.busy(true);
    try {
      await api('PUT', `/api/doctors/${doctorId}/profile`, { fullName: name.value, about: about.value });
      haptic('success');
      await refreshTeam();
      toast('Сохранено');
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      const f = e instanceof ApiError ? e.fields : {};
      errors.fullName.textContent = f.fullName || '';
      errors.about.textContent = f.about || '';
      if (!f.fullName && !f.about) error.textContent = errorText(e);
    }
  });
}

/** Список врачей после правки: имена и фото в переключателе «Дня» — свежие. */
async function refreshTeam() {
  try {
    team.doctors = (await api('GET', '/api/doctors')).filter((d) => d.active);
  } catch {
    // старый список тоже годится
  }
}

// ---------- Врачи (админ) ----------

/** Врачи кабинета: завести, привязать Telegram, скрыть. Только админ (ALLOWED_TELEGRAM_IDS). */
async function doctorsScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  let list;
  try {
    list = await api('GET', '/api/doctors/admin');
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e))));
    return;
  }
  if (id !== screenId) return;
  const self = () => doctorsScreen(back);
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Врачи'),
    h('p', { class: 'hint' }, 'У каждого врача своё расписание. Врач с привязанным Telegram входит в приложение и получает уведомления о своих приёмах.'),
    list.map((d) => h('button', {
      type: 'button', class: `doctor-row${d.active ? '' : ' inactive'}`, onclick: () => doctorEditScreen(d, self),
    }, avatar(d, 44), h('span', { class: 'doctor-row-text' }, h('strong', {}, d.fullName),
      h('span', { class: 'hint' }, [d.telegramId ? `Telegram ${d.telegramId}` : 'Telegram не привязан — уведомления вам',
        d.active ? null : 'скрыт'].filter(Boolean).join(' · '))))),
    h('button', { type: 'button', class: 'btn primary', onclick: () => doctorEditScreen(null, self) }, '+ Добавить врача')));
}

function doctorEditScreen(doctor, back) {
  ++screenId;
  backButton.set(back);
  const name = h('input', { type: 'text', maxLength: 100, value: doctor?.fullName || '', placeholder: 'Например: Айгерим Садыкова' });
  const about = h('input', { type: 'text', maxLength: 300, value: doctor?.about || '', placeholder: 'Например: ортодонт, брекеты' });
  const telegram = h('input', { type: 'text', inputMode: 'numeric', value: doctor?.telegramId ?? '', placeholder: 'Например: 512345678' });
  const active = h('input', { type: 'checkbox', checked: doctor ? doctor.active : true, id: 'doctor-active' });
  const errors = { fullName: h('div', { class: 'error' }), about: h('div', { class: 'error' }), telegramId: h('div', { class: 'error' }) };
  const error = h('div', { class: 'error', role: 'alert' });
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, doctor ? 'Врач' : 'Новый врач'),
    h('label', { class: 'field' }, h('span', {}, 'Имя'), name, errors.fullName),
    h('label', { class: 'field' }, h('span', {}, 'Кратко (видят пациенты)'), about, errors.about),
    h('label', { class: 'field' }, h('span', {}, 'Telegram id врача'), telegram, errors.telegramId),
    h('p', { class: 'hint' }, 'Свой id врач узнает у бота @userinfobot. Потом пусть нажмёт /start в нашем боте — появится кнопка «Открыть день».'),
    doctor ? h('label', { class: 'consent', for: 'doctor-active' }, active, ' Работает (показывать пациентам)') : null,
    doctor ? h('button', { type: 'button', class: 'btn', onclick: () => profileScreen(() => doctorEditScreen(doctor, back), doctor.id) }, 'Фото и профиль') : null,
    error));
  mainButton.set('Сохранить', async () => {
    Object.values(errors).forEach((e) => { e.textContent = ''; });
    error.textContent = '';
    const tgId = telegram.value.trim();
    if (tgId && !/^\d{3,15}$/.test(tgId)) {
      errors.telegramId.textContent = 'Только цифры, как пишет @userinfobot';
      return;
    }
    mainButton.busy(true);
    const body = { fullName: name.value, about: about.value, telegramId: tgId ? Number(tgId) : null, active: active.checked };
    try {
      await api(doctor ? 'PUT' : 'POST', doctor ? `/api/doctors/${doctor.id}` : '/api/doctors', body);
      haptic('success');
      await refreshTeam();
      if (!team.current && team.doctors.length) team.current = team.doctors[0].id;
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      const f = e instanceof ApiError ? e.fields : {};
      Object.entries(errors).forEach(([k, el]) => { el.textContent = f[k] || ''; });
      if (e.code === 'telegram-taken') errors.telegramId.textContent = e.message;
      else if (!Object.keys(errors).some((k) => f[k])) error.textContent = errorText(e);
    }
  });
}

// ---------- Перерывы и выходные ----------

/** Время, когда врач не принимает: джума-намаз, обед, отпуск. Его нет в сетке у пациентов. */
async function timeOffScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  const self = () => timeOffScreen(back);
  let list;
  try {
    list = await api('GET', '/api/time-off');
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e))));
    return;
  }
  if (id !== screenId) return;
  const error = h('div', { class: 'error', role: 'alert' });
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Перерывы и выходные'),
    h('p', { class: 'hint' }, 'В это время пациенты не смогут записаться через бота. Вы сами записать можете.'),
    error,
    list.length ? list.map((t) => h('div', { class: 'timeoff' },
      h('span', { class: 'timeoff-emoji', 'aria-hidden': 'true' }, t.emoji),
      h('div', { class: 'timeoff-text' }, h('div', { class: 'timeoff-title' }, t.title), h('div', { class: 'hint' }, timeOffWhen(t)),
        team.doctors.length > 1 ? h('div', { class: 'hint' }, t.doctorId ? `🧑‍⚕️ ${doctorById(t.doctorId)?.fullName || 'врач скрыт'}` : '👥 Все врачи') : null),
      !team.admin && t.doctorId !== team.mine ? null : h('button', {
        type: 'button', class: 'link danger-link', 'aria-label': `Удалить: ${t.title}`,
        onclick: async () => {
          try {
            await api('DELETE', `/api/time-off/${t.id}`);
            haptic('success');
            self();
          } catch (e) {
            if (!fatal(e)) error.textContent = errorText(e);
          }
        },
      }, 'Удалить'))) : h('div', { class: 'hint' }, 'Перерывов нет'),
    h('button', { type: 'button', class: 'btn primary', onclick: () => timeOffEditScreen(self) }, '+ Добавить')));
}

const TIME_OFF_EMOJI = ['🕌', '🍽️', '☕', '🏖️', '✈️', '🤒', '📚', '👨‍👩‍👧', '⏸️'];
const WEEKDAY_NAMES = [['MONDAY', 'Пн'], ['TUESDAY', 'Вт'], ['WEDNESDAY', 'Ср'], ['THURSDAY', 'Чт'], ['FRIDAY', 'Пт'],
  ['SATURDAY', 'Сб'], ['SUNDAY', 'Вс']];

/** Новый перерыв: каждую неделю или по датам; на весь день или с–по. */
function timeOffEditScreen(back) {
  ++screenId;
  backButton.set(back);
  let weekly = true;
  let weekday = 'FRIDAY';
  // Для кого: по умолчанию — для своего кресла; null — у всех (общий выходной, джума-намаз)
  // Врач — только себе; админ выбирает (null — у всех врачей)
  let forDoctor = !team.admin ? team.mine : team.doctors.length > 1 ? (team.mine ?? team.current) : null;
  const whoBox = h('div', { class: 'weekdays', role: 'group', 'aria-label': 'Для кого' });
  let emoji = TIME_OFF_EMOJI[0];
  const title = h('input', { type: 'text', maxLength: 60, placeholder: 'Например: Обед' });
  const firstDay = h('input', { type: 'date', value: todayIn() });
  const lastDay = h('input', { type: 'date', value: todayIn() });
  const wholeDay = h('input', { type: 'checkbox', id: 'whole-day' });
  const startTime = h('input', { type: 'time', value: '13:00', step: 900 });
  const endTime = h('input', { type: 'time', value: '14:00', step: 900 });
  const errors = Object.fromEntries(['title', 'firstDay', 'lastDay', 'endTime', 'emoji'].map((k) => [k, h('div', { class: 'error' })]));
  const error = h('div', { class: 'error', role: 'alert' });
  const kindBox = h('div', { class: 'segments' });
  const whenBox = h('div', {});
  const timeBox = h('div', { class: 'time-pair' }, h('label', { class: 'field' }, h('span', {}, 'С'), startTime),
    h('label', { class: 'field' }, h('span', {}, 'До'), endTime, errors.endTime));
  const emojiBox = h('div', { class: 'emoji-pick', role: 'group', 'aria-label': 'Значок' });

  function draw() {
    whoBox.replaceChildren(...[[null, '👥 Все врачи'], ...team.doctors.map((d) => [d.id, shortName(d.fullName)])]
      .map(([value, label]) => h('button', {
        type: 'button', class: 'day', 'aria-pressed': String(forDoctor === value), onclick: () => { forDoctor = value; draw(); },
      }, label)));
    kindBox.replaceChildren(
      h('button', { type: 'button', 'aria-pressed': String(weekly), onclick: () => { weekly = true; draw(); } }, 'Каждую неделю'),
      h('button', { type: 'button', 'aria-pressed': String(!weekly), onclick: () => { weekly = false; draw(); } }, 'По датам'));
    whenBox.replaceChildren(...(weekly
      ? [h('div', { class: 'field' }, h('span', {}, 'День недели'), h('div', { class: 'weekdays' }, WEEKDAY_NAMES.map(([code, name]) => h('button', {
        type: 'button', class: 'day', 'aria-pressed': String(weekday === code), onclick: () => { weekday = code; draw(); },
      }, name))))]
      : [h('label', { class: 'field' }, h('span', {}, 'С даты'), firstDay, errors.firstDay),
        h('label', { class: 'field' }, h('span', {}, 'По дату (включительно)'), lastDay, errors.lastDay)]));
    timeBox.hidden = wholeDay.checked;
    emojiBox.replaceChildren(...TIME_OFF_EMOJI.map((e) => h('button', {
      type: 'button', class: 'day', 'aria-pressed': String(emoji === e), onclick: () => { emoji = e; draw(); },
    }, e)));
  }
  wholeDay.addEventListener('change', draw);
  firstDay.addEventListener('change', () => { if (lastDay.value < firstDay.value) lastDay.value = firstDay.value; });

  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Новый перерыв'),
    team.admin && team.doctors.length > 1 ? h('div', { class: 'field' }, h('span', {}, 'Для кого'), whoBox) : null,
    kindBox, whenBox,
    h('label', { class: 'consent', for: 'whole-day' }, wholeDay, ' Весь день (выходной, отпуск)'),
    timeBox,
    h('label', { class: 'field' }, h('span', {}, 'Название'), title, errors.title),
    h('div', { class: 'field' }, h('span', {}, 'Значок'), emojiBox, errors.emoji),
    error));
  draw();
  mainButton.set('Сохранить', async () => {
    Object.values(errors).forEach((e) => { e.textContent = ''; });
    error.textContent = '';
    mainButton.busy(true);
    const body = {
      doctorId: forDoctor,
      weekday: weekly ? weekday : null,
      firstDay: weekly ? null : firstDay.value || null,
      lastDay: weekly ? null : lastDay.value || null,
      startTime: wholeDay.checked ? null : startTime.value || null,
      endTime: wholeDay.checked ? null : endTime.value || null,
      title: title.value,
      emoji,
    };
    try {
      await api('POST', '/api/time-off', body);
      haptic('success');
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      const f = e instanceof ApiError ? e.fields : {};
      Object.entries(errors).forEach(([k, el]) => { el.textContent = f[k] || ''; });
      if (!Object.keys(errors).some((k) => f[k])) error.textContent = errorText(e);
    }
  });
}

// ---------- Услуги (самозапись) ----------

/** Что пациент может выбрать в боте и сколько это длится. Скрытая услуга остаётся у прошлых приёмов. */
async function servicesScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  const self = () => servicesScreen(back);
  let list;
  try {
    list = await api('GET', '/api/service-types');
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e))));
    return;
  }
  if (id !== screenId) return;
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Услуги'),
    h('p', { class: 'hint' }, 'Их видят пациенты, когда записываются сами. Длительность — сколько времени займёт приём.'),
    ...list.map((s) => h('button', {
      type: 'button', class: 'btn', onclick: () => serviceEditScreen(s, self),
    }, [s.name, `${s.durationMinutes} мин`, priceText(s.priceSom)].filter(Boolean).join(' · '),
    s.active ? null : h('span', { class: 'hint' }, ' · скрыта'))),
    h('button', { type: 'button', class: 'btn primary', onclick: () => serviceEditScreen(null, self) }, '+ Добавить услугу')));
}

function serviceEditScreen(service, back) {
  ++screenId;
  backButton.set(back);
  const name = h('input', { type: 'text', value: service?.name || '', maxLength: 100 });
  const duration = h('input', { type: 'number', min: 15, max: 240, step: 15, value: service?.durationMinutes || 30 });
  const price = h('input', { type: 'number', min: 0, step: 100, inputMode: 'numeric', value: service?.priceSom ?? '', placeholder: 'Не показывать' });
  const active = h('input', { type: 'checkbox', checked: service ? service.active : true, id: 'active' });
  const errors = { name: h('div', { class: 'error' }), durationMinutes: h('div', { class: 'error' }), priceSom: h('div', { class: 'error' }) };
  const error = h('div', { class: 'error', role: 'alert' });
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, service ? 'Услуга' : 'Новая услуга'),
    h('label', { class: 'field' }, h('span', {}, 'Название'), name, errors.name),
    h('label', { class: 'field' }, h('span', {}, 'Длительность, мин'), duration, errors.durationMinutes),
    h('label', { class: 'field' }, h('span', {}, 'Цена «от», сом'), price, errors.priceSom),
    service ? h('label', { class: 'consent', for: 'active' }, active, ' Показывать пациентам') : null,
    error));
  mainButton.set('Сохранить', async () => {
    Object.values(errors).forEach((e) => { e.textContent = ''; });
    error.textContent = '';
    mainButton.busy(true);
    const body = {
      name: name.value, durationMinutes: Number(duration.value), active: active.checked,
      priceSom: price.value === '' ? null : Number(price.value),
    };
    try {
      await api(service ? 'PUT' : 'POST', service ? `/api/service-types/${service.id}` : '/api/service-types', body);
      haptic('success');
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      const f = e instanceof ApiError ? e.fields : {};
      errors.name.textContent = f.name || '';
      errors.durationMinutes.textContent = f.durationMinutes || '';
      errors.priceSom.textContent = f.priceSom || '';
      if (!f.name && !f.durationMinutes && !f.priceSom) error.textContent = errorText(e);
    }
  });
}

// ---------- Профосмотры ----------

/**
 * Кто был больше полугода назад и никуда не записан. WhatsApp — так же, как напоминание:
 * отметка «позвали» с keepalive и сразу openLink. Позванные уходят в конец списка с датой.
 */
async function recallScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  const self = () => recallScreen(back);
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Позвать на профосмотр'), h('div', { class: 'skeleton' })));
  let due;
  try {
    due = await api('GET', '/api/appointments/recall');
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e),
      h('button', { type: 'button', class: 'btn primary', onclick: self }, 'Повторить'))));
    return;
  }
  if (id !== screenId) return;
  const now = new Date();
  const item = (r) => {
    const invited = h('span', { class: 'hint' }, r.invitedAt ? ` · позвали ${agoText(r.invitedAt, now)}` : '');
    return h('div', { class: 'candidate' },
      h('div', {}, h('button', {
        type: 'button', class: 'link name-link', onclick: () => patientScreen(r.patient.id, self),
      }, r.patient.fullName, ' ›'), h('br'),
      h('span', { class: 'hint' }, `был ${agoText(r.lastVisit, now)}`), invited),
      h('div', { class: 'choice' },
        h('button', {
          type: 'button', class: 'btn',
          onclick: () => {
            fetch(`/api/patients/${r.patient.id}/recall-invited`, {
              method: 'POST', keepalive: true, headers: { Authorization: `tma ${initData}` },
            }).catch(() => toast('Отметка «позвали» не сохранилась'));
            openLink(whatsappUrl(r.patient.phone, recallText(r.patient.fullName, settings.clinicSignature)));
            invited.textContent = ' · позвали сегодня';
          },
        }, 'WhatsApp'),
        h('button', {
          type: 'button', class: 'btn primary', onclick: () => newScreen({ date: todayIn(), patient: r.patient }),
        }, 'Записать')));
  };
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Позвать на профосмотр'),
    h('p', { class: 'hint' }, 'Были больше полугода назад и никуда не записаны. Сначала — кого ещё не звали.'),
    due.length ? due.map(item) : h('div', { class: 'message hint' }, 'Звать некого — все записаны или были недавно')));
}

// ---------- Статистика ----------

/** Неявки по неделям — ради этого проект: стало ли их меньше, чем 2–3 в неделю до приложения. */
/** «⭐ 4,8 из 5 · 12 оценок за 90 дней» и полоски по звёздам. Оценок нет — подсказка, откуда они берутся. */
function ratingsCard(r) {
  if (!r.count) {
    return h('div', { class: 'stat' }, h('strong', {}, '⭐ Оценки пациентов'),
      h('div', { class: 'hint' }, 'На следующий день после приёма бот спросит оценку у тех, кто записывался через бота.'));
  }
  const max = Math.max(...r.byStars);
  return h('div', { class: 'stat ratings' },
    h('div', { class: 'ratings-head' }, h('span', { class: 'ratings-avg' }, `⭐ ${String(r.average).replace('.', ',')}`),
      h('span', { class: 'hint' }, ` из 5 · ${ratingsPhrase(r.count)} за 90 дней`)),
    [5, 4, 3, 2, 1].map((stars) => h('div', { class: 'ratings-row' },
      h('span', { class: 'hint' }, `${stars} ⭐`),
      h('span', { class: 'ratings-bar' }, h('span', { style: `width: ${max ? (r.byStars[stars - 1] / max) * 100 : 0}%` })),
      h('span', { class: 'hint' }, String(r.byStars[stars - 1])))));
}

async function statsScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Статистика'), h('div', { class: 'skeleton' })));
  let weeks;
  let ratings;
  try {
    [weeks, ratings] = await Promise.all([api('GET', `/api/appointments/stats?weeks=8&doctorId=${team.current}`),
      api('GET', `/api/appointments/ratings?days=90&doctorId=${team.current}`)]);
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e),
      h('button', { type: 'button', class: 'btn primary', onclick: () => statsScreen(back) }, 'Повторить'))));
    return;
  }
  if (id !== screenId) return;
  const average = averageNoShows(weeks);
  const unmarked = weeks.reduce((sum, w) => sum + w.unmarked, 0);
  const week = (w, i) => {
    const percent = noShowPercent(w);
    return h('li', { class: 'stat' },
      h('div', { class: 'stat-head' }, h('strong', {}, weekLabel(w.weekStart)), i === 0 ? h('span', { class: 'hint' }, ' · эта неделя') : null),
      w.booked === 0 ? h('div', { class: 'hint' }, 'приёмов не было') : [
        h('div', { class: w.noShow > 0 ? 'tone-bad' : 'tone-ok' },
          `Не пришли: ${w.noShow}${percent === null ? '' : ` (${percent}%)`}`),
        h('div', { class: 'hint' }, [`приёмов ${w.booked}`, `пришли ${w.done}`, `отменено ${w.cancelled}`,
          `напомнили ${w.reminded}`, w.unmarked ? `не отмечено ${w.unmarked}` : null].filter(Boolean).join(' · ')),
      ]);
  };
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Статистика'),
    ratingsCard(ratings),
    h('p', {}, average === null ? 'Через неделю здесь будет среднее неявок в неделю.'
      : `В среднем ${noShowsPhrase(average)} в неделю. До приложения было 2–3.`),
    // Неотмеченные портят цифры: не пришёл или пришёл — неизвестно
    unmarked ? h('p', { class: 'tone-wait' }, `Не отмечено «пришёл / не пришёл»: ${unmarked} — без отметок неявки не посчитать.`) : null,
    h('ul', { class: 'stats', role: 'list' }, weeks.map(week))));
}

// ---------- Пациенты ----------

/** Поиск пациента из «Дня» → карточка пациента. Поиск — POST: в нём бывает номер телефона. */
function patientsScreen(back) {
  const id = ++screenId;
  hideToast();
  mainButton.hide();
  backButton.set(back);
  const results = h('div', {});
  let timer;
  let seq = 0;
  const search = h('input', {
    type: 'search', placeholder: 'Имя или телефон', autocomplete: 'off', 'aria-label': 'Поиск пациента',
    oninput: () => {
      clearTimeout(timer);
      results.replaceChildren();
      timer = setTimeout(async () => {
        const query = search.value.trim();
        const mine = ++seq;
        if (!query) return;
        try {
          const found = await api('POST', '/api/patients/search', { query });
          if (mine !== seq || id !== screenId) return;
          results.replaceChildren(...(found.length ? found.map((p) => h('button', {
            type: 'button', class: 'btn', onclick: () => patientScreen(p.id, () => patientsScreen(back)),
          }, p.fullName, h('br'), h('span', { class: 'hint' }, formatPhone(p.phone))))
            : [h('div', { class: 'hint' }, 'Никого не нашлось')]));
        } catch (e) {
          if (!fatal(e)) results.replaceChildren(h('div', { class: 'error' }, errorText(e)));
        }
      }, 250);
    },
  });
  const waiting = h('div', {});
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Пациенты'),
    h('label', { class: 'field' }, h('span', {}, 'Найти'), search), results, waiting));
  search.focus();
  // Лист ожидания — под поиском: кто хочет прийти раньше
  api('GET', '/api/waitlist').then((list) => {
    if (id !== screenId || list.length === 0) return;
    waiting.replaceChildren(h('h2', {}, `Лист ожидания: ${list.length}`), ...list.map((entry) => h('button', {
      type: 'button', class: 'btn', onclick: () => patientScreen(entry.patient.id, () => patientsScreen(back)),
    }, entry.patient.fullName, h('br'), h('span', { class: 'hint' }, entry.upcoming
      ? `сейчас: ${dayTitle(dateOf(entry.upcoming.startAt))}, ${timeOf(entry.upcoming.startAt)}`
      : 'ещё не записан', ` · ждёт с ${dayTitle(dateOf(entry.createdAt))}`))));
  }).catch((e) => fatal(e));
}

/** Карточка пациента: история приёмов, сколько раз пришёл / не пришёл / отменял. */
async function patientScreen(patientId, back) {
  const id = ++screenId;
  hideToast();
  mainButton.hide();
  backButton.set(back);
  render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'skeleton' })));
  let card;
  let waitlist;
  try {
    [card, waitlist] = await Promise.all([
      api('GET', `/api/appointments/by-patient/${patientId}`),
      api('GET', '/api/waitlist'),
    ]);
  } catch (e) {
    if (id !== screenId || fatal(e)) return;
    render(h('section', { class: 'screen' }, backButton.link(), h('div', { class: 'message' }, errorText(e),
      h('button', { type: 'button', class: 'btn primary', onclick: () => patientScreen(patientId, back) }, 'Повторить'))));
    return;
  }
  if (id !== screenId) return;
  const p = card.patient;
  const self = () => patientScreen(patientId, back);
  const now = new Date();
  const item = (a) => {
    const state = rowState(a, now);
    return h('li', {
      class: `row${state.struck ? ' struck' : ''}`, role: 'listitem', tabindex: 0,
      onclick: () => cardScreen(a), onkeydown: (e) => e.key === 'Enter' && cardScreen(a),
    },
    h('span', { class: 'time' }, timeOf(a.startAt)),
    h('span', { class: 'name' }, dayTitle(dateOf(a.startAt), now),
      team.doctors.length > 1 && doctorById(a.doctorId) ? h('span', { class: 'hint' }, ` · ${shortName(doctorById(a.doctorId).fullName)}`) : null),
    h('span', { class: 'time hint' }, timeOf(a.endAt)),
    h('span', { class: `status tone-${state.tone}` }, `${state.icon} ${state.label}`));
  };
  // Цифры — то, ради чего проект: видно, кто часто не приходит
  const stats = [`Пришёл: ${card.done}`, `не пришёл: ${card.noShow}`, `отменял: ${card.cancelled}`].join(' · ');
  render(h('section', { class: 'screen' },
    backButton.link(),
    h('h1', {}, p.fullName),
    h('div', {}, formatPhone(p.phone)),
    h('p', { class: card.noShow > 0 ? 'tone-bad' : 'hint' }, stats),
    h('button', { type: 'button', class: 'btn primary', onclick: () => newScreen({ date: todayIn(), patient: p }) }, 'Записать снова'),
    // Пустой чат, без готового текста: для любых вопросов
    h('button', { type: 'button', class: 'btn', onclick: () => openLink(`https://wa.me/${p.phone}`) }, 'Написать в WhatsApp'),
    callButton(p.phone),
    waitlistButton(waitlist.find((e) => e.patient.id === p.id), p, card, self),
    h('button', { type: 'button', class: 'btn', onclick: () => editPatientScreen(p, self) }, 'Изменить имя или телефон'),
    card.upcoming.length ? h('h2', {}, 'Впереди') : null,
    card.upcoming.length ? h('ul', { class: 'rows', role: 'list' }, card.upcoming.map(item)) : null,
    h('h2', {}, 'История'),
    card.past.length ? h('ul', { class: 'rows', role: 'list' }, card.past.map(item))
      : h('div', { class: 'hint' }, 'Приёмов ещё не было')));
}

/**
 * «Хочет прийти раньше» → в лист ожидания. Длительность — как у ближайшего приёма, иначе 30 минут.
 * Уже в листе — кнопка убирает.
 */
function waitlistButton(entry, p, card, refresh) {
  if (entry) {
    return h('button', {
      type: 'button', class: 'btn',
      onclick: async () => {
        try {
          await api('DELETE', `/api/waitlist/${entry.id}`);
          refresh();
        } catch (e) {
          if (!fatal(e)) toast(errorText(e));
        }
      },
    }, `Убрать из листа ожидания (с ${dayTitle(dateOf(entry.createdAt))})`);
  }
  const next = card.upcoming[0];
  const duration = next ? minutesBetween(next.startAt, next.endAt) : 30;
  return h('button', {
    type: 'button', class: 'btn',
    onclick: async () => {
      try {
        await api('POST', '/api/waitlist', { patientId: p.id, doctorId: team.current, durationMinutes: duration });
        haptic('success');
        refresh();
      } catch (e) {
        if (!fatal(e)) toast(errorText(e));
      }
    },
  }, `В лист ожидания (${duration} мин) — хочет раньше`);
}

/** Исправить имя или телефон — те же проверки, что при создании (422 под полем, 409 дубль). */
function editPatientScreen(p, back) {
  ++screenId;
  backButton.set(back);
  const nameInput = h('input', { type: 'text', value: p.fullName, maxLength: 200, autocomplete: 'off' });
  const phoneInput = h('input', { type: 'tel', value: formatPhone(p.phone), autocomplete: 'off' });
  const nameError = h('div', { class: 'error' });
  const phoneError = h('div', { class: 'error' });
  const error = h('div', { class: 'error', role: 'alert' });
  let allowDuplicate = false;
  async function save() {
    nameError.textContent = '';
    phoneError.textContent = '';
    error.replaceChildren();
    mainButton.busy(true);
    try {
      await api('PUT', `/api/patients/${p.id}`, { fullName: nameInput.value, phone: phoneInput.value, allowDuplicate });
      haptic('success');
      back();
    } catch (e) {
      mainButton.busy(false);
      if (fatal(e)) return;
      haptic('error');
      if (e.code === 'duplicate-patient') {
        const other = e.problem.patient;
        error.replaceChildren(`${e.message}: ${other.fullName}, ${formatPhone(other.phone)}`,
          h('button', { type: 'button', class: 'btn', onclick: () => { allowDuplicate = true; save(); } }, 'Всё равно сохранить'));
        return;
      }
      const f = e instanceof ApiError ? e.fields : {};
      nameError.textContent = f.fullName || '';
      phoneError.textContent = f.phone || '';
      if (!f.fullName && !f.phone) error.textContent = errorText(e);
    }
  }
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Изменить'),
    h('label', { class: 'field' }, h('span', {}, 'Имя пациента'), nameInput, nameError),
    h('label', { class: 'field' }, h('span', {}, 'Телефон'), phoneInput, phoneError),
    error));
  mainButton.set('Сохранить', save);
}

// ---------- Новая запись ----------

function newScreen({ date, time, duration, patient, candidates = [], doctorId = canEdit(team.current) ? team.current : team.mine }) {
  const id = ++screenId;
  hideToast();
  backButton.set(() => dayScreen(date));

  let picked = patient || null; // выбранный пациент { id, fullName, phone }; «Записать снова» — уже выбран
  let creating = false; // режим «Новый пациент»
  let allowDuplicate = false;

  const patientBox = h('div', {});
  const patientError = h('div', { class: 'error', role: 'alert' });
  const results = h('div', {});
  const search = h('input', { type: 'search', placeholder: 'Имя или телефон', autocomplete: 'off', 'aria-label': 'Поиск пациента' });
  const nameInput = h('input', { type: 'text', autocomplete: 'off', maxLength: 200 });
  const phoneInput = h('input', { type: 'tel', autocomplete: 'off', placeholder: '0555 123 456' });
  const nameError = h('div', { class: 'error' });
  const phoneError = h('div', { class: 'error' });
  const form = timeFields({ date, time, duration, doctorId });

  function drawPatient() {
    patientError.replaceChildren();
    if (picked) {
      patientBox.replaceChildren(h('div', { class: 'picked' },
        h('span', {}, h('strong', {}, picked.fullName), h('br'), h('span', { class: 'hint' }, formatPhone(picked.phone))),
        h('button', { type: 'button', class: 'link', onclick: () => { picked = null; search.value = ''; results.replaceChildren(); drawPatient(); } }, 'Изменить')));
    } else if (creating) {
      patientBox.replaceChildren(
        h('label', { class: 'field' }, h('span', {}, 'Имя пациента'), nameInput, nameError),
        h('label', { class: 'field' }, h('span', {}, 'Телефон'), phoneInput, phoneError),
        h('button', { type: 'button', class: 'link', onclick: () => { creating = false; drawPatient(); } }, 'Найти в списке'));
    } else {
      patientBox.replaceChildren(h('label', { class: 'field' }, h('span', {}, 'Пациент'), search), results);
      search.focus();
    }
  }

  let searchTimer;
  let searchSeq = 0;
  search.addEventListener('input', () => {
    clearTimeout(searchTimer);
    // Старые результаты сразу прочь: иначе за 250 мс можно нажать кнопку прошлого запроса
    results.replaceChildren();
    searchSeq++;
    searchTimer = setTimeout(runSearch, 250); // не на каждую букву
  });

  async function runSearch() {
    const query = search.value.trim();
    const seq = ++searchSeq;
    if (!query) { results.replaceChildren(); return; }
    let found;
    try {
      // POST, а не GET ?q=: в строке поиска бывает номер телефона, а ему не место в URL
      found = await api('POST', '/api/patients/search', { query });
    } catch (e) {
      if (!fatal(e)) results.replaceChildren(h('div', { class: 'error' }, errorText(e)));
      return;
    }
    if (seq !== searchSeq || id !== screenId) return; // пришёл ответ на старый запрос
    results.replaceChildren(
      ...found.map((p) => h('button', { type: 'button', class: 'btn', onclick: () => { picked = p; drawPatient(); } },
        p.fullName, h('br'), h('span', { class: 'hint' }, formatPhone(p.phone)))),
      h('button', {
        type: 'button', class: 'btn',
        onclick: () => {
          const text = search.value.trim();
          creating = true;
          allowDuplicate = false;
          nameInput.value = looksLikePhone(text) ? '' : text;
          phoneInput.value = looksLikePhone(text) ? text : '';
          drawPatient();
          (nameInput.value ? phoneInput : nameInput).focus();
        },
      }, `+ Новый пациент: «${query}»`));
  }

  async function createPatient() {
    nameError.textContent = '';
    phoneError.textContent = '';
    try {
      return await api('POST', '/api/patients', { fullName: nameInput.value, phone: phoneInput.value, allowDuplicate });
    } catch (e) {
      if (fatal(e)) return null;
      haptic('error');
      if (e.code === 'duplicate-patient') {
        // Тот же номер и то же имя (D19): скорее всего, этот человек уже есть
        const existing = e.problem.patient;
        patientError.replaceChildren(h('div', {}, `${e.message}: ${existing.fullName}, ${formatPhone(existing.phone)}`),
          h('div', { class: 'choice' },
            h('button', { type: 'button', class: 'btn', onclick: () => { picked = existing; creating = false; drawPatient(); } }, 'Выбрать его'),
            h('button', { type: 'button', class: 'btn', onclick: () => { allowDuplicate = true; submit(); } }, 'Всё равно создать')));
        return null;
      }
      const f = e instanceof ApiError ? e.fields : {};
      nameError.textContent = f.fullName || '';
      phoneError.textContent = f.phone || '';
      if (!f.fullName && !f.phone) patientError.textContent = errorText(e);
      return null;
    }
  }

  async function submit() {
    patientError.replaceChildren();
    if (!picked && !creating) { patientError.textContent = 'Выберите пациента'; return; }
    if (!form.validate()) return;
    mainButton.busy(true);
    try {
      if (!picked) {
        picked = await createPatient();
        if (!picked) return;
        creating = false;
        drawPatient();
      }
      const created = await api('POST', '/api/appointments',
        { doctorId, patientId: picked.id, startAt: form.startAt(), durationMinutes: form.duration });
      dayScreen(form.date(), created.id);
    } catch (e) {
      if (!fatal(e)) form.showError(e);
    } finally {
      mainButton.busy(false);
    }
  }

  /**
   * Лист ожидания для этого окна: написать «освободилось время» и записать (или перенести,
   * если уже записан) одним нажатием — сервер заодно уберёт пациента из листа.
   */
  const waiting = candidates.length ? h('div', { class: 'waitlist' },
    h('h2', {}, 'Ждут свободного времени'),
    ...candidates.map((entry) => h('div', { class: 'candidate' },
      h('div', {}, h('strong', {}, entry.patient.fullName), h('br'), h('span', { class: 'hint' },
        entry.upcoming ? `сейчас: ${dayTitle(dateOf(entry.upcoming.startAt))}, ${timeOf(entry.upcoming.startAt)}`
          : 'ещё не записан', ` · ${entry.durationMinutes} мин`)),
      h('div', { class: 'choice' },
        h('button', {
          type: 'button', class: 'btn',
          onclick: () => {
            if (!form.validate()) return;
            openLink(whatsappUrl(entry.patient.phone,
              freeSlotText(entry.patient.fullName, form.startAt(), settings.clinicSignature)));
          },
        }, 'WhatsApp'),
        h('button', {
          type: 'button', class: 'btn primary',
          onclick: async () => {
            if (!form.validate()) return;
            try {
              const booked = await api('POST', `/api/waitlist/${entry.id}/book`, { doctorId, startAt: form.startAt() });
              dayScreen(form.date(), booked.id);
            } catch (e) {
              if (!fatal(e)) form.showError(e);
            }
          },
        }, entry.upcoming ? 'Перенести сюда' : 'Записать сюда'))))) : null;

  const forDoctor = team.doctors.length > 1 ? h('div', { class: 'for-doctor' }, avatar(doctorById(doctorId)),
    h('span', {}, 'к врачу ', h('strong', {}, doctorById(doctorId)?.fullName || ''))) : null;
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Новая запись'), forDoctor,
    waiting, patientBox, patientError, form.node));
  drawPatient();
  mainButton.set('Записать', submit);
}

// ---------- Старт ----------

async function start() {
  if (tg) {
    tg.ready();
    tg.expand();
    const applyScheme = () => {
      document.documentElement.classList.toggle('dark', tg.colorScheme === 'dark');
      // Шапка и главная кнопка Telegram — в цвет приложения, а не синие по умолчанию
      const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
      try {
        tg.setHeaderColor?.('secondary_bg_color');
        tg.setBackgroundColor?.('secondary_bg_color');
        tg.MainButton.setParams({ color: accent, text_color: '#ffffff' });
      } catch {
        // старый Telegram без этих методов — остаются его цвета
      }
    };
    applyScheme();
    tg.onEvent('themeChanged', applyScheme);
  }
  // Вне Telegram (ноутбук, профиль local): подписанная тестовая initData от DevInitDataController
  if (!initData && ['localhost', '127.0.0.1'].includes(location.hostname)) {
    const response = await fetch('/dev/init-data').catch(() => null);
    if (response?.ok) initData = await response.text();
  }
  if (!initData) {
    render(h('div', { class: 'message' }, 'Откройте приложение из бота в Telegram'));
    return;
  }
  // Кто открыл: врач — «День», остальные — самозапись (patient.js)
  let me;
  try {
    me = await api('GET', '/api/patient/me');
  } catch (e) {
    if (fatal(e)) return;
    render(h('div', { class: 'message' }, errorText(e),
      h('button', { type: 'button', class: 'btn primary', onclick: () => location.reload() }, 'Повторить')));
    return;
  }
  if (!me.doctor) {
    startPatient({
      h, render, api, mainButton, backButton, toast, haptic, fatal, errorText, openLink, tg: insideTelegram ? tg : null,
    }, me);
    return;
  }
  team.doctors = me.doctors;
  team.admin = me.admin;
  team.mine = me.doctorId;
  team.clinic = me.clinic;
  if (!team.doctors.length) {
    // Все врачи скрыты: админу — сразу «Врачи», иначе работать не с чем
    doctorsScreen(() => location.reload());
    return;
  }
  // Подпись клиники для текста напоминания; без неё напоминание всё равно работает
  api('GET', '/api/settings').then((loaded) => { settings = loaded; }).catch(() => {});
  const params = new URLSearchParams(location.search);
  // Свой «День» — по умолчанию; кнопка из уведомления о приёмах коллеги — ?doctor=ID
  const linked = Number(params.get('doctor'));
  team.current = doctorById(linked)?.id ?? doctorById(team.mine)?.id ?? team.doctors[0].id;
  // «Открыть приём» в уведомлении перед приёмом: сразу карточка этого приёма
  const appointmentId = params.get('appointment');
  if (appointmentId) {
    try {
      const opened = await api('GET', `/api/appointments/${encodeURIComponent(appointmentId)}`);
      if (doctorById(opened.doctorId)) team.current = opened.doctorId;
      cardScreen(opened);
      return;
    } catch (e) {
      if (fatal(e)) return; // иначе (приём удалён, сеть) — просто «День»
    }
  }
  // Кнопка «Открыть завтра» в сводке ведёт на ?day=tomorrow (DD3)
  dayScreen(params.get('day') === 'tomorrow' ? addDays(todayIn(), 1) : todayIn());
}

start();

// Mini App врача: «День», карточка приёма, «Новая запись». Без сборки и фреймворка (R2):
// три экрана и десяток запросов не окупают React и node в сборке.
import {
  rowState, todayIn, addDays, toInstant, timeOf, dateOf, minutesBetween, dayTitle, canMarkVisit,
  formatPhone, looksLikePhone, dayItems, windowDuration,
  reminderText, whatsappUrl, canRemind, readyCount, nextToRemind, fitsWindow, freeSlotText,
  weekLabel, noShowPercent, averageNoShows, noShowsPhrase, recallText, agoText,
} from './state.js';

const tg = window.Telegram?.WebApp;
const DURATIONS = [15, 30, 45, 60, 90];
const SESSION_EXPIRED = 'Сессия устарела — откройте заново из бота';

let initData = tg?.initData || '';
let settings = { clinicSignature: '' };

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
  return el;
}

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
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
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
function toast(text, actionText, action) {
  const el = document.getElementById('toast');
  clearTimeout(toastTimer);
  el.replaceChildren(h('span', {}, text), actionText ? h('button', {
    type: 'button',
    onclick: () => { el.hidden = true; action(); },
  }, actionText) : null);
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

// ---------- «День» ----------

async function dayScreen(date, flashId) {
  const id = ++screenId;
  hideToast();
  backButton.hide();
  mainButton.set('Новая запись', () => newScreen({ date }));

  const today = todayIn();
  const dateInput = h('input', {
    type: 'date', value: date, 'aria-label': 'Выбрать дату',
    onchange: (e) => e.target.value && dayScreen(e.target.value),
  });
  const head = h('header', { class: 'day-head' },
    h('div', { class: 'day-title' },
      h('button', { type: 'button', class: 'arrow', 'aria-label': 'Предыдущий день', onclick: () => dayScreen(addDays(date, -1)) }, '‹'),
      h('label', {}, h('h1', {}, dayTitle(date)), dateInput),
      h('button', { type: 'button', class: 'arrow', 'aria-label': 'Следующий день', onclick: () => dayScreen(addDays(date, 1)) }, '›')),
    h('div', { class: 'segments' },
      h('button', { type: 'button', 'aria-pressed': String(date === today), onclick: () => dayScreen(today) }, 'Сегодня'),
      h('button', { type: 'button', 'aria-pressed': String(date === addDays(today, 1)), onclick: () => dayScreen(addDays(today, 1)) }, 'Завтра'),
      h('button', { type: 'button', onclick: () => menuScreen(() => dayScreen(date)) }, 'Меню')));

  render(head, ...Array.from({ length: 6 }, () => h('div', { class: 'skeleton' })));

  let appointments;
  let slots;
  let waitlist;
  try {
    // Параллельно: приёмы дня, свободные окна (DD2) и лист ожидания — кого позвать в окно
    [appointments, slots, waitlist] = await Promise.all([
      api('GET', `/api/appointments?date=${date}`),
      api('GET', `/api/slots?date=${date}`),
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
    note.textContent = [!slots.workingDay && 'Выходной', isTomorrow && total > 0 && `Готово ${ready} из ${total}`]
      .filter(Boolean).join(' · ');
    const items = dayItems(appointments, slots.free);
    const next = date === todayIn(now)
      ? appointments.find((a) => a.status === 'SCHEDULED' && new Date(a.startAt) > now) : null;
    list.replaceChildren(...items.map((item) => (item.free
      ? freeRow(date, item, waitlist) : row(item, item === next, item.id === flash, now, draw))));
    flash = null;

    // Вечерний обход (DD6): на «Завтра» главная кнопка ведёт к следующему, кому не напомнили
    const nextUp = isTomorrow ? nextToRemind(appointments, pressed, now) : null;
    if (nextUp) {
      mainButton.set(`Напомнить следующему (${short(nextUp.patient.fullName)}, ${timeOf(nextUp.startAt)})`,
        () => remind(nextUp, draw));
    } else {
      mainButton.set('Новая запись', () => newScreen({ date }));
    }
  }

  if (appointments.length === 0 && slots.free.length === 0) {
    render(head, h('div', { class: 'message hint' }, 'На этот день записей нет'));
    note.textContent = slots.workingDay ? '' : 'Выходной';
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
function freeRow(date, slot, waitlist = []) {
  const candidates = waitlist.filter((entry) => fitsWindow(entry, slot));
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
  const state = failed ? { label: 'Не сохранено · повторить', icon: '!', tone: 'bad' } : rowState(a, now);
  const classes = ['row', isNext && 'next', flash && 'flash', state.struck && 'struck'].filter(Boolean).join(' ');
  const wa = canRemind(a, now) ? h('button', {
    type: 'button', class: 'wa', 'aria-label': `WhatsApp: ${a.patient.fullName}, ${timeOf(a.startAt)}`,
    onclick: (e) => {
      e.stopPropagation(); // не открывать карточку
      if (failed) {
        // «повторить» — только отметку: WhatsApp уже открывали
        unsaved.delete(a.id);
        markReminder(a, redraw);
        a.reminderOpenedAt = a.reminderOpenedAt || new Date().toISOString();
        redraw();
      } else {
        remind(a, redraw);
      }
    },
  }, failed ? '↻' : 'WA') : null;
  return h('li', {
    class: classes, role: 'listitem', tabindex: 0,
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
  ++screenId;
  mainButton.hide();
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
  if (a.status === 'SCHEDULED') {
    actions.push(
      h('div', { class: 'replies' }, REPLIES.map(([reply, text]) => {
        const b = button(text, () => act('reply', { reply: a.reply === reply ? 'NONE' : reply }));
        b.setAttribute('aria-pressed', String(a.reply === reply));
        return b;
      })),
      button('Перенести', () => rescheduleScreen(a)),
    );
    if (canRemind(a)) {
      actions.unshift(button('Напомнить в WhatsApp', () => remind(a, () => cardScreen(a)), 'btn primary'));
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
    actions.push(button('Исправить: не пришёл', () => act('status', { status: 'NO_SHOW' }, undoable('Отмечено: не пришёл')), 'btn danger'));
  } else if (a.status === 'NO_SHOW') {
    actions.push(button('Исправить: пришёл', () => act('status', { status: 'DONE' })));
  }

  render(h('section', { class: 'screen' },
    backButton.link(),
    h('h1', { class: 'time' }, `${timeOf(a.startAt)}–${timeOf(a.endAt)}`),
    h('div', { class: 'hint' }, dayTitle(dateOf(a.startAt))),
    h('p', {}, h('button', {
      type: 'button', class: 'link name-link', onclick: () => patientScreen(a.patient.id, () => cardScreen(a)),
    }, a.patient.fullName, ' ›'), h('br'), formatPhone(a.patient.phone)),
    h('p', { class: `tone-${state.tone}` }, `${state.icon} ${state.label}`),
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
function timeFields({ date, time = '', duration = 30, exclude }) {
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
    const query = `date=${dateInput.value}&duration=${fields.duration}${exclude ? `&exclude=${exclude}` : ''}`;
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
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Меню'),
    h('button', { type: 'button', class: 'btn', onclick: () => patientsScreen(self) }, 'Пациенты и лист ожидания'),
    h('button', { type: 'button', class: 'btn', onclick: () => recallScreen(self) }, 'Позвать на профосмотр'),
    h('button', { type: 'button', class: 'btn', onclick: () => statsScreen(self) }, 'Статистика неявок')));
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
async function statsScreen(back) {
  const id = ++screenId;
  mainButton.hide();
  backButton.set(back);
  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Статистика'), h('div', { class: 'skeleton' })));
  let weeks;
  try {
    weeks = await api('GET', '/api/appointments/stats?weeks=8');
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
    h('span', { class: 'name' }, dayTitle(dateOf(a.startAt), now)),
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
        await api('POST', '/api/waitlist', { patientId: p.id, durationMinutes: duration });
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

function newScreen({ date, time, duration, patient, candidates = [] }) {
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
  const form = timeFields({ date, time, duration });

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
        { patientId: picked.id, startAt: form.startAt(), durationMinutes: form.duration });
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
              const booked = await api('POST', `/api/waitlist/${entry.id}/book`, { startAt: form.startAt() });
              dayScreen(form.date(), booked.id);
            } catch (e) {
              if (!fatal(e)) form.showError(e);
            }
          },
        }, entry.upcoming ? 'Перенести сюда' : 'Записать сюда'))))) : null;

  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Новая запись'),
    waiting, patientBox, patientError, form.node));
  drawPatient();
  mainButton.set('Записать', submit);
}

// ---------- Старт ----------

async function start() {
  if (tg) {
    tg.ready();
    tg.expand();
    const applyScheme = () => document.documentElement.classList.toggle('dark', tg.colorScheme === 'dark');
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
  // Подпись клиники для текста напоминания; без неё напоминание всё равно работает
  api('GET', '/api/settings').then((loaded) => { settings = loaded; }).catch(() => {});
  const params = new URLSearchParams(location.search);
  // «Открыть приём» в уведомлении перед приёмом: сразу карточка этого приёма
  const appointmentId = params.get('appointment');
  if (appointmentId) {
    try {
      cardScreen(await api('GET', `/api/appointments/${encodeURIComponent(appointmentId)}`));
      return;
    } catch (e) {
      if (fatal(e)) return; // иначе (приём удалён, сеть) — просто «День»
    }
  }
  // Кнопка «Открыть завтра» в сводке ведёт на ?day=tomorrow (DD3)
  dayScreen(params.get('day') === 'tomorrow' ? addDays(todayIn(), 1) : todayIn());
}

start();

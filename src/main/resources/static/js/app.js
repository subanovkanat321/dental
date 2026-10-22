// Mini App врача: «День», карточка приёма, «Новая запись». Без сборки и фреймворка (R2):
// три экрана и десяток запросов не окупают React и node в сборке.
import {
  rowState, todayIn, addDays, toInstant, timeOf, dateOf, minutesBetween, dayTitle, canMarkVisit,
  formatPhone, looksLikePhone, dayItems, windowDuration,
} from './state.js';

const tg = window.Telegram?.WebApp;
const DURATIONS = [15, 30, 45, 60, 90];
const SESSION_EXPIRED = 'Сессия устарела — откройте заново из бота';

let initData = tg?.initData || '';

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
      h('button', { type: 'button', 'aria-pressed': String(date === addDays(today, 1)), onclick: () => dayScreen(addDays(today, 1)) }, 'Завтра')));

  render(head, ...Array.from({ length: 6 }, () => h('div', { class: 'skeleton' })));

  let appointments;
  let slots;
  try {
    // Параллельно: приёмы дня и свободные окна (DD2)
    [appointments, slots] = await Promise.all([
      api('GET', `/api/appointments?date=${date}`),
      api('GET', `/api/slots?date=${date}`),
    ]);
  } catch (error) {
    if (id !== screenId || fatal(error)) return;
    render(head, h('div', { class: 'message' }, h('div', {}, errorText(error)),
      h('button', { type: 'button', class: 'btn primary', onclick: () => dayScreen(date) }, 'Повторить')));
    return;
  }
  if (id !== screenId) return;

  // Нерабочий день (WORK_DAYS): «Выходной», строк «свободно» нет — записать можно через «Другое время» (ER5)
  if (!slots.workingDay) head.append(h('div', { class: 'hint day-note' }, 'Выходной'));
  const items = dayItems(appointments, slots.free);
  if (items.length === 0) {
    render(head, h('div', { class: 'message hint' }, 'На этот день записей нет'));
    return;
  }
  const now = new Date();
  // На «Сегодня» выделен ближайший предстоящий приём (DD1)
  const next = date === today
    ? appointments.find((a) => a.status === 'SCHEDULED' && new Date(a.startAt) > now) : null;
  render(head, h('ul', { class: 'rows', role: 'list' }, items.map((item) => (item.free
    ? freeRow(date, item) : row(item, item === next, item.id === flashId, now)))));
  if (flashId) haptic('success');
}

/** «свободно 11:00–12:00»: нажатие — «Новая запись» с этим временем (DD2). */
function freeRow(date, slot) {
  const open = () => newScreen({ date, time: timeOf(slot.startAt), duration: windowDuration(slot) });
  return h('li', {
    class: 'row free', role: 'listitem', tabindex: 0, onclick: open,
    onkeydown: (e) => e.key === 'Enter' && open(),
    'aria-label': `Свободно с ${timeOf(slot.startAt)} до ${timeOf(slot.endAt)}, записать`,
  },
  h('span', { class: 'time' }, timeOf(slot.startAt)),
  h('span', { class: 'hint' }, `свободно до ${timeOf(slot.endAt)}`));
}

function row(a, isNext, flash, now) {
  const state = rowState(a, now);
  const classes = ['row', isNext && 'next', flash && 'flash', state.struck && 'struck'].filter(Boolean).join(' ');
  return h('li', {
    class: classes, role: 'listitem', tabindex: 0,
    onclick: () => cardScreen(a),
    onkeydown: (e) => e.key === 'Enter' && cardScreen(a),
  },
  h('span', { class: 'time' }, timeOf(a.startAt)),
  h('span', { class: 'name' }, a.patient.fullName),
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
    h('p', {}, h('strong', {}, a.patient.fullName), h('br'), formatPhone(a.patient.phone)),
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

// ---------- Новая запись ----------

function newScreen({ date, time, duration }) {
  const id = ++screenId;
  hideToast();
  backButton.set(() => dayScreen(date));

  let picked = null; // выбранный пациент { id, fullName, phone }
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

  render(h('section', { class: 'screen' }, backButton.link(), h('h1', {}, 'Новая запись'),
    patientBox, patientError, form.node));
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
  dayScreen(todayIn());
}

start();

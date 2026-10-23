// Самозапись пациента: всё, что видит в Mini App не-врач. Общие кусочки (DOM, запросы,
// кнопки Telegram) приходят из app.js параметром ui — без циклических импортов.
import { todayIn, timeOf, toInstant, formatPhone, bookingDays, whenText, serviceEmoji, breakNote } from './state.js';

const STATUS = {
  REQUESTED: '⏳ Ждёт подтверждения врача — ответ придёт в этот чат',
  SCHEDULED: '✅ Подтверждена',
};

const TOOTH = new URL('../img/tooth.svg', import.meta.url).href;

/** Шапка экрана пациента: иллюстрация, заголовок и подзаголовок на бирюзовом фоне. */
function hero(h, title, subtitle) {
  return h('div', { class: 'hero' }, h('img', { src: TOOTH, alt: '', width: 72, height: 72 }),
    h('div', {}, h('h1', {}, title), h('p', {}, subtitle)));
}

export function startPatient(ui, me) {
  if (!me.bookingEnabled) {
    ui.mainButton.hide();
    ui.render(ui.h('div', { class: 'message' }, 'Это служебный бот записи клиники.',
      me.clinicContact ? ui.h('p', {}, `Записаться на приём: ${me.clinicContact}`) : null));
    return;
  }
  if (me.booking) bookingScreen(ui, me);
  else bookScreen(ui, me);
}

/** «Ваша запись»: когда, что, подтверждена ли; отменить. */
function bookingScreen(ui, me) {
  const { h } = ui;
  const b = me.booking;
  ui.mainButton.hide();
  ui.backButton.hide();
  const error = h('div', { class: 'error', role: 'alert' });
  let sure = false;
  const cancel = h('button', {
    type: 'button', class: 'btn danger',
    onclick: async () => {
      // Отмена — вторым нажатием: случайно отменить запись к врачу обидно
      if (!sure) {
        sure = true;
        cancel.textContent = 'Точно отменить? Нажмите ещё раз';
        return;
      }
      cancel.disabled = true;
      try {
        await ui.api('POST', '/api/patient/booking/cancel');
        ui.haptic('success');
        reload(ui);
      } catch (e) {
        cancel.disabled = false;
        error.textContent = ui.errorText(e);
      }
    },
  }, 'Отменить запись');
  ui.render(h('section', { class: 'screen' },
    hero(h, 'Ваша запись', 'Ждём вас в клинике 😊'),
    h('div', { class: 'booking-card' },
      h('p', { class: 'big' }, '📅 ', capitalize(`${whenText(b.startAt)}, в ${timeOf(b.startAt)}`)),
      h('div', {}, `${serviceEmoji(b.serviceName || '')} `, b.serviceName || 'Приём у стоматолога'),
      h('p', {}, h('span', { class: `status ${b.status === 'SCHEDULED' ? 'tone-ok' : 'tone-wait'}` }, STATUS[b.status] || b.status))),
    me.clinicContact ? h('p', { class: 'hint' }, `📞 Вопросы — по телефону клиники: ${me.clinicContact}`) : null,
    error,
    h('div', { class: 'danger-zone' }, cancel)));
}

/** Запись: услуга → день → время → имя, телефон из Telegram, согласие → «Записаться». */
function bookScreen(ui, me) {
  const { h } = ui;
  ui.backButton.hide();
  let service = me.services.length === 1 ? me.services[0] : null;
  let date = todayIn();
  let chosen = '';
  let seq = 0;

  const serviceBox = h('div', { class: 'field services' });
  const dayBox = h('div', { class: 'days', role: 'group', 'aria-label': 'День' });
  const grid = h('div', { class: 'slots', role: 'group', 'aria-label': 'Свободное время' });
  const timeError = h('div', { class: 'error', role: 'alert' });
  const nameInput = h('input', { type: 'text', value: me.fullName || '', maxLength: 200, autocomplete: 'name' });
  const nameError = h('div', { class: 'error' });
  const phoneBox = h('div', {});
  const phoneError = h('div', { class: 'error' });
  const consent = h('input', { type: 'checkbox', id: 'consent' });
  const consentError = h('div', { class: 'error' });
  const error = h('div', { class: 'error', role: 'alert' });

  function drawServices() {
    if (!me.services.length) {
      serviceBox.replaceChildren(h('div', { class: 'hint' }, 'Запись пока недоступна — позвоните в клинику.'));
      return;
    }
    serviceBox.replaceChildren(h('span', {}, 'Услуга'), ...me.services.map((s) => h('button', {
      type: 'button', class: 'btn', 'aria-pressed': String(service?.id === s.id),
      onclick: () => { service = s; chosen = ''; drawServices(); loadSlots(); },
    },
    h('span', { class: 'ico', 'aria-hidden': 'true' }, serviceEmoji(s.name)),
    // Для скринридера — «Чистка · 60 мин» одной фразой; на экране длительность отдельной строкой
    s.name, h('span', { class: 'sr-only' }, ` · ${s.durationMinutes} мин`),
    h('span', { class: 'dur', 'aria-hidden': 'true' }, `⏱ ${s.durationMinutes} мин`))));
  }

  function drawDays() {
    dayBox.replaceChildren(...bookingDays(todayIn(), Math.min(me.daysAhead, 14)).map((d) => h('button', {
      type: 'button', class: 'day', 'aria-pressed': String(d.date === date),
      onclick: () => { date = d.date; chosen = ''; drawDays(); loadSlots(); },
    }, d.label)));
  }

  async function loadSlots() {
    const mine = ++seq;
    timeError.textContent = '';
    if (!service) {
      grid.replaceChildren(h('div', { class: 'hint' }, 'Сначала выберите услугу'));
      return;
    }
    grid.replaceChildren(h('div', { class: 'hint' }, 'Загрузка…'));
    let slots;
    try {
      slots = await ui.api('GET', `/api/patient/slots?date=${date}&serviceTypeId=${service.id}`);
    } catch (e) {
      if (mine === seq) grid.replaceChildren(h('div', { class: 'error' }, ui.errorText(e)));
      return;
    }
    if (mine !== seq) return;
    const starts = slots.starts.map(timeOf);
    // Перерыв или выходной — подписью над сеткой, чтобы было понятно, куда делось время
    const notes = [...slots.breaks, slots.dayOff].filter(Boolean)
      .map((b) => h('div', { class: 'break-note' }, breakNote(b)));
    grid.replaceChildren(...notes, ...(starts.length ? starts.map((t) => h('button', {
      type: 'button', class: 'slot time', 'aria-pressed': String(t === chosen),
      onclick: () => { chosen = t; timeError.textContent = ''; markChosen(); },
    }, t)) : [h('div', { class: 'hint' }, slots.dayOff ? 'Выберите другой день' : slots.workingDay ? 'На этот день свободного времени нет' : 'Выходной')]));
  }

  function markChosen() {
    grid.querySelectorAll('.slot').forEach((b) => b.setAttribute('aria-pressed', String(b.textContent === chosen)));
  }

  /**
   * Телефон — из Telegram: requestContact показывает системный запрос, номер приходит боту
   * (сервер сохраняет его), а мы ждём, пока он появится в /api/patient/me.
   */
  function drawPhone() {
    if (me.phone) {
      phoneBox.replaceChildren(h('div', { class: 'picked' },
        h('span', {}, formatPhone(me.phone), h('br'), h('span', { class: 'hint' }, 'номер из Telegram'))));
      return;
    }
    const share = h('button', {
      type: 'button', class: 'btn',
      onclick: () => {
        phoneError.textContent = '';
        if (!ui.tg?.requestContact) {
          phoneError.textContent = 'Откройте запись из бота в Telegram';
          return;
        }
        ui.tg.requestContact(async (shared) => {
          if (!shared) {
            phoneError.textContent = 'Без номера записаться нельзя: на него придёт напоминание';
            return;
          }
          share.disabled = true;
          share.textContent = 'Получаем номер…';
          for (let i = 0; i < 10 && !me.phone; i += 1) {
            await new Promise((r) => setTimeout(r, 800));
            try {
              const fresh = await ui.api('GET', '/api/patient/me');
              me.phone = fresh.phone;
              if (!nameInput.value && fresh.fullName) nameInput.value = fresh.fullName;
            } catch {
              // повторим
            }
          }
          if (!me.phone) {
            share.disabled = false;
            share.textContent = 'Поделиться номером';
            phoneError.textContent = 'Номер не дошёл — попробуйте ещё раз';
          }
          drawPhone();
        });
      },
    }, 'Поделиться номером');
    phoneBox.replaceChildren(share);
  }

  async function submit() {
    [timeError, nameError, phoneError, consentError, error].forEach((e) => { e.textContent = ''; });
    if (!service) { error.textContent = 'Выберите услугу'; return; }
    if (!chosen) { timeError.textContent = 'Выберите время'; return; }
    if (!nameInput.value.trim()) { nameError.textContent = 'Укажите имя пациента'; return; }
    if (!me.phone) { phoneError.textContent = 'Поделитесь номером телефона'; return; }
    if (!consent.checked) { consentError.textContent = 'Нужно согласие на обработку данных'; return; }
    ui.mainButton.busy(true);
    try {
      const booking = await ui.api('POST', '/api/patient/booking', {
        serviceTypeId: service.id, startAt: toInstant(date, chosen), fullName: nameInput.value, consent: true,
      });
      ui.haptic('success');
      me.booking = booking;
      bookingScreen(ui, me);
    } catch (e) {
      ui.mainButton.busy(false);
      ui.haptic('error');
      if (e.code === 'time-taken') {
        timeError.textContent = 'Это время уже заняли — выберите другое';
        chosen = '';
        loadSlots();
      } else if (e.code === 'already-booked') {
        reload(ui);
      } else {
        const f = e.fields || {};
        nameError.textContent = f.fullName || '';
        phoneError.textContent = f.phone || '';
        consentError.textContent = f.consent || '';
        if (!f.fullName && !f.phone && !f.consent) error.textContent = ui.errorText(e);
      }
    } finally {
      ui.mainButton.busy(false);
    }
  }

  ui.render(h('section', { class: 'screen' },
    hero(h, 'Запись к стоматологу', 'Выберите услугу и удобное время — врач подтвердит запись'),
    serviceBox,
    h('div', { class: 'field' }, h('span', {}, 'День'), dayBox),
    h('div', { class: 'field' }, h('span', {}, 'Время'), timeError, grid),
    h('label', { class: 'field' }, h('span', {}, 'Имя пациента'), nameInput, nameError),
    h('div', { class: 'field' }, h('span', {}, 'Телефон'), phoneBox, phoneError),
    h('label', { class: 'consent', for: 'consent' }, consent,
      ' Согласен(на) на обработку персональных данных (имя и телефон) клиникой для записи на приём и напоминаний о нём'),
    consentError,
    error));
  drawServices();
  drawDays();
  drawPhone();
  loadSlots();
  ui.mainButton.set('Записаться', submit);
}

function capitalize(text) {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

async function reload(ui) {
  try {
    startPatient(ui, await ui.api('GET', '/api/patient/me'));
  } catch (e) {
    if (!ui.fatal(e)) ui.toast(ui.errorText(e));
  }
}


// Самозапись пациента: всё, что видит в Mini App не-врач. Общие кусочки (DOM, запросы,
// кнопки Telegram) приходят из app.js параметром ui — без циклических импортов.
import { todayIn, timeOf, toInstant, dateOf, formatPhone, serviceEmoji, initials } from './state.js';
import { t, setLang, currentLang, normalizeLang, whenAt, bookingDays, breakNote, priceText } from './i18n.js';

const STATUS = { REQUESTED: 'requested', SCHEDULED: 'scheduled' };

const TOOTH = new URL('../img/tooth.svg', import.meta.url).href;

/**
 * Шапка экрана пациента на бирюзовом фоне: фото врача (если врач загрузил) или рисунок зуба,
 * заголовок, имя врача и пара слов о нём — или подзаголовок по умолчанию.
 */
function hero(h, title, subtitle, profile) {
  const photo = profile?.photoUrl;
  return h('div', { class: 'hero' },
    h('img', { src: photo || TOOTH, alt: photo ? t('doctorPhoto') : '', width: 72, height: 72, class: photo ? 'hero-photo' : null }),
    h('div', {}, h('h1', {}, title),
      profile?.doctorName ? h('div', { class: 'hero-doctor' }, profile.doctorName) : null,
      h('p', {}, profile?.about || subtitle)));
}

/** Экран, на котором пациент сейчас: переключатель языка перерисовывает именно его. */
let redraw = () => {};

/**
 * «Русский | Кыргызча» над шапкой. Выбор уходит на сервер: на этом языке дальше пишет и бот —
 * подтверждение, напоминание, вопрос об оценке. Имена врачей, услуги и «о себе» — как их ввёл врач.
 */
function langSwitch(ui) {
  const { h } = ui;
  const pick = (code) => async () => {
    if (code === currentLang()) return;
    setLang(code);
    redraw();
    try {
      await ui.api('PUT', '/api/patient/language', { lang: code });
    } catch {
      // Не сохранилось — экран уже на новом языке, бот напишет на старом; при следующем входе повторим
    }
  };
  return h('div', { class: 'lang-switch', role: 'group', 'aria-label': 'Тил / Язык' },
    ['ru', 'ky'].map((code) => h('button', {
      type: 'button', 'aria-pressed': String(code === currentLang()), onclick: pick(code),
    }, t(code === 'ru' ? 'langRu' : 'langKy'))));
}

export function startPatient(ui, me) {
  // Язык: выбранный раньше (сервер) или язык Telegram — тогда сохраняем его, чтобы бот писал так же
  const fromTelegram = normalizeLang(ui.tg?.initDataUnsafe?.user?.language_code);
  setLang(me.lang || fromTelegram);
  if (!me.lang) ui.api('PUT', '/api/patient/language', { lang: currentLang() }).catch(() => {});
  if (!me.bookingEnabled) {
    ui.mainButton.hide();
    ui.render(ui.h('div', { class: 'message' }, t('serviceBot'),
      me.clinicContact ? ui.h('p', {}, t('bookBy', { phone: me.clinicContact })) : null));
    return;
  }
  if (me.booking) bookingScreen(ui, me);
  else if (me.doctors.length === 1) bookScreen(ui, me, me.doctors[0]);
  else doctorScreen(ui, me);
}

/** Ошибка для пациента: нет связи — на его языке; текст сервера — как пришёл. */
function errorText(ui, e) {
  return e?.status ? ui.errorText(e) : t('noConnection');
}

/** «📍 Бишкек, ул. Шевченко 6 · Открыть в 2ГИС» — если админ заполнил «О клинике». */
function clinicCard(ui, clinic) {
  const { h } = ui;
  if (!clinic?.address && !clinic?.mapUrl) return null;
  return h('div', { class: 'clinic-card' },
    h('span', { class: 'clinic-pin', 'aria-hidden': 'true' }, '📍'),
    h('span', { class: 'clinic-address' }, clinic.address || t('findUs')),
    clinic.mapUrl ? h('button', { type: 'button', class: 'btn map-btn', onclick: () => ui.openLink(clinic.mapUrl) }, t('open2gis')) : null);
}

/** Внизу экрана: не получается записаться — к техподдержке (Telegram или звонок). */
function supportFooter(ui, clinic) {
  const { h } = ui;
  if (!clinic?.supportUrl && !clinic?.supportPhone) return null;
  const open = () => (clinic.supportUrl ? ui.openLink(clinic.supportUrl)
    : ui.openLink(`${new URL('../call.html', import.meta.url)}#${clinic.supportPhone}`));
  return h('p', { class: 'support-footer' }, t('cantBook'),
    h('button', { type: 'button', class: 'link', onclick: open }, t('support')));
}

/** Профиль врача для шапки: фото, имя, пара слов. */
function profileOf(d) {
  return d ? { photoUrl: d.photoUrl, doctorName: d.fullName, about: d.about } : null;
}

/** Врачей несколько — сначала выбрать, к кому: у каждого своё кресло и своё время. */
function doctorScreen(ui, me) {
  const { h } = ui;
  redraw = () => doctorScreen(ui, me);
  ui.mainButton.hide();
  ui.backButton.hide();
  ui.render(h('section', { class: 'screen' },
    langSwitch(ui),
    hero(h, t('bookTitle'), t('pickDoctorSub')),
    h('h2', {}, t('doctor')),
    me.doctors.length ? me.doctors.map((d) => h('button', {
      type: 'button', class: 'doctor-card', onclick: () => bookScreen(ui, me, d),
    },
    d.photoUrl ? h('img', { src: d.photoUrl, alt: '', width: 64, height: 64 })
      : h('span', { class: 'doctor-initials', 'aria-hidden': 'true' }, initials(d.fullName)),
    h('span', { class: 'doctor-card-text' }, h('strong', {}, d.fullName), d.about ? h('span', { class: 'hint' }, d.about) : null),
    h('span', { class: 'chevron', 'aria-hidden': 'true' }, '›')))
      : h('div', { class: 'hint' }, t('unavailable')),
    clinicCard(ui, me.clinic),
    supportFooter(ui, me.clinic)));
}

/** «Ваша запись»: когда, что, подтверждена ли; отменить. */
function bookingScreen(ui, me) {
  const { h } = ui;
  const b = me.booking;
  redraw = () => bookingScreen(ui, me);
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
        cancel.textContent = t('cancelSure');
        return;
      }
      cancel.disabled = true;
      try {
        await ui.api('POST', '/api/patient/booking/cancel');
        ui.haptic('success');
        reload(ui);
      } catch (e) {
        cancel.disabled = false;
        error.textContent = errorText(ui, e);
      }
    },
  }, t('cancel'));
  ui.render(h('section', { class: 'screen' },
    langSwitch(ui),
    hero(h, t('yourBooking'), t('waitingYou'), profileOf(me.doctors.find((d) => d.id === b.doctorId))
      || { doctorName: b.doctorName }),
    h('div', { class: 'booking-card' },
      h('p', { class: 'big' }, '📅 ', whenAt(b.startAt)),
      h('div', {}, `${serviceEmoji(b.serviceName || '')} `, b.serviceName || t('defaultService')),
      h('p', {}, h('span', { class: `status ${b.status === 'SCHEDULED' ? 'tone-ok' : 'tone-wait'}` },
        STATUS[b.status] ? t(STATUS[b.status]) : b.status))),
    h('button', { type: 'button', class: 'btn', onclick: () => rescheduleScreen(ui, me) },
      h('span', { class: 'ico', 'aria-hidden': 'true' }, '🗓️'), t('moveOther')),
    clinicCard(ui, me.clinic),
    me.clinicContact ? h('p', { class: 'hint' }, t('clinicPhone', { phone: me.clinicContact })) : null,
    error,
    h('div', { class: 'danger-zone' }, cancel),
    supportFooter(ui, me.clinic)));
}

/**
 * Перенос своей записи: другой день и время у того же врача, той же длительности. Своё
 * прежнее время в сетке свободно. Новое время врач подтвердит — как и первую заявку.
 */
function rescheduleScreen(ui, me) {
  const { h } = ui;
  const b = me.booking;
  ui.backButton.set(() => bookingScreen(ui, me));
  redraw = () => rescheduleScreen(ui, me);
  let date = dateOf(b.startAt);
  let chosen = '';
  let seq = 0;
  const dayBox = h('div', { class: 'days', role: 'group', 'aria-label': t('day') });
  const grid = h('div', { class: 'slots', role: 'group', 'aria-label': t('freeTime') });
  const error = h('div', { class: 'error', role: 'alert' });

  function drawDays() {
    dayBox.replaceChildren(...bookingDays(todayIn(), Math.min(me.daysAhead, 14)).map((d) => h('button', {
      type: 'button', class: 'day', 'aria-pressed': String(d.date === date),
      onclick: () => { date = d.date; chosen = ''; drawDays(); loadSlots(); },
    }, d.label)));
  }

  async function loadSlots() {
    const mine = ++seq;
    error.textContent = '';
    grid.replaceChildren(h('div', { class: 'hint' }, t('loading')));
    let slots;
    try {
      slots = await ui.api('GET', `/api/patient/booking/slots?date=${date}`);
    } catch (e) {
      if (mine === seq) grid.replaceChildren(h('div', { class: 'error' }, errorText(ui, e)));
      return;
    }
    if (mine !== seq) return;
    const notes = [...slots.breaks, slots.dayOff].filter(Boolean).map((x) => h('div', { class: 'break-note' }, breakNote(x)));
    const starts = slots.starts.map(timeOf);
    grid.replaceChildren(...notes, ...(starts.length ? starts.map((time) => h('button', {
      type: 'button', class: 'slot time', 'aria-pressed': String(time === chosen),
      onclick: () => { chosen = time; grid.querySelectorAll('.slot').forEach((x) => x.setAttribute('aria-pressed', String(x.textContent === time))); },
    }, time)) : [h('div', { class: 'hint' }, slots.workingDay ? t('noFree') : t('dayOff'))]));
  }

  ui.render(h('section', { class: 'screen' },
    langSwitch(ui),
    h('h1', {}, t('moveTitle')),
    h('p', { class: 'hint' }, t('moveNow', { when: whenAt(b.startAt) })),
    h('div', { class: 'field' }, h('span', {}, t('day')), dayBox),
    h('div', { class: 'field' }, h('span', {}, t('time')), error, grid)));
  drawDays();
  loadSlots();
  ui.mainButton.set(t('move'), async () => {
    if (!chosen) { error.textContent = t('pickTime'); return; }
    ui.mainButton.busy(true);
    try {
      me.booking = await ui.api('POST', '/api/patient/booking/reschedule', { startAt: toInstant(date, chosen) });
      ui.haptic('success');
      ui.toast(t('moved'), null, null, 'ok');
      bookingScreen(ui, me);
    } catch (e) {
      ui.haptic('error');
      error.textContent = e.code === 'time-taken' ? t('taken') : errorText(ui, e);
      if (e.code === 'time-taken') { chosen = ''; loadSlots(); }
    } finally {
      ui.mainButton.busy(false);
    }
  });
}

/** Запись: услуга → день → время → имя, телефон из Telegram, согласие → «Записаться». */
function bookScreen(ui, me, doctor) {
  const { h } = ui;
  // Несколько врачей — «Назад» к выбору врача
  if (me.doctors.length > 1) ui.backButton.set(() => doctorScreen(ui, me));
  else ui.backButton.hide();
  // Сменили язык — тот же экран заново, но выбранное (услуга, день, время, имя) не теряем
  const state = bookScreen.state?.doctor === doctor.id ? bookScreen.state : {};
  let service = state.service ?? (me.services.length === 1 ? me.services[0] : null);
  let date = state.date ?? todayIn();
  let chosen = state.chosen ?? '';
  let seq = 0;
  redraw = () => {
    bookScreen.state = { doctor: doctor.id, service, date, chosen, name: nameInput.value, consent: consent.checked };
    bookScreen(ui, me, doctor);
  };

  const serviceBox = h('div', { class: 'field services' });
  const dayBox = h('div', { class: 'days', role: 'group', 'aria-label': t('day') });
  const grid = h('div', { class: 'slots', role: 'group', 'aria-label': t('freeTime') });
  const timeError = h('div', { class: 'error', role: 'alert' });
  const nameInput = h('input', { type: 'text', value: state.name ?? (me.fullName || ''), maxLength: 200, autocomplete: 'name' });
  const nameError = h('div', { class: 'error' });
  const phoneBox = h('div', {});
  const phoneError = h('div', { class: 'error' });
  const consent = h('input', { type: 'checkbox', id: 'consent', checked: Boolean(state.consent) });
  bookScreen.state = null;
  const consentError = h('div', { class: 'error' });
  const error = h('div', { class: 'error', role: 'alert' });

  function drawServices() {
    if (!me.services.length) {
      serviceBox.replaceChildren(h('div', { class: 'hint' }, t('unavailable')));
      return;
    }
    serviceBox.replaceChildren(h('span', {}, t('service')), ...me.services.map((s) => h('button', {
      type: 'button', class: 'btn', 'aria-pressed': String(service?.id === s.id),
      onclick: () => { service = s; chosen = ''; drawServices(); loadSlots(); },
    },
    h('span', { class: 'ico', 'aria-hidden': 'true' }, serviceEmoji(s.name)),
    // Для скринридера — «Чистка · 60 мин» одной фразой; на экране длительность отдельной строкой
    s.name, h('span', { class: 'sr-only' }, ` · ${s.durationMinutes} ${t('min')}`),
    h('span', { class: 'dur', 'aria-hidden': 'true' }, `⏱ ${s.durationMinutes} ${t('min')}`),
    priceText(s.priceSom) ? h('span', { class: 'price' }, priceText(s.priceSom)) : null)));
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
      grid.replaceChildren(h('div', { class: 'hint' }, t('pickServiceFirst')));
      return;
    }
    grid.replaceChildren(h('div', { class: 'hint' }, t('loading')));
    let slots;
    try {
      slots = await ui.api('GET', `/api/patient/slots?date=${date}&doctorId=${doctor.id}&serviceTypeId=${service.id}`);
    } catch (e) {
      if (mine === seq) grid.replaceChildren(h('div', { class: 'error' }, errorText(ui, e)));
      return;
    }
    if (mine !== seq) return;
    const starts = slots.starts.map(timeOf);
    // Перерыв или выходной — подписью над сеткой, чтобы было понятно, куда делось время
    const notes = [...slots.breaks, slots.dayOff].filter(Boolean)
      .map((b) => h('div', { class: 'break-note' }, breakNote(b)));
    grid.replaceChildren(...notes, ...(starts.length ? starts.map((time) => h('button', {
      type: 'button', class: 'slot time', 'aria-pressed': String(time === chosen),
      onclick: () => { chosen = time; timeError.textContent = ''; markChosen(); },
    }, time)) : [h('div', { class: 'hint' }, slots.dayOff ? t('otherDay') : slots.workingDay ? t('noFree') : t('dayOff'))]));
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
        h('span', {}, formatPhone(me.phone), h('br'), h('span', { class: 'hint' }, t('phoneFromTelegram')))));
      return;
    }
    const share = h('button', {
      type: 'button', class: 'btn',
      onclick: () => {
        phoneError.textContent = '';
        if (!ui.tg?.requestContact) {
          phoneError.textContent = t('openFromBot');
          return;
        }
        ui.tg.requestContact(async (shared) => {
          if (!shared) {
            phoneError.textContent = t('needPhone');
            return;
          }
          share.disabled = true;
          share.textContent = t('gettingPhone');
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
            share.textContent = t('sharePhone');
            phoneError.textContent = t('phoneLost');
          }
          drawPhone();
        });
      },
    }, t('sharePhone'));
    phoneBox.replaceChildren(share);
  }

  async function submit() {
    [timeError, nameError, phoneError, consentError, error].forEach((e) => { e.textContent = ''; });
    if (!service) { error.textContent = t('pickService'); return; }
    if (!chosen) { timeError.textContent = t('pickTime'); return; }
    if (!nameInput.value.trim()) { nameError.textContent = t('nameRequired'); return; }
    if (!me.phone) { phoneError.textContent = t('phoneRequired'); return; }
    if (!consent.checked) { consentError.textContent = t('consentRequired'); return; }
    ui.mainButton.busy(true);
    try {
      const booking = await ui.api('POST', '/api/patient/booking', {
        doctorId: doctor.id, serviceTypeId: service.id, startAt: toInstant(date, chosen), fullName: nameInput.value, consent: true,
      });
      ui.haptic('success');
      me.booking = booking;
      bookingScreen(ui, me);
    } catch (e) {
      ui.mainButton.busy(false);
      ui.haptic('error');
      if (e.code === 'time-taken') {
        timeError.textContent = t('taken');
        chosen = '';
        loadSlots();
      } else if (e.code === 'already-booked') {
        reload(ui);
      } else {
        // Ошибки полей — своими словами на языке пациента (сервер отвечает по-русски)
        const f = e.fields || {};
        nameError.textContent = f.fullName ? (currentLang() === 'ru' ? f.fullName : t('nameRequired')) : '';
        phoneError.textContent = f.phone ? (currentLang() === 'ru' ? f.phone : t('phoneRequired')) : '';
        consentError.textContent = f.consent ? (currentLang() === 'ru' ? f.consent : t('consentRequired')) : '';
        if (!f.fullName && !f.phone && !f.consent) error.textContent = errorText(ui, e);
      }
    } finally {
      ui.mainButton.busy(false);
    }
  }

  ui.render(h('section', { class: 'screen' },
    langSwitch(ui),
    hero(h, t('bookTitle'), t('pickServiceSub'), profileOf(doctor)),
    me.doctors.length > 1 ? h('button', { type: 'button', class: 'link', onclick: () => doctorScreen(ui, me) }, t('otherDoctor')) : null,
    me.doctors.length > 1 ? null : clinicCard(ui, me.clinic),
    serviceBox,
    h('div', { class: 'field' }, h('span', {}, t('day')), dayBox),
    h('div', { class: 'field' }, h('span', {}, t('time')), timeError, grid),
    h('label', { class: 'field' }, h('span', {}, t('patientName')), nameInput, nameError),
    h('div', { class: 'field' }, h('span', {}, t('phone')), phoneBox, phoneError),
    h('label', { class: 'consent', for: 'consent' }, consent, ` ${t('consent')}`),
    consentError,
    error,
    supportFooter(ui, me.clinic)));
  drawServices();
  drawDays();
  drawPhone();
  loadSlots();
  ui.mainButton.set(t('book'), submit);
}

async function reload(ui) {
  try {
    startPatient(ui, await ui.api('GET', '/api/patient/me'));
  } catch (e) {
    if (!ui.fatal(e)) ui.toast(errorText(ui, e));
  }
}


// Чистые функции без DOM и Telegram: их проверяет `node --test src/test/js` (CI).

/** Бишкек живёт по UTC+6 круглый год (переход на летнее время отменён в 2005). */
export const ZONE = 'Asia/Bishkek';
const OFFSET = '+06:00';

/**
 * Одно состояние строки «Дня» (DD4 + ER2): первое совпавшее сверху вниз.
 * Поля в базе не меняются — это только подпись для врача.
 * tone — цвет: ok, wait, bad, hint.
 */
export function rowState(a, now = new Date()) {
  if (a.status === 'CANCELLED') return { label: 'Отменён', icon: '✕', tone: 'hint', struck: true };
  if (a.status === 'DONE') return { label: 'Пришёл', icon: '✓', tone: 'ok' };
  if (a.status === 'NO_SHOW') return { label: 'Не пришёл', icon: '!', tone: 'bad' };
  if (new Date(a.endAt) < now) return { label: 'Отметьте: пришёл?', icon: '?', tone: 'wait' };
  if (a.reply === 'CONFIRMED') return { label: 'Придёт', icon: '●', tone: 'ok' };
  if (a.reply === 'RESCHEDULE') return { label: 'Перенос', icon: '↻', tone: 'wait' };
  if (a.reply === 'NO_ANSWER') return { label: 'Нет ответа', icon: '?', tone: 'wait' };
  if (a.reminderOpenedAt) return { label: 'Ждём ответа', icon: '◐', tone: 'wait' };
  return { label: 'Не напомнили', icon: '○', tone: 'hint' };
}

/** Сегодняшняя дата по Бишкеку: 'YYYY-MM-DD'. */
export function todayIn(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(now);
}

export function addDays(date, days) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** '2099-06-01' + '10:00' по Бишкеку → '2099-06-01T04:00:00.000Z' для API. */
export function toInstant(date, time) {
  return new Date(`${date}T${time}:00${OFFSET}`).toISOString();
}

/** Instant из API → 'HH:MM' по Бишкеку. */
export function timeOf(instant) {
  return new Intl.DateTimeFormat('ru-RU', { timeZone: ZONE, hour: '2-digit', minute: '2-digit' })
    .format(new Date(instant));
}

/** Instant из API → 'YYYY-MM-DD' по Бишкеку. */
export function dateOf(instant) {
  return todayIn(new Date(instant));
}

export function minutesBetween(from, to) {
  return Math.round((new Date(to) - new Date(from)) / 60000);
}

/** Заголовок дня: «Чт, 23 окт · Завтра». */
export function dayTitle(date, now = new Date()) {
  const label = new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short' })
    .format(new Date(`${date}T00:00:00Z`)).replace('.', '');
  const today = todayIn(now);
  const rel = date === today ? 'Сегодня' : date === addDays(today, 1) ? 'Завтра'
    : date === addDays(today, -1) ? 'Вчера' : '';
  const title = label.charAt(0).toUpperCase() + label.slice(1);
  return rel ? `${title} · ${rel}` : title;
}

/** «Пришёл» / «Не пришёл» — только у приёмов сегодня и раньше (как проверяет сервер). */
export function canMarkVisit(a, now = new Date()) {
  return a.status === 'SCHEDULED' && dateOf(a.startAt) <= todayIn(now);
}

/** 996555123456 → +996 555 123 456; другие страны — +цифры. */
export function formatPhone(digits) {
  const kg = /^996(\d{3})(\d{3})(\d{3})$/.exec(digits);
  return kg ? `+996 ${kg[1]} ${kg[2]} ${kg[3]}` : `+${digits}`;
}

/** Ввод похож на номер — тогда «Новый пациент» подставит его в поле телефона, а не имени. */
export function looksLikePhone(text) {
  return /^[+\d\s\-()]+$/.test(text) && text.replace(/\D/g, '').length >= 3;
}

/**
 * Строки «Дня»: приёмы и окна «свободно» (DD2) по времени начала.
 * Окно — { free: true, startAt, endAt }; приём — как пришёл из API.
 */
export function dayItems(appointments, free) {
  const windows = free.map((w) => ({ free: true, startAt: w.start, endAt: w.end }));
  return [...appointments, ...windows].sort((x, y) => new Date(x.startAt) - new Date(y.startAt));
}

/** Нажатие на «свободно»: длительность min(30, длина окна) — окно всегда ≥ 15 минут (ER4). */
export function windowDuration(window) {
  return Math.min(30, minutesBetween(window.startAt, window.endAt));
}

// ---------- Напоминание в WhatsApp (DD12, ER3) ----------

// Винительный падеж: «в четверг», но «во вторник», «в среду», «в пятницу», «в субботу»
const WEEKDAY_ON = ['в воскресенье', 'в понедельник', 'во вторник', 'в среду', 'в четверг', 'в пятницу', 'в субботу'];

/** «сегодня» / «завтра» / «в четверг, 23 октября» — по Бишкеку на момент нажатия. */
export function whenText(startAt, now = new Date()) {
  const day = dateOf(startAt);
  const today = todayIn(now);
  if (day === today) return 'сегодня';
  if (day === addDays(today, 1)) return 'завтра';
  const date = new Date(`${day}T00:00:00Z`);
  const dayMonth = new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', day: 'numeric', month: 'long' }).format(date);
  return `${WEEKDAY_ON[date.getUTCDay()]}, ${dayMonth}`;
}

/** Текст напоминания пациенту. В приложении не редактируется — врач может поправить его в WhatsApp. */
export function reminderText(a, signature = '', now = new Date()) {
  const text = `Здравствуйте, ${a.patient.fullName}! Напоминаем: ${whenText(a.startAt, now)} в ${timeOf(a.startAt)} `
    + 'приём у стоматолога. Ответьте 1 — приду, 2 — перенести.';
  return signature ? `${text} ${signature}` : text;
}

/** Ссылка wa.me собирается здесь, в браузере: телефон не попадает ни в URL приложения, ни в бота. */
export function whatsappUrl(phone, text) {
  return `https://wa.me/${phone}?text=${encodeURIComponent(text)}`;
}

/** Кнопка WhatsApp есть у запланированного и ещё не закончившегося приёма. */
export function canRemind(a, now = new Date()) {
  return a.status === 'SCHEDULED' && new Date(a.endAt) > now;
}

/** «Готово» (ER3): напоминание открыто или пациент уже ответил. Одно правило для счётчика и «следующего». */
export function isReady(a) {
  return Boolean(a.reminderOpenedAt) || a.reply !== 'NONE';
}

/** «Готово X из N» — среди запланированных приёмов дня. */
export function readyCount(appointments) {
  const scheduled = appointments.filter((a) => a.status === 'SCHEDULED');
  return { ready: scheduled.filter(isReady).length, total: scheduled.length };
}

/** «Напомнить следующему» (DD6): первый по времени не готовый, кроме нажатых в этой сессии. */
export function nextToRemind(appointments, pressed = new Set(), now = new Date()) {
  return [...appointments]
    .sort((x, y) => new Date(x.startAt) - new Date(y.startAt))
    .find((a) => canRemind(a, now) && !isReady(a) && !pressed.has(a.id)) || null;
}

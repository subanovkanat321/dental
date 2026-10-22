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

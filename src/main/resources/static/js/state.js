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
  if (a.status === 'CANCELLED') return { label: 'Отменён', icon: '❌', tone: 'hint', struck: true };
  if (a.status === 'DONE') return { label: 'Приём состоялся', icon: '✅', tone: 'ok' };
  if (a.status === 'NO_SHOW') return { label: 'Неявка', icon: '🚷', tone: 'bad' };
  // Заявка пациента из бота: время занято, ждёт решения врача
  if (a.status === 'REQUESTED') return { label: 'Заявка — подтвердите', icon: '🆕', tone: 'wait' };
  if (new Date(a.endAt) < now) return { label: 'Отметьте: явка или неявка?', icon: '❓', tone: 'wait' };
  if (a.reply === 'CONFIRMED') return { label: 'Придёт', icon: '👍', tone: 'ok' };
  if (a.reply === 'RESCHEDULE') return { label: 'Перенос', icon: '🔄', tone: 'wait' };
  if (a.reply === 'NO_ANSWER') return { label: 'Нет ответа', icon: '📵', tone: 'wait' };
  if (a.reminderOpenedAt) return { label: 'Ждём ответа', icon: '⏳', tone: 'wait' };
  return { label: 'Не напомнили', icon: '🔔', tone: 'hint' };
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
export function dayItems(appointments, free, breaks = []) {
  const windows = free.map((w) => ({ free: true, startAt: w.start, endAt: w.end }));
  const pauses = breaks.map((b) => ({ pause: true, startAt: b.start, endAt: b.end, title: b.title, emoji: b.emoji }));
  return [...appointments, ...windows, ...pauses].sort((x, y) => new Date(x.startAt) - new Date(y.startAt));
}

/**
 * Пациенту — почему этого времени нет: «🕌 С 13:00 до 15:00 — джума-намаз, врач не принимает».
 * Выходной на весь день: «🏖️ Отпуск — в этот день приёма нет».
 */
export function breakNote(b) {
  if (!b.start) return `${b.emoji} ${b.title} — в этот день приёма нет`;
  const title = b.title.charAt(0).toLowerCase() + b.title.slice(1);
  return `${b.emoji} С ${timeOf(b.start)} до ${timeOf(b.end)} — ${title}, врач не принимает`;
}

const WEEKDAYS = { MONDAY: 'понедельник', TUESDAY: 'вторник', WEDNESDAY: 'среду', THURSDAY: 'четверг',
  FRIDAY: 'пятницу', SATURDAY: 'субботу', SUNDAY: 'воскресенье' };

/** Перерыв в списке врача: «Каждую пятницу · 13:00–15:00», «24–30 окт · весь день». */
export function timeOffWhen(t) {
  const hours = t.startTime ? `${t.startTime.slice(0, 5)}–${t.endTime.slice(0, 5)}` : 'весь день';
  if (t.weekday) {
    const every = t.weekday === 'WEDNESDAY' || t.weekday === 'FRIDAY' || t.weekday === 'SATURDAY' ? 'Каждую'
      : t.weekday === 'SUNDAY' ? 'Каждое' : 'Каждый';
    return `${every} ${WEEKDAYS[t.weekday]} · ${hours}`;
  }
  const fmt = (d) => new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', day: 'numeric', month: 'short' })
    .format(new Date(`${d}T00:00:00Z`)).replace('.', '');
  const days = t.firstDay === t.lastDay ? fmt(t.firstDay) : `${fmt(t.firstDay)} – ${fmt(t.lastDay)}`;
  return `${days} · ${hours}`;
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

// ---------- Лист ожидания ----------

/**
 * Кандидат ли пациент из листа ожидания в это окно: помещается по длительности и, если уже
 * записан, окно раньше его приёма — в лист ставят тех, кто хочет прийти раньше.
 */
export function fitsWindow(entry, window, doctorId) {
  // Хочет к другому врачу — не кандидат; к любому (null) или к этому — кандидат
  if (doctorId !== undefined && entry.doctorId != null && entry.doctorId !== doctorId) return false;
  // Записан к другому врачу — в это окно будет новая запись, «раньше» не важно
  const sameDoctor = doctorId === undefined || !entry.upcoming || entry.upcoming.doctorId === doctorId;
  const earlier = !entry.upcoming || !sameDoctor || new Date(window.startAt) < new Date(entry.upcoming.startAt);
  return earlier && entry.durationMinutes <= minutesBetween(window.startAt, window.endAt);
}

/** «Освободилось время»: пациенту из листа ожидания (по Бишкеку на момент нажатия). */
export function freeSlotText(fullName, startAt, signature = '', now = new Date()) {
  const text = `Здравствуйте, ${fullName}! Освободилось время у стоматолога: ${whenText(startAt, now)} `
    + `в ${timeOf(startAt)}. Хотите прийти? Ответьте «да» — запишем.`;
  return signature ? `${text} ${signature}` : text;
}

// ---------- Статистика ----------

/** «1–7 июн» или «25 мая – 31 мая» → «25–31 мая»; через месяц — «29 июн – 5 июл». */
export function weekLabel(weekStart) {
  const end = addDays(weekStart, 6);
  const fmt = (d, opts) => new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', ...opts })
    .format(new Date(`${d}T00:00:00Z`)).replace('.', '');
  const sameMonth = weekStart.slice(0, 7) === end.slice(0, 7);
  return sameMonth
    ? `${fmt(weekStart, { day: 'numeric' })}–${fmt(end, { day: 'numeric', month: 'short' })}`
    : `${fmt(weekStart, { day: 'numeric', month: 'short' })} – ${fmt(end, { day: 'numeric', month: 'short' })}`;
}

/** Доля неявок среди отмеченных приёмов, % (null — отмеченных нет). */
export function noShowPercent(week) {
  const marked = week.done + week.noShow;
  return marked ? Math.round((week.noShow / marked) * 100) : null;
}

/** Среднее неявок в неделю по прошедшим неделям (без текущей) — сравнить с «до приложения». */
export function averageNoShows(weeks) {
  const past = weeks.slice(1).filter((w) => w.booked > 0);
  if (!past.length) return null;
  return Math.round((past.reduce((sum, w) => sum + w.noShow, 0) / past.length) * 10) / 10;
}

/** «1 неявка», «2 неявки», «5 неявок», «1,5 неявки» (дробь — родительный падеж ед. числа). */
export function noShowsPhrase(n) {
  const number = String(n).replace('.', ',');
  if (!Number.isInteger(n)) return `${number} неявки`;
  const mod100 = n % 100;
  const mod10 = n % 10;
  const word = mod100 >= 11 && mod100 <= 14 ? 'неявок'
    : mod10 === 1 ? 'неявка' : mod10 >= 2 && mod10 <= 4 ? 'неявки' : 'неявок';
  return `${number} ${word}`;
}

// ---------- Профосмотры ----------

/** Приглашение на профосмотр в WhatsApp. */
export function recallText(fullName, signature = '') {
  const text = `Здравствуйте, ${fullName}! Прошло больше полугода с вашего визита к стоматологу — `
    + 'пора на профилактический осмотр. Ответьте, когда вам удобно, — запишем.';
  return signature ? `${text} ${signature}` : text;
}

/** «сегодня», «вчера», «5 дней назад», «2 мес назад» — по Бишкеку. */
export function agoText(instant, now = new Date()) {
  const days = Math.round((new Date(`${todayIn(now)}T00:00:00Z`) - new Date(`${dateOf(instant)}T00:00:00Z`)) / 86400000);
  if (days <= 0) return 'сегодня';
  if (days === 1) return 'вчера';
  if (days < 60) {
    const mod100 = days % 100;
    const mod10 = days % 10;
    const word = mod100 >= 11 && mod100 <= 14 ? 'дней' : mod10 === 1 ? 'день' : mod10 >= 2 && mod10 <= 4 ? 'дня' : 'дней';
    return `${days} ${word} назад`;
  }
  return `${Math.floor(days / 30)} мес назад`;
}

// ---------- Самозапись ----------

/** Дни для выбора пациентом: «Сегодня», «Завтра», «Пн 27», … — n дней начиная с сегодня. */
export function bookingDays(today, n) {
  return Array.from({ length: n }, (_, i) => {
    const date = addDays(today, i);
    const label = i === 0 ? 'Сегодня' : i === 1 ? 'Завтра'
      : new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', weekday: 'short', day: 'numeric' })
        .format(new Date(`${date}T00:00:00Z`)).replace(',', '');
    return { date, label: label.charAt(0).toUpperCase() + label.slice(1) };
  });
}

/**
 * Лента дней в шапке «Дня»: n дней с сегодняшнего; если выбранный день вне ленты (прошлое или
 * дальше n дней) — лента с него, начиная за 3 дня до, чтобы было видно соседей.
 */
export function stripDays(date, today, n = 21) {
  const inStrip = date >= today && date < addDays(today, n);
  const start = inStrip ? today : addDays(date, -3);
  const fmt = (opts, d) => new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', ...opts })
    .format(new Date(`${d}T00:00:00Z`)).replace('.', '');
  return Array.from({ length: n }, (_, i) => {
    const d = addDays(start, i);
    const weekday = new Date(`${d}T00:00:00Z`).getUTCDay();
    return {
      date: d,
      weekday: fmt({ weekday: 'short' }, d).replace(/^./, (c) => c.toUpperCase()),
      day: Number(d.slice(8)),
      month: fmt({ month: 'short' }, d),
      today: d === today,
      weekend: weekday === 0 || weekday === 6,
    };
  });
}

/** «3 записи», «1 запись», «5 записей» — для подписи дня в ленте. */
export function recordsPhrase(n) {
  const mod10 = n % 10;
  const mod100 = n % 100;
  const word = mod10 === 1 && mod100 !== 11 ? 'запись'
    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? 'записи' : 'записей';
  return `${n} ${word}`;
}

/** Эмодзи услуги по названию — для плиток самозаписи; незнакомая услуга — зуб. */
export function serviceEmoji(name) {
  const n = name.toLowerCase();
  const map = [['брекет', '😁'], ['кариес', '🦷'], ['чистк', '✨'], ['гигиен', '✨'], ['удален', '🩹'],
    ['канал', '🔬'], ['отбел', '💎'], ['имплант', '🔩'], ['протез', '👑'], ['коронк', '👑'], ['детск', '🧸'],
    ['осмотр', '🩺'], ['консульт', '🩺']];
  return map.find(([key]) => n.includes(key))?.[1] || '🦷';
}

/** «от 2 000 сом»; цены нет — null (не показываем). Деньги в API — целые сомы. */
export function priceText(som) {
  if (som === null || som === undefined) return null;
  return `от ${new Intl.NumberFormat('ru-RU').format(som)} сом`;
}

/** «1 оценка», «3 оценки», «12 оценок». */
export function ratingsPhrase(n) {
  const mod10 = n % 10;
  const mod100 = n % 100;
  const word = mod10 === 1 && mod100 !== 11 ? 'оценка'
    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? 'оценки' : 'оценок';
  return `${n} ${word}`;
}

/** Две буквы для кружка врача без фото: «Айгерим Садыкова» → «АС». */
export function initials(name) {
  return name.trim().split(/\s+/).slice(0, 2).map((w) => w.charAt(0).toUpperCase()).join('');
}

/** Когда напомнили: «сегодня в 18:05», «вчера в 18:05», «21 окт в 18:05» — по Бишкеку. */
export function sentText(instant, now = new Date()) {
  const day = dateOf(instant);
  const today = todayIn(now);
  const when = day === today ? 'сегодня' : day === addDays(today, -1) ? 'вчера'
    : new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', day: 'numeric', month: 'short' })
      .format(new Date(`${day}T00:00:00Z`)).replace('.', '');
  return `${when} в ${timeOf(instant)}`;
}

/** «3 000 сом» — деньги в API целые сомы. */
export function somText(som) {
  return `${new Intl.NumberFormat('ru-RU').format(som)} сом`;
}

export const PAYMENT_METHODS = [['CASH', 'Наличные'], ['TRANSFER', 'Перевод'], ['CARD', 'Карта']];

/** Первый и последний день месяца и недели (пн–вс) по дате 'YYYY-MM-DD' — для выручки. */
export function periodOf(date, kind) {
  if (kind === 'month') {
    const [y, m] = date.split('-').map(Number);
    const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
    return { from: `${date.slice(0, 7)}-01`, to: `${date.slice(0, 7)}-${String(last).padStart(2, '0')}` };
  }
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay() || 7;
  const from = addDays(date, 1 - weekday);
  return { from, to: addDays(from, 6) };
}

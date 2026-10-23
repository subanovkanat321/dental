// Язык экранов пациента: русский или кыргызский. Врачи и админ работают на русском (app.js).
// Все тексты — здесь, в одном месте: перевод может проверить носитель языка, не читая код.
import { addDays, dateOf, todayIn, timeOf, whenText as ruWhen, breakNote as ruBreak, priceText as ruPrice } from './state.js';

const TEXTS = {
  ru: {
    langRu: 'Русский',
    langKy: 'Кыргызча',
    bookTitle: 'Запись к стоматологу',
    pickDoctorSub: 'Выберите врача, услугу и удобное время — врач подтвердит запись',
    pickServiceSub: 'Выберите услугу и удобное время — врач подтвердит запись',
    doctor: 'Врач',
    unavailable: 'Запись пока недоступна — позвоните в клинику.',
    yourBooking: 'Ваша запись',
    waitingYou: 'Ждём вас в клинике 😊',
    defaultService: 'Приём у стоматолога',
    requested: '⏳ Ждёт подтверждения врача — ответ придёт в этот чат',
    scheduled: '✅ Подтверждена',
    moveOther: 'Перенести на другое время',
    clinicPhone: '📞 Вопросы — по телефону клиники: {phone}',
    cancel: 'Отменить запись',
    cancelSure: 'Точно отменить? Нажмите ещё раз',
    moveTitle: 'Перенести запись',
    moveNow: 'Сейчас: {when}. Новое время подтвердит врач.',
    day: 'День',
    days: 'Дни',
    time: 'Время',
    freeTime: 'Свободное время',
    loading: 'Загрузка…',
    noFree: 'На этот день свободного времени нет',
    dayOff: 'Выходной',
    otherDay: 'Выберите другой день',
    move: 'Перенести',
    pickTime: 'Выберите время',
    moved: '✅ Запись перенесена — ждём подтверждения врача',
    taken: 'Это время уже заняли — выберите другое',
    service: 'Услуга',
    min: 'мин',
    pickServiceFirst: 'Сначала выберите услугу',
    phoneFromTelegram: 'номер из Telegram',
    openFromBot: 'Откройте запись из бота в Telegram',
    needPhone: 'Без номера записаться нельзя: на него придёт напоминание',
    gettingPhone: 'Получаем номер…',
    sharePhone: 'Поделиться номером',
    phoneLost: 'Номер не дошёл — попробуйте ещё раз',
    pickService: 'Выберите услугу',
    nameRequired: 'Укажите имя пациента',
    phoneRequired: 'Поделитесь номером телефона',
    consentRequired: 'Нужно согласие на обработку данных',
    otherDoctor: '‹ Другой врач',
    patientName: 'Имя пациента',
    phone: 'Телефон',
    consent: 'Согласен(на) на обработку персональных данных (имя и телефон) клиникой для записи на приём и напоминаний о нём',
    book: 'Записаться',
    findUs: 'Как нас найти',
    open2gis: 'Открыть в 2ГИС',
    cantBook: 'Не получается записаться? ',
    support: '🛟 Техподдержка',
    serviceBot: 'Это служебный бот записи клиники.',
    bookBy: 'Записаться на приём: {phone}',
    doctorPhoto: 'Фото врача',
    noConnection: 'Нет связи с сервером',
    today: 'Сегодня',
    tomorrow: 'Завтра',
    whenAt: '{when}, в {time}',
  },
  ky: {
    langRu: 'Русский',
    langKy: 'Кыргызча',
    bookTitle: 'Тиш дарыгерине жазылуу',
    pickDoctorSub: 'Дарыгерди, кызматты жана ыңгайлуу убакытты тандаңыз — дарыгер жазылууну ырастайт',
    pickServiceSub: 'Кызматты жана ыңгайлуу убакытты тандаңыз — дарыгер жазылууну ырастайт',
    doctor: 'Дарыгер',
    unavailable: 'Азырынча жазылуу мүмкүн эмес — клиникага чалыңыз.',
    yourBooking: 'Сиздин жазылууңуз',
    waitingYou: 'Сизди клиникада күтөбүз 😊',
    defaultService: 'Тиш дарыгеринин кабыл алуусу',
    requested: '⏳ Дарыгердин ырастоосун күтүүдө — жооп ушул чатка келет',
    scheduled: '✅ Ырасталды',
    moveOther: 'Башка убакытка жылдыруу',
    clinicPhone: '📞 Суроолор боюнча — клиниканын телефону: {phone}',
    cancel: 'Жазылууну жокко чыгаруу',
    cancelSure: 'Чын эле жокко чыгарасызбы? Дагы бир жолу басыңыз',
    moveTitle: 'Жазылууну жылдыруу',
    moveNow: 'Азыр: {when}. Жаңы убакытты дарыгер ырастайт.',
    day: 'Күн',
    days: 'Күндөр',
    time: 'Убакыт',
    freeTime: 'Бош убакыт',
    loading: 'Жүктөлүүдө…',
    noFree: 'Бул күнү бош убакыт жок',
    dayOff: 'Дем алыш күн',
    otherDay: 'Башка күндү тандаңыз',
    move: 'Жылдыруу',
    pickTime: 'Убакытты тандаңыз',
    moved: '✅ Жазылуу жылдырылды — дарыгердин ырастоосун күтөбүз',
    taken: 'Бул убакыт бош эмес — башкасын тандаңыз',
    service: 'Кызмат',
    min: 'мүн.',
    pickServiceFirst: 'Адегенде кызматты тандаңыз',
    phoneFromTelegram: 'Telegram’дагы номер',
    openFromBot: 'Жазылууну Telegram’дагы боттон ачыңыз',
    needPhone: 'Номерсиз жазылууга болбойт: эскертме ушул номерге келет',
    gettingPhone: 'Номер алынууда…',
    sharePhone: 'Номер менен бөлүшүү',
    phoneLost: 'Номер келген жок — дагы бир жолу аракет кылыңыз',
    pickService: 'Кызматты тандаңыз',
    nameRequired: 'Бейтаптын атын жазыңыз',
    phoneRequired: 'Телефон номериңиз менен бөлүшүңүз',
    consentRequired: 'Маалыматтарды иштетүүгө макулдук керек',
    otherDoctor: '‹ Башка дарыгер',
    patientName: 'Бейтаптын аты',
    phone: 'Телефон',
    consent: 'Клиника кабыл алууга жазуу жана ал тууралуу эскертүү үчүн жеке маалыматтарымды (аты жана телефону) иштетүүсүнө макулмун',
    book: 'Жазылуу',
    findUs: 'Бизди кантип табасыз',
    open2gis: '2ГИСте ачуу',
    cantBook: 'Жазыла албай жатасызбы? ',
    support: '🛟 Техникалык колдоо',
    serviceBot: 'Бул клиниканын кызматтык жазылуу боту.',
    bookBy: 'Кабыл алууга жазылуу: {phone}',
    doctorPhoto: 'Дарыгердин сүрөтү',
    noConnection: 'Сервер менен байланыш жок',
    today: 'Бүгүн',
    tomorrow: 'Эртең',
    whenAt: '{when}, саат {time}',
  },
};

const KY_MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];
// С воскресенья — как getUTCDay()
const KY_WEEKDAYS = ['жекшемби', 'дүйшөмбү', 'шейшемби', 'шаршемби', 'бейшемби', 'жума', 'ишемби'];
const KY_WEEKDAYS_SHORT = ['Жк', 'Дш', 'Шш', 'Шр', 'Бш', 'Жм', 'Иш'];

let lang = 'ru';

/** «ky…» — кыргызский, всё остальное — русский. */
export function normalizeLang(code) {
  return typeof code === 'string' && code.toLowerCase().startsWith('ky') ? 'ky' : 'ru';
}

export function setLang(code) {
  lang = normalizeLang(code);
  document.documentElement.lang = lang;
}

export function currentLang() {
  return lang;
}

/** Текст по ключу; {name} — подстановки. Нет перевода — русский (лучше, чем пустая строка). */
export function t(key, vars = {}) {
  const text = TEXTS[lang][key] ?? TEXTS.ru[key] ?? key;
  return text.replace(/\{(\w+)\}/g, (_, name) => String(vars[name] ?? ''));
}

/** «сегодня» / «в среду, 3 июня» — и «бүгүн» / «3-июнь, шаршемби». */
export function whenText(startAt, now = new Date()) {
  if (lang === 'ru') return ruWhen(startAt, now);
  const day = dateOf(startAt);
  const today = todayIn(now);
  if (day === today) return 'бүгүн';
  if (day === addDays(today, 1)) return 'эртең';
  const date = new Date(`${day}T00:00:00Z`);
  return `${date.getUTCDate()}-${KY_MONTHS[date.getUTCMonth()]}, ${KY_WEEKDAYS[date.getUTCDay()]}`;
}

/** «Завтра, в 10:00» — дата и время записи целиком. */
export function whenAt(startAt, now = new Date()) {
  const text = t('whenAt', { when: whenText(startAt, now), time: timeOf(startAt) });
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Подписи ленты дней: «Сегодня», «Завтра», «Ср 3» — «Бүгүн», «Эртең», «Шр 3». */
export function bookingDays(today, n) {
  return Array.from({ length: n }, (_, i) => {
    const date = addDays(today, i);
    if (i === 0) return { date, label: t('today') };
    if (i === 1) return { date, label: t('tomorrow') };
    const d = new Date(`${date}T00:00:00Z`);
    const label = lang === 'ky' ? `${KY_WEEKDAYS_SHORT[d.getUTCDay()]} ${d.getUTCDate()}`
      : new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', weekday: 'short', day: 'numeric' }).format(d).replace(',', '');
    return { date, label: label.charAt(0).toUpperCase() + label.slice(1) };
  });
}

/** Перерыв над сеткой. Название («Джума-намаз») — как его ввёл врач. */
export function breakNote(b) {
  if (lang === 'ru') return ruBreak(b);
  if (!b.start) return `${b.emoji} ${b.title} — бул күнү кабыл алуу жок`;
  return `${b.emoji} ${timeOf(b.start)}–${timeOf(b.end)} — ${b.title}, дарыгер кабыл албайт`;
}

/** «от 1 500 сом» / «1 500 сомдон». */
export function priceText(som) {
  if (lang === 'ru') return ruPrice(som);
  if (som === null || som === undefined) return null;
  return `${new Intl.NumberFormat('ru-RU').format(som)} сомдон`;
}

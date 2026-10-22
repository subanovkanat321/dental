// node --test src/test/js — без npm и зависимостей: node:test и node:assert встроены в Node 18+.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  rowState, todayIn, addDays, toInstant, timeOf, dayTitle, canMarkVisit, formatPhone, looksLikePhone,
  dayItems, windowDuration, fitsWindow, freeSlotText, whenText, reminderText, whatsappUrl, isReady, readyCount, nextToRemind, canRemind,
} from '../../main/resources/static/js/state.js';

const NOW = new Date('2099-06-01T06:00:00Z'); // 12:00 по Бишкеку
const FUTURE = { startAt: '2099-06-01T08:00:00Z', endAt: '2099-06-01T08:30:00Z' };
const PAST = { startAt: '2099-06-01T03:00:00Z', endAt: '2099-06-01T03:30:00Z' };

// DD4 + ER2: девять строк таблицы, порядок приоритетов
const rows = [
  ['CANCELLED побеждает всё', { ...PAST, status: 'CANCELLED', reply: 'CONFIRMED' }, 'Отменён'],
  ['DONE', { ...PAST, status: 'DONE', reply: 'NONE' }, 'Пришёл'],
  ['NO_SHOW', { ...PAST, status: 'NO_SHOW', reply: 'CONFIRMED' }, 'Не пришёл'],
  ['прошедший без отметки — раньше ответа', { ...PAST, status: 'SCHEDULED', reply: 'CONFIRMED' }, 'Отметьте: пришёл?'],
  ['CONFIRMED', { ...FUTURE, status: 'SCHEDULED', reply: 'CONFIRMED', reminderOpenedAt: '2099-05-31T12:00:00Z' }, 'Придёт'],
  ['RESCHEDULE', { ...FUTURE, status: 'SCHEDULED', reply: 'RESCHEDULE' }, 'Перенос'],
  ['NO_ANSWER', { ...FUTURE, status: 'SCHEDULED', reply: 'NO_ANSWER' }, 'Нет ответа'],
  ['напоминание открыто', { ...FUTURE, status: 'SCHEDULED', reply: 'NONE', reminderOpenedAt: '2099-05-31T12:00:00Z' }, 'Ждём ответа'],
  ['ничего', { ...FUTURE, status: 'SCHEDULED', reply: 'NONE', reminderOpenedAt: null }, 'Не напомнили'],
];
for (const [name, appointment, label] of rows) {
  test(`rowState: ${name}`, () => assert.equal(rowState(appointment, NOW).label, label));
}

test('rowState: у каждого состояния есть значок и подпись, не только цвет', () => {
  for (const [, appointment] of rows) {
    const s = rowState(appointment, NOW);
    assert.ok(s.icon && s.label && s.tone);
  }
});

test('сегодня по Бишкеку, а не по UTC', () => {
  assert.equal(todayIn(new Date('2099-05-31T18:30:00Z')), '2099-06-01'); // 00:30 по Бишкеку
  assert.equal(todayIn(new Date('2099-05-31T17:59:00Z')), '2099-05-31');
});

test('дата и время по Бишкеку → UTC для API и обратно', () => {
  assert.equal(toInstant('2099-06-01', '10:00'), '2099-06-01T04:00:00.000Z');
  assert.equal(toInstant('2099-06-01', '00:15'), '2099-05-31T18:15:00.000Z');
  assert.equal(timeOf('2099-05-31T18:15:00Z'), '00:15');
});

test('addDays через конец месяца', () => {
  assert.equal(addDays('2099-05-31', 1), '2099-06-01');
  assert.equal(addDays('2099-03-01', -1), '2099-02-28');
});

test('заголовок дня', () => {
  assert.equal(dayTitle('2099-06-01', NOW), 'Пн, 1 июн · Сегодня');
  assert.equal(dayTitle('2099-06-02', NOW), 'Вт, 2 июн · Завтра');
  assert.equal(dayTitle('2099-06-10', NOW), 'Ср, 10 июн');
});

test('«Пришёл» доступно сегодня и раньше, но не завтра', () => {
  const scheduled = { status: 'SCHEDULED' };
  assert.ok(canMarkVisit({ ...scheduled, startAt: '2099-06-01T15:00:00Z' }, NOW)); // 21:00 сегодня
  assert.ok(!canMarkVisit({ ...scheduled, startAt: '2099-06-01T18:00:00Z' }, NOW)); // 00:00 завтра
  assert.ok(!canMarkVisit({ status: 'CANCELLED', startAt: PAST.startAt }, NOW));
});

test('телефон для показа и распознавание номера в поиске', () => {
  assert.equal(formatPhone('996555123456'), '+996 555 123 456');
  assert.equal(formatPhone('77012345678'), '+77012345678');
  assert.ok(looksLikePhone('0555 12'));
  assert.ok(!looksLikePhone('Айгуль'));
  assert.ok(!looksLikePhone('12'));
});

test('«День»: приёмы и окна «свободно» по времени', () => {
  const items = dayItems(
    [{ id: 1, startAt: '2099-06-01T05:00:00Z', endAt: '2099-06-01T05:30:00Z' }],
    [{ start: '2099-06-01T03:00:00Z', end: '2099-06-01T05:00:00Z' }, { start: '2099-06-01T05:30:00Z', end: '2099-06-01T12:00:00Z' }],
  );
  assert.deepEqual(items.map((i) => (i.free ? `free ${timeOf(i.startAt)}` : `#${i.id}`)), ['free 09:00', '#1', 'free 11:30']);
});

test('длительность из окна: не больше 30 и не больше самого окна', () => {
  assert.equal(windowDuration({ startAt: '2099-06-01T03:00:00Z', endAt: '2099-06-01T05:00:00Z' }), 30);
  assert.equal(windowDuration({ startAt: '2099-06-01T03:00:00Z', endAt: '2099-06-01T03:15:00Z' }), 15);
});

// ---------- Напоминание ----------

test('«когда»: сегодня, завтра, дальше — с днём недели в нужном падеже', () => {
  const now = new Date('2099-06-01T06:00:00Z'); // пн 12:00 Бишкек
  assert.equal(whenText('2099-06-01T10:00:00Z', now), 'сегодня');
  assert.equal(whenText('2099-06-02T03:00:00Z', now), 'завтра');
  assert.equal(whenText('2099-06-03T03:00:00Z', now), 'в среду, 3 июня');
  assert.equal(whenText('2099-06-09T03:00:00Z', now), 'во вторник, 9 июня');
  assert.equal(whenText('2099-06-05T03:00:00Z', now), 'в пятницу, 5 июня');
});

test('«когда» через полночь по Бишкеку, а не по UTC', () => {
  const start = '2099-06-01T18:15:00Z'; // вт 00:15 Бишкек
  assert.equal(whenText(start, new Date('2099-06-01T17:59:00Z')), 'завтра'); // пн 23:59
  assert.equal(whenText(start, new Date('2099-06-01T18:01:00Z')), 'сегодня'); // вт 00:01
});

test('текст напоминания и ссылка wa.me', () => {
  const a = { startAt: '2099-06-02T03:30:00Z', patient: { fullName: 'Айгуль Т.', phone: '996555123456' } };
  const now = new Date('2099-06-01T12:00:00Z');
  const text = reminderText(a, 'Клиника «Улыбка»', now);
  assert.equal(text, 'Здравствуйте, Айгуль Т.! Напоминаем: завтра в 09:30 приём у стоматолога. '
    + 'Ответьте 1 — приду, 2 — перенести. Клиника «Улыбка»');
  assert.ok(!reminderText(a, '', now).endsWith(' '));
  const url = whatsappUrl('996555123456', text);
  assert.ok(url.startsWith('https://wa.me/996555123456?text='));
  assert.equal(decodeURIComponent(url.split('?text=')[1]), text);
});

test('«готово», счётчик и «следующий» по одному правилу', () => {
  const now = new Date('2099-06-01T12:00:00Z');
  const base = { status: 'SCHEDULED', reply: 'NONE', reminderOpenedAt: null };
  const list = [
    { ...base, id: 3, startAt: '2099-06-02T05:00:00Z', endAt: '2099-06-02T05:30:00Z' },
    { ...base, id: 1, startAt: '2099-06-02T03:00:00Z', endAt: '2099-06-02T03:30:00Z', reminderOpenedAt: '2099-06-01T11:00:00Z' },
    { ...base, id: 2, startAt: '2099-06-02T04:00:00Z', endAt: '2099-06-02T04:30:00Z', reply: 'CONFIRMED' },
    { ...base, id: 4, startAt: '2099-06-02T04:30:00Z', endAt: '2099-06-02T05:00:00Z' },
    { ...base, id: 5, startAt: '2099-06-02T02:00:00Z', endAt: '2099-06-02T02:30:00Z', status: 'CANCELLED' },
  ];
  assert.deepEqual(readyCount(list), { ready: 2, total: 4 });
  assert.equal(nextToRemind(list, new Set(), now).id, 4);
  assert.equal(nextToRemind(list, new Set([4]), now).id, 3);
  assert.equal(nextToRemind(list, new Set([3, 4]), now), null);
  assert.ok(isReady(list[2]) && !isReady(list[0]));
  assert.ok(!canRemind({ ...base, endAt: '2099-06-01T11:00:00Z' }, now));
});

test('лист ожидания: кто помещается в окно и текст «освободилось время»', () => {
  const window = { startAt: '2099-06-02T03:00:00Z', endAt: '2099-06-02T03:45:00Z' };
  assert.ok(fitsWindow({ durationMinutes: 45 }, window));
  assert.ok(!fitsWindow({ durationMinutes: 60 }, window));
  // Уже записан: окно до его приёма — кандидат, после — нет
  assert.ok(fitsWindow({ durationMinutes: 30, upcoming: { startAt: '2099-06-02T09:00:00Z' } }, window));
  assert.ok(!fitsWindow({ durationMinutes: 30, upcoming: { startAt: '2099-06-02T02:00:00Z' } }, window));
  const text = freeSlotText('Бакыт М.', '2099-06-02T03:00:00Z', '', new Date('2099-06-01T12:00:00Z'));
  assert.equal(text, 'Здравствуйте, Бакыт М.! Освободилось время у стоматолога: завтра в 09:00. '
    + 'Хотите прийти? Ответьте «да» — запишем.');
});

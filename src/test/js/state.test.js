// node --test src/test/js — без npm и зависимостей: node:test и node:assert встроены в Node 18+.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  rowState, todayIn, addDays, toInstant, timeOf, dayTitle, canMarkVisit, formatPhone, looksLikePhone,
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

// Звонок из Mini App. Внутри Telegram ссылка tel: не открывает звонилку, поэтому Mini App
// открывает эту страницу через openLink — во встроенном браузере tel: работает.
// Номер — во фрагменте (#996…): фрагмент не уходит на сервер и в логи nginx.
const digits = location.hash.slice(1);
const link = document.getElementById('call');
if (/^\d{6,15}$/.test(digits)) {
  const kg = /^996(\d{3})(\d{3})(\d{3})$/.exec(digits);
  link.href = `tel:+${digits}`;
  link.textContent = `Позвонить ${kg ? `+996 ${kg[1]} ${kg[2]} ${kg[3]}` : `+${digits}`}`;
  location.href = link.href;
} else {
  link.textContent = 'Номер не найден';
  link.removeAttribute('href');
}

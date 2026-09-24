// Загрузчик пункта «Уведомления» в шапке Jira.
//
// Этот файл — единственное, что грузится на каждой странице Jira: сам диалог
// (React + ReactDOM + модалка, ~145 КБ) подтягивается по первому клику через
// WRM.require. Раньше в контексте atl.general лежал весь бандл, то есть React
// разбирался при открытии любой задачи, доски и дашборда.

const BUNDLE = 'wrc!ru.my.issue-notifier:user-settings-resources';

// Патчим href сразу при загрузке скрипта, до любого клика пользователя.
// Если оставить реальный URL (/jira/), Jira вызывает window.location.href до того,
// как наш capture-handler успевает сработать, и страница перезагружается.
// После замены на '#' навигация Jira — это hash-change текущей страницы (без reload).
function patchNavLink() {
  const el = document.getElementById('issue-notifier-nav-link');
  if (el) { el.setAttribute('href', '#'); return true; }
  return false;
}

if (!patchNavLink()) {
  // В Jira 9.x nav-бар рендерится React-ом асинхронно — ждём появления элемента.
  // Наблюдение за всем документом дорогое, а ссылки может не быть вовсе
  // (нет прав, другая тема) — поэтому снимаем наблюдатель по таймауту.
  const obs = new MutationObserver(() => { if (patchNavLink()) obs.disconnect(); });
  obs.observe(document.documentElement, { childList: true, subtree: true });
  setTimeout(() => obs.disconnect(), 15000);
}

let loading = false;

// Диалог регистрирует window.ISSUE_NOTIFIER_OPEN при загрузке бандла: клик,
// который и запустил загрузку, открывает его уже после неё.
function openSettings() {
  if (window.ISSUE_NOTIFIER_OPEN) { window.ISSUE_NOTIFIER_OPEN(); return; }
  if (loading) return;
  loading = true;
  window.WRM.require(BUNDLE, () => {
    loading = false;
    if (window.ISSUE_NOTIFIER_OPEN) window.ISSUE_NOTIFIER_OPEN();
  });
}

document.addEventListener('click', e => {
  const link = e.target.closest && e.target.closest('#issue-notifier-nav-link');
  if (!link) return;
  e.preventDefault(); // предотвращаем даже hash-change в адресной строке
  openSettings();
}, true);

import React, { useState } from 'react';
import ReactDOM from 'react-dom';
import UserSettingsModal from './UserSettingsModal';

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
  // В Jira 9.x nav-бар рендерится React-ом асинхронно — ждём появления элемента
  const obs = new MutationObserver(() => { if (patchNavLink()) obs.disconnect(); });
  obs.observe(document.documentElement, { childList: true, subtree: true });
}

function App() {
  const [open, setOpen] = useState(false);

  React.useEffect(() => {
    function handleClick(e) {
      const link = e.target.closest && e.target.closest('#issue-notifier-nav-link');
      if (link) {
        e.preventDefault(); // предотвращаем даже hash-change в адресной строке
        setOpen(true);
      }
    }
    document.addEventListener('click', handleClick, true);
    return () => document.removeEventListener('click', handleClick, true);
  }, []);

  if (!open) return null;
  return <UserSettingsModal onClose={() => setOpen(false)} />;
}

// Идемпотентное монтирование — безопасно при двойном вызове скрипта
let root = document.getElementById('issue-notifier-user-root');
if (!root) {
  root = document.createElement('div');
  root.id = 'issue-notifier-user-root';
  document.body.appendChild(root);
}
ReactDOM.render(<App />, root);

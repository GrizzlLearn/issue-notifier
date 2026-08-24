import React, { useState } from 'react';
import ReactDOM from 'react-dom';
import UserSettingsModal from './UserSettingsModal';

function App() {
  const [open, setOpen] = useState(false);

  React.useEffect(() => {
    function handleClick(e) {
      const link = e.target.closest && e.target.closest('#issue-notifier-nav-link');
      if (link) {
        e.preventDefault();
        setOpen(true);
      }
    }
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
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

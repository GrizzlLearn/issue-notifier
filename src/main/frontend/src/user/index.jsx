import React from 'react';
import ReactDOM from 'react-dom';
import UserSettingsModal from './UserSettingsModal';

// Бандл подтягивается по клику из загрузчика (см. nav.js), поэтому патч ссылки
// и обработчик клика живут там, а здесь остаётся только сам диалог.

// Идемпотентное монтирование — безопасно при двойном вызове скрипта
let root = document.getElementById('issue-notifier-user-root');
if (!root) {
  root = document.createElement('div');
  root.id = 'issue-notifier-user-root';
  document.body.appendChild(root);
}

function close() {
  ReactDOM.unmountComponentAtNode(root);
}

// Обёртки-состояния нет намеренно: загрузчик вызывает эту функцию сразу после
// загрузки бандла, а useEffect к тому моменту ещё не отработал бы.
window.ISSUE_NOTIFIER_OPEN = () => {
  ReactDOM.render(<UserSettingsModal onClose={close} />, root);
};

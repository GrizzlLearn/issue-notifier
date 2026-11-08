import React from 'react';

// Вкладка «Диагностика»: логирование по областям.
// Порядок и подписи держим здесь, а не тянем с сервера: список меняется вместе
// с кодом плагина, а не настройками, и лишний запрос ради пяти строк не нужен.
const LOG_AREAS = [
  ['logging.verbose', 'Все действия плагина',
    'Поднимает подробность всему плагину. Включённая, перекрывает галочки ниже.'],
  ['logging.channels', 'Каналы доставки',
    'Обращения к Mattermost, Telegram и почте: адресат, HTTP-статус и время ответа.'],
  ['logging.recipients', 'Отбор получателей и делегирование',
    'Кто попал в рассылку и по какой причине отсеян: права, личные настройки, проект, делегирование.'],
  ['logging.rest', 'Запросы из интерфейса',
    'Кто открыл настройки, что сохранил, какие справочники запрашивал.'],
  ['logging.scheduler', 'Планировщик',
    'Опрос Telegram: такты, сдвиг offset, ответы на /start.'],
];

export default function DiagnosticsTab({ values, setValue }) {
  const all = values['logging.verbose'] === 'true';
  return (
    <fieldset className="in-section">
      <legend>Логирование</legend>

      {LOG_AREAS.map(([key, label, hint]) => {
        const checked = values[key] === 'true';
        const overridden = all && key !== 'logging.verbose';
        return (
          <div key={key} className="field-group" style={{ marginBottom: 12 }}>
            <label className="in-check">
              <input
                type="checkbox"
                checked={checked}
                onChange={e => setValue(key, e.target.checked ? 'true' : 'false')}
              />
              <span>{label}</span>
            </label>
            <div className="description">
              {hint}
              {overridden && !checked ? ' Сейчас всё равно включено галочкой «Все действия плагина».' : ''}
            </div>
          </div>
        );
      })}

      <div className="description">
        Файл лога: <code>&lt;jira-home&gt;/log/issue-notifier.log</code>.
        Предупреждения и ошибки дополнительно дублируются в <code>atlassian-jira.log</code>.
        Тексты задач и комментариев в лог не попадают, токены маскируются.
        Применяется сразу после сохранения, перезапуск Jira не нужен.
      </div>
    </fieldset>
  );
}


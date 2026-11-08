import React, { useState, useEffect, useRef } from 'react';
import { testChannel } from '../api';
import { hintStyle } from './adminData';

export const SECTIONS = [
  {
    title: 'Email',
    channel: 'EMAIL',
    fields: [
      { key: 'email.enabled', label: 'Включить канал', type: 'checkbox' },
    ],
  },
  {
    title: 'Mattermost',
    channel: 'MATTERMOST',
    fields: [
      { key: 'mattermost.enabled', label: 'Включить канал', type: 'checkbox' },
      { key: 'mattermost.domain', label: 'URL сервера', type: 'text', placeholder: 'https://mattermost.example.com' },
      { key: 'mattermost.botId', label: 'ID бота', type: 'text', placeholder: 'abc123xyz' },
      { key: 'mattermost.token', label: 'Bearer-токен', type: 'password', isSetKey: 'mattermost.token.isSet', placeholder: '••••••••' },
    ],
  },
  {
    title: 'Telegram',
    channel: 'TELEGRAM',
    fields: [
      { key: 'telegram.enabled', label: 'Включить канал', type: 'checkbox' },
      { key: 'telegram.botUsername', label: 'Username бота', type: 'text', placeholder: 'MyJiraBot' },
      { key: 'telegram.botToken', label: 'Токен бота', type: 'password', isSetKey: 'telegram.botToken.isSet', placeholder: '123456:ABC-DEF…' },
    ],
  },
];


// Поле секрета: значение с сервера не приходит, поэтому пустое поле означает
// «оставить прежний токен». Отдельного режима редактирования нет — админ просто
// вводит новое значение поверх пустого поля.
function SecretField({ field, values, setValue }) {
  const isSet = values[field.isSetKey] === 'true';
  const typed = values[field.key] || '';

  return (
    <>
      <label className="label" htmlFor={field.key}>
        {field.label}
        <span style={{ ...hintStyle, marginLeft: 8, fontWeight: 'normal', color: isSet ? '#14892c' : '#707070' }}>
          {isSet ? '● Установлен' : '○ Не задан'}
        </span>
      </label>
      <input
        id={field.key}
        className="text"
        type="password"
        value={typed}
        placeholder={isSet ? 'Оставьте пустым, чтобы не менять' : field.placeholder}
        onChange={e => setValue(field.key, e.target.value)}
        style={{ width: '100%' }}
        autoComplete="new-password"
      />
      {isSet && typed && (
        <div style={hintStyle}>Новое значение заменит сохранённый токен после сохранения.</div>
      )}
    </>
  );
}


// Проверочная отправка канала. Отправляем значения секции прямо из формы —
// админ проверяет введённый токен до сохранения; результат приходит от бэкенда
// текстом (например, «Пользователь не найден в Mattermost»).
// Кому уходит проверка. Telegram адресует по chat_id из настроек пользователя,
// поэтому произвольной почты у него нет — вариант скрыт.
const TEST_RECIPIENTS = [
  ['me', 'Мне'],
  ['user', 'Пользователю Jira'],
  ['email', 'На адрес почты'],
];

function ChannelTestButton({ section, values, onTested }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [recipientType, setRecipientType] = useState('me');
  const [recipient, setRecipient] = useState('');
  // вкладка «Каналы» размонтируется при переключении вкладок, а запрос идёт до 10 секунд:
  // без этой проверки ответ пришёл бы в размонтированный компонент
  const alive = useRef(true);
  useEffect(() => () => { alive.current = false; }, []);

  async function handleClick() {
    setBusy(true); setError(null); setResult(null);
    try {
      const settings = Object.fromEntries(
        section.fields.filter(f => !f.isSetKey || values[f.key]).map(f => [f.key, values[f.key] || ''])
      );
      const message = await testChannel(section.channel, settings, recipientType,
        recipientType === 'me' ? '' : recipient.trim());
      if (alive.current) {
        setResult(message);
        onTested(section.channel);
      }
    } catch (e) {
      if (alive.current) setError(e.message);
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  const variants = section.channel === 'TELEGRAM'
    ? TEST_RECIPIENTS.filter(([value]) => value !== 'email')
    : TEST_RECIPIENTS;
  const needsTarget = recipientType !== 'me';

  return (
    <div className="field-group">
      <label className="label" htmlFor={`in-test-to-${section.channel}`}>Кому отправить проверку</label>
      <select
        id={`in-test-to-${section.channel}`}
        className="select"
        value={recipientType}
        onChange={e => { setRecipientType(e.target.value); setResult(null); setError(null); }}
      >
        {variants.map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
      </select>

      {needsTarget && (
        <input
          className="text"
          type={recipientType === 'email' ? 'email' : 'text'}
          value={recipient}
          onChange={e => setRecipient(e.target.value)}
          placeholder={recipientType === 'email' ? 'qa@example.com' : 'логин или ключ пользователя Jira'}
          style={{ width: '100%', maxWidth: 320, marginTop: 6 }}
          aria-label={recipientType === 'email' ? 'Адрес почты' : 'Пользователь Jira'}
        />
      )}

      <div style={{ marginTop: 8 }}>
        <button type="button" className="aui-button" onClick={handleClick}
                disabled={busy || (needsTarget && !recipient.trim())}>
          {busy ? 'Отправка…' : 'Проверить'}
        </button>
        <span style={{ marginLeft: 8 }}>
          {error && <span className="in-status-text is-error">{error}</span>}
          {result && <span className="in-status-text is-success">{result}</span>}
        </span>
      </div>

      <div style={hintStyle}>
        {section.channel === 'MATTERMOST'
          ? 'В Mattermost получатель ищется по адресу почты — своей или указанной. '
          : ''}
        Проверяются значения из формы, сохранять их для этого не нужно; пустое поле
        токена означает «взять сохранённый». Без успешной проверки включённый канал
        сохранить нельзя.
      </div>
    </div>
  );
}

export default function ChannelsTab({ values, setValue, onTested }) {
  return (
    <>
      {SECTIONS.map(section => (
        <fieldset key={section.title} className="in-section">
          <legend>{section.title}</legend>

          {section.fields.map(field => (
            <div key={field.key} className="field-group" style={{ marginBottom: 12 }}>
              {field.type === 'checkbox' ? (
                <label className="in-check">
                  <input
                    type="checkbox"
                    checked={values[field.key] === 'true'}
                    onChange={e => setValue(field.key, e.target.checked ? 'true' : 'false')}
                  />
                  <span>{field.label}</span>
                </label>
              ) : field.isSetKey ? (
                <SecretField field={field} values={values} setValue={setValue} />
              ) : (
                <>
                  <label className="label" htmlFor={field.key}>{field.label}</label>
                  <input
                    id={field.key}
                    className="text"
                    type={field.type}
                    value={values[field.key] || ''}
                    onChange={e => setValue(field.key, e.target.value)}
                    placeholder={field.placeholder}
                    style={{ width: '100%' }}
                  />
                </>
              )}
            </div>
          ))}

          {section.channel && (
            <ChannelTestButton
              section={section}
              values={values}
              onTested={onTested}
            />
          )}
        </fieldset>
      ))}
    </>
  );
}

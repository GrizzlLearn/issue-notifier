import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings } from '../api';

const SECTIONS = [
  {
    title: 'Email',
    fields: [
      { key: 'email.enabled', label: 'Включить канал', type: 'checkbox' },
    ],
  },
  {
    title: 'Mattermost',
    fields: [
      { key: 'mattermost.enabled', label: 'Включить канал', type: 'checkbox' },
      { key: 'mattermost.domain', label: 'URL сервера', type: 'text', placeholder: 'https://mattermost.example.com' },
      { key: 'mattermost.botId', label: 'ID бота', type: 'text', placeholder: 'abc123xyz' },
      { key: 'mattermost.token', label: 'Bearer-токен', type: 'password', isSetKey: 'mattermost.token.isSet', placeholder: '••••••••' },
    ],
  },
  {
    title: 'Telegram',
    fields: [
      { key: 'telegram.enabled', label: 'Включить канал', type: 'checkbox' },
      { key: 'telegram.botUsername', label: 'Username бота', type: 'text', placeholder: 'MyJiraBot' },
      { key: 'telegram.botToken', label: 'Токен бота', type: 'password', isSetKey: 'telegram.botToken.isSet', placeholder: '123456:ABC-DEF…' },
    ],
  },
];

// Перед отправкой убираем read-only .isSet ключи и пустые секреты.
function buildPayload(values) {
  return Object.fromEntries(
    Object.entries(values).filter(([key, val]) => {
      if (key.endsWith('.isSet')) return false;
      return !((key + '.isSet') in values && !val);

    })
  );
}

// Поле ввода секрета: если токен установлен — показывает ••••••••,
// при фокусе очищается для ввода нового значения, Esc или уход без ввода — откат.
function SecretField({ field, values, setValue }) {
  const isSet = values[field.isSetKey] === 'true';
  const [editing, setEditing] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => {
    if (editing) inputRef.current?.focus();
  }, [editing]);

  function startEdit() {
    setValue(field.key, '');
    setEditing(true);
  }

  function cancelEdit() {
    setValue(field.key, '');
    setEditing(false);
  }

  const showPlaceholder = isSet && !editing;

  return (
    <>
      <label className="label" htmlFor={field.key}>
        {field.label}
        {isSet && !editing && (
          <span style={{ marginLeft: 8, fontSize: 11, color: '#14892c', fontWeight: 'normal' }}>● Установлен</span>
        )}
        {isSet && editing && (
          <span style={{ marginLeft: 8, fontSize: 11, color: '#707070', fontWeight: 'normal' }}>Esc — отмена</span>
        )}
        {!isSet && (
          <span style={{ marginLeft: 8, fontSize: 11, color: '#707070', fontWeight: 'normal' }}>○ Не задан</span>
        )}
      </label>
      <input
        ref={inputRef}
        id={field.key}
        className="text"
        type="password"
        value={showPlaceholder ? '••••••••' : (values[field.key] || '')}
        readOnly={showPlaceholder}
        placeholder={!isSet ? field.placeholder : ''}
        onChange={showPlaceholder ? undefined : e => setValue(field.key, e.target.value)}
        onFocus={showPlaceholder ? startEdit : undefined}
        onKeyDown={editing ? e => { if (e.key === 'Escape') cancelEdit(); } : undefined}
        onBlur={editing ? () => { if (!values[field.key]) cancelEdit(); } : undefined}
        style={{ width: '100%', cursor: showPlaceholder ? 'pointer' : 'text' }}
        autoComplete="new-password"
      />
    </>
  );
}

export default function AdminApp() {
  const [values, setValues] = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const isSavingRef = useRef(false);
  const successTimerRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    getAdminSettings(controller.signal)
      .then(data => { setValues(data); setLoading(false); })
      .catch(e => {
        if (e.name !== 'AbortError') { setError(e.message); setLoading(false); }
      });
    return () => {
      controller.abort();
      clearTimeout(successTimerRef.current);
    };
  }, []);

  async function handleSave() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null); setSuccess(false);
    try {
      await saveAdminSettings(buildPayload(values));
      setSuccess(true);
      clearTimeout(successTimerRef.current);
      successTimerRef.current = setTimeout(() => setSuccess(false), 2500);
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
      isSavingRef.current = false;
    }
  }

  function setValue(key, val) {
    setValues(prev => ({ ...prev, [key]: val }));
  }

  if (loading) return <div className="in-loading">Загрузка…</div>;

  return (
    <div className="in-admin-wrap">
      <h2>Настройки Issue Notifier</h2>

      {error && <div className="aui-message aui-message-error" style={{ marginBottom: 16 }}>{error}</div>}
      {success && <div className="aui-message aui-message-success" style={{ marginBottom: 16 }}>Сохранено</div>}

      {SECTIONS.map(section => (
        <fieldset key={section.title} className="in-section">
          <legend>{section.title}</legend>

          {section.fields.map(field => (
            <div key={field.key} className="field-group" style={{ marginBottom: 12 }}>
              {field.type === 'checkbox' ? (
                <label>
                  <input
                    type="checkbox"
                    checked={values[field.key] === 'true'}
                    onChange={e => setValue(field.key, e.target.checked ? 'true' : 'false')}
                    style={{ marginRight: 6 }}
                  />
                  {field.label}
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
        </fieldset>
      ))}

      <div style={{ marginTop: 8 }}>
        <button type="button" className="aui-button aui-button-primary" onClick={handleSave} disabled={saving}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

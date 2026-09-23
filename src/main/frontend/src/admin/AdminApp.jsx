import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings, getSdProjects, getActions } from '../api';

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

// Вкладка SD-проектов: список Service Desk-проектов инстанса с чекбоксами.
// Отмеченные хранятся в одном admin-ключе sd.projects как CSV из project key.
function SdProjectsPanel({ values, setValue }) {
  const [projects, setProjects] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    getSdProjects(controller.signal)
      .then(setProjects)
      .catch(e => { if (e.name !== 'AbortError') setError(e.message); });
    return () => controller.abort();
  }, []);

  const selected = (values['sd.projects'] || '').split(',').filter(Boolean);

  function toggle(key, checked) {
    const next = checked ? [...selected, key] : selected.filter(k => k !== key);
    setValue('sd.projects', next.join(','));
  }

  if (error) return <div className="aui-message aui-message-error">{error}</div>;
  if (!projects) return <div className="in-loading">Загрузка…</div>;
  if (projects.length === 0) return <p>Service Desk-проекты не найдены.</p>;

  return (
    <fieldset className="in-section">
      <legend>Проекты с логикой портала</legend>
      {projects.map(p => (
        <div key={p.value} className="field-group" style={{ marginBottom: 8 }}>
          <label>
            <input
              type="checkbox"
              checked={selected.includes(p.value)}
              onChange={e => toggle(p.value, e.target.checked)}
              style={{ marginRight: 6 }}
            />
            {p.label}
          </label>
        </div>
      ))}
    </fieldset>
  );
}

const CHANNEL_TITLES = { MATTERMOST: 'Mattermost', TELEGRAM: 'Telegram' };

// Вкладка действий: для каждого действия — галка «уведомлять» и шаблон текста
// на каждый канал. Пустой шаблон означает, что по этому каналу ничего не уйдёт,
// поэтому включённое действие без единого шаблона показывает предупреждение.
function ActionsPanel({ values, setValue }) {
  const [actions, setActions] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    getActions(controller.signal)
      .then(setActions)
      .catch(e => { if (e.name !== 'AbortError') setError(e.message); });
    return () => controller.abort();
  }, []);

  if (error) return <div className="aui-message aui-message-error">{error}</div>;
  if (!actions) return <div className="in-loading">Загрузка…</div>;

  return (
    <>
      {actions.map(action => {
        const enabled = values[action.enabledKey] === 'true';
        const noTemplates = action.channels.every(ch => !(values[ch.templateKey] || '').trim());

        return (
          <fieldset key={action.key} className="in-section">
            <legend>{action.title}</legend>

            <div className="field-group" style={{ marginBottom: 12 }}>
              <label>
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={e => setValue(action.enabledKey, e.target.checked ? 'true' : 'false')}
                  style={{ marginRight: 6 }}
                />
                Уведомлять
              </label>
            </div>

            {enabled && noTemplates && (
              <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
                Шаблоны не заданы — уведомления по этому действию отправляться не будут.
              </div>
            )}

            {action.channels.map(ch => (
              <div key={ch.templateKey} className="field-group" style={{ marginBottom: 12 }}>
                <label className="label" htmlFor={ch.templateKey}>{CHANNEL_TITLES[ch.channel] || ch.channel}</label>
                <textarea
                  id={ch.templateKey}
                  className="textarea"
                  rows={3}
                  value={values[ch.templateKey] || ''}
                  onChange={e => setValue(ch.templateKey, e.target.value)}
                  style={{ width: '100%' }}
                />
              </div>
            ))}

            <div style={{ fontSize: 11, color: '#707070' }}>
              Доступные плейсхолдеры: {action.placeholders.map(p => '{' + p + '}').join(', ')}
            </div>
          </fieldset>
        );
      })}
    </>
  );
}

export default function AdminApp() {
  const [values, setValues] = useState({});
  const [tab, setTab] = useState('channels');
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

      <div className="aui-tabs horizontal-tabs">
        <ul className="tabs-menu">
          {[['channels', 'Каналы'], ['sd', 'SD-проекты'], ['actions', 'Действия']].map(([id, label]) => (
            <li key={id} className={'menu-item' + (tab === id ? ' active-tab' : '')}>
              <a href="#" onClick={e => { e.preventDefault(); setTab(id); }}>{label}</a>
            </li>
          ))}
        </ul>

        <div className="tabs-pane active-pane">
          {tab === 'sd' && <SdProjectsPanel values={values} setValue={setValue} />}
          {tab === 'actions' && <ActionsPanel values={values} setValue={setValue} />}
          {tab === 'channels' && SECTIONS.map(section => (
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
        </div>
      </div>

      <div style={{ marginTop: 8 }}>
        <button type="button" className="aui-button aui-button-primary" onClick={handleSave} disabled={saving}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

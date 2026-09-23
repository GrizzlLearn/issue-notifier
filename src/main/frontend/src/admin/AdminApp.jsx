import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings } from '../api';

// Справочники страницы (проекты, статусы, каталог действий) сервлет кладёт прямо
// в HTML — см. AdminPageData. Поэтому поиск проектов идёт по массиву в памяти
// браузера, без запросов на каждое нажатие клавиши.
const PAGE_DATA = window.ISSUE_NOTIFIER_DATA || { projects: [], statuses: [], actions: [] };
const MAX_SUGGESTIONS = 20;

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

const PROJECTS_KEY = 'sd.projects';
const CLOSING_KEY = 'closed.statuses';
const CLOSED_ACTION = 'closed';
const CHANNEL_TITLES = { MATTERMOST: 'Mattermost', TELEGRAM: 'Telegram' };
const hintStyle = { fontSize: 11, color: '#707070' };

const parseKeys = raw => (raw || '').split(',').filter(Boolean);

// "HELP:10001,3;SUP:10002" ↔ { HELP: ['10001','3'], SUP: ['10002'] }
function parseClosing(raw) {
  const map = {};
  (raw || '').split(';').filter(Boolean).forEach(chunk => {
    const colon = chunk.indexOf(':');
    if (colon <= 0) return;
    const ids = chunk.slice(colon + 1).split(',').filter(Boolean);
    if (ids.length) map[chunk.slice(0, colon)] = ids;
  });
  return map;
}

function formatClosing(map) {
  return Object.entries(map)
    .filter(([, ids]) => ids.length)
    .map(([key, ids]) => `${key}:${ids.join(',')}`)
    .join(';');
}

// Пикер обычных проектов: их в инстансе могут быть сотни, поэтому список целиком
// не рисуем — фильтруем по вводу и показываем выбранное чипами.
function ProjectPicker({ projects, selected, labels, onAdd, onRemove }) {
  const [query, setQuery] = useState('');

  const text = query.trim().toLowerCase();
  const suggestions = text
    ? projects
        .filter(p => !selected.includes(p.value)
          && (p.value.toLowerCase().includes(text) || p.label.toLowerCase().includes(text)))
        .slice(0, MAX_SUGGESTIONS)
    : [];

  function pick(item) {
    onAdd(item.value);
    setQuery('');
  }

  return (
    <div style={{ position: 'relative' }}>
      {selected.length > 0 && (
        <div style={{ marginBottom: 6 }}>
          {selected.map(key => (
            <span key={key} className="aui-label" style={{ marginRight: 6, display: 'inline-block' }}>
              {labels[key] || key}
              <a
                href="#"
                onClick={e => { e.preventDefault(); onRemove(key); }}
                style={{ marginLeft: 6, textDecoration: 'none' }}
                title="Убрать проект"
              >×</a>
            </span>
          ))}
        </div>
      )}

      <input
        className="text"
        type="text"
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Начните вводить ключ или название проекта"
        style={{ width: '100%' }}
      />

      {suggestions.length > 0 && (
        <ul style={{
          position: 'absolute', zIndex: 10, left: 0, right: 0, margin: 0, padding: 0,
          listStyle: 'none', background: '#fff', border: '1px solid #dfe1e6',
          borderRadius: 4, maxHeight: 220, overflowY: 'auto',
          boxShadow: '0 4px 8px rgba(9,30,66,.15)',
        }}>
          {suggestions.map(item => (
            <li key={item.value}>
              <a
                href="#"
                onClick={e => { e.preventDefault(); pick(item); }}
                style={{ display: 'block', padding: '6px 10px', textDecoration: 'none', color: '#172b4d' }}
              >
                {item.label}
                {item.serviceDesk && <span style={{ ...hintStyle, marginLeft: 6 }}>Service Desk</span>}
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// Проекты, к которым применяется логика портала: SD-проекты полным списком
// (их немного), остальные — через пикер.
function ProjectsPanel({ projects, selected, labels, setValue }) {
  const sdProjects = projects.filter(p => p.serviceDesk);
  const sdKeys = sdProjects.map(p => p.value);
  const otherSelected = selected.filter(key => !sdKeys.includes(key));

  function setSelected(next) {
    setValue(PROJECTS_KEY, next.join(','));
  }

  return (
    <fieldset className="in-section">
      <legend>Проекты с логикой портала</legend>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Service Desk</div>
        {sdProjects.length === 0 && <div style={hintStyle}>Service Desk-проекты не найдены.</div>}
        {sdProjects.map(p => (
          <div key={p.value} style={{ marginBottom: 4 }}>
            <label>
              <input
                type="checkbox"
                checked={selected.includes(p.value)}
                onChange={e => setSelected(e.target.checked
                  ? [...selected, p.value]
                  : selected.filter(k => k !== p.value))}
                style={{ marginRight: 6 }}
              />
              {p.label}
            </label>
          </div>
        ))}
      </div>

      <div>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Остальные проекты</div>
        <ProjectPicker
          projects={projects}
          selected={otherSelected}
          labels={labels}
          onAdd={key => setSelected([...selected, key])}
          onRemove={key => setSelected(selected.filter(k => k !== key))}
        />
      </div>
    </fieldset>
  );
}

// Закрывающие статусы задаются отдельно для каждого выбранного проекта:
// в разных workflow закрытие называется по-разному.
function ClosingStatusesField({ labels, selected, statuses, values, setValue }) {
  const map = parseClosing(values[CLOSING_KEY]);

  function toggleStatus(projectKey, statusId, checked) {
    const current = map[projectKey] || [];
    const next = checked ? [...current, statusId] : current.filter(id => id !== statusId);
    setValue(CLOSING_KEY, formatClosing({ ...map, [projectKey]: next }));
  }

  if (selected.length === 0) {
    return <div style={hintStyle}>Отметьте проекты выше, чтобы выбрать для них закрывающие статусы.</div>;
  }

  return (
    <div style={{ marginBottom: 12 }}>
      <div className="label">Закрывающие статусы по проектам</div>
      {selected.map(key => {
        const chosen = map[key] || [];
        return (
          <details key={key} style={{ marginBottom: 6 }}>
            <summary style={{ cursor: 'pointer' }}>
              {labels[key] || key}
              <span style={{ ...hintStyle, marginLeft: 8 }}>
                {chosen.length ? `выбрано: ${chosen.length}` : 'по категории «Готово»'}
              </span>
            </summary>
            <div style={{ maxHeight: 180, overflowY: 'auto', padding: '6px 0 6px 16px' }}>
              {statuses.map(s => (
                <div key={s.value} style={{ marginBottom: 4 }}>
                  <label>
                    <input
                      type="checkbox"
                      checked={chosen.includes(s.value)}
                      onChange={e => toggleStatus(key, s.value, e.target.checked)}
                      style={{ marginRight: 6 }}
                    />
                    {s.label}
                    {s.done && <span style={{ ...hintStyle, marginLeft: 6 }}>категория «Готово»</span>}
                  </label>
                </div>
              ))}
            </div>
          </details>
        );
      })}
      <div style={hintStyle}>
        Если для проекта не выбрано ни одного статуса, закрывающими считаются статусы категории «Готово».
      </div>
    </div>
  );
}

// Действие: галка «уведомлять» и шаблон текста на каждый канал. Пустой шаблон —
// по этому каналу ничего не уйдёт, поэтому включённое действие без шаблонов
// показывает предупреждение.
function ActionsPanel({ actions, labels, selected, statuses, values, setValue }) {
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

            {action.key === CLOSED_ACTION && (
              <ClosingStatusesField
                labels={labels}
                selected={selected}
                statuses={statuses}
                values={values}
                setValue={setValue}
              />
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

            <div style={hintStyle}>
              Доступные плейсхолдеры: {action.placeholders.map(p => '{' + p + '}').join(', ')}
            </div>
          </fieldset>
        );
      })}
    </>
  );
}

// Вкладка SD-проектов: выбор проектов и настройки действий по ним.
// Справочники уже в PAGE_DATA, загружать нечего.
const PROJECT_LABELS = Object.fromEntries(PAGE_DATA.projects.map(p => [p.value, p.label]));

function PortalTab({ values, setValue }) {
  const selected = parseKeys(values[PROJECTS_KEY]);

  return (
    <>
      <ProjectsPanel
        projects={PAGE_DATA.projects}
        selected={selected}
        labels={PROJECT_LABELS}
        setValue={setValue}
      />
      <ActionsPanel
        actions={PAGE_DATA.actions}
        labels={PROJECT_LABELS}
        selected={selected}
        statuses={PAGE_DATA.statuses}
        values={values}
        setValue={setValue}
      />
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
          {[['channels', 'Каналы'], ['sd', 'SD-проекты']].map(([id, label]) => (
            <li key={id} className={'menu-item' + (tab === id ? ' active-tab' : '')}>
              <a href="#" onClick={e => { e.preventDefault(); setTab(id); }}>{label}</a>
            </li>
          ))}
        </ul>

        <div className="tabs-pane active-pane">
          {tab === 'sd' && <PortalTab values={values} setValue={setValue} />}
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

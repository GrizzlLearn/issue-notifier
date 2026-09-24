import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings } from '../api';

// Справочники страницы (проекты, статусы, каталог действий) сервлет кладёт прямо
// в HTML — см. AdminPageData. Поэтому поиск проектов идёт по массиву в памяти
// браузера, без запросов на каждое нажатие клавиши.
const PAGE_DATA = window.ISSUE_NOTIFIER_DATA || { projects: [], statuses: [], actions: [], userFields: [] };
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

// Ключ флага канала — как в AdminSettingsServiceImpl: имя канала в нижнем регистре + ".enabled".
const isChannelOn = (values, channel) => values[channel.toLowerCase() + '.enabled'] === 'true';
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
// Список подсказок управляется с клавиатуры: ↑/↓ — перебор, Enter — выбрать, Esc — закрыть.
function ProjectPicker({ projects, selected, labels, onAdd, onRemove }) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [closed, setClosed] = useState(false);

  const text = query.trim().toLowerCase();
  const suggestions = text && !closed
    ? projects
        .filter(p => !selected.includes(p.value)
          && (p.value.toLowerCase().includes(text) || p.label.toLowerCase().includes(text)))
        .slice(0, MAX_SUGGESTIONS)
    : [];

  function changeQuery(value) {
    setQuery(value);
    setActive(0);
    setClosed(false);
  }

  function pick(item) {
    onAdd(item.value);
    setQuery('');
    setActive(0);
  }

  function handleKeyDown(e) {
    if (e.key === 'Escape') {
      setClosed(true);
      return;
    }
    if (suggestions.length === 0) {
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive(prev => Math.min(prev + 1, suggestions.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive(prev => Math.max(prev - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      pick(suggestions[active]);
    }
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
        onChange={e => changeQuery(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Начните вводить ключ или название проекта"
        style={{ width: '100%' }}
        role="combobox"
        aria-expanded={suggestions.length > 0}
        aria-autocomplete="list"
        aria-controls="in-project-suggestions"
        aria-activedescendant={suggestions.length > 0 ? `in-project-option-${active}` : undefined}
      />

      {suggestions.length > 0 && (
        <ul className="in-suggestions" id="in-project-suggestions" role="listbox">
          {suggestions.map((item, index) => (
            <li
              key={item.value}
              id={`in-project-option-${index}`}
              role="option"
              aria-selected={index === active}
              // активный пункт держим в зоне видимости при переборе стрелками
              ref={el => { if (index === active && el) el.scrollIntoView({ block: 'nearest' }); }}
            >
              <a
                href="#"
                className={'in-suggestion' + (index === active ? ' is-active' : '')}
                onMouseEnter={() => setActive(index)}
                onClick={e => { e.preventDefault(); pick(item); }}
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
// в разных workflow закрытие называется по-разному. Строка проекта — кнопка:
// раскрывается список статусов, выбранные видны чипами и без раскрытия.
function ClosingStatusesField({ labels, selected, statuses, values, setValue }) {
  const [openProject, setOpenProject] = useState(null);
  const map = parseClosing(values[CLOSING_KEY]);
  const statusNames = Object.fromEntries(statuses.map(s => [s.value, s.label]));

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

      <div className="in-status-list">
        {selected.map(key => {
          const chosen = map[key] || [];
          const open = openProject === key;

          return (
            <React.Fragment key={key}>
              <button
                type="button"
                className="in-status-row"
                aria-expanded={open}
                onClick={() => setOpenProject(open ? null : key)}
              >
                <span className="in-chevron">▶</span>
                <span className="in-status-name">{labels[key] || key}</span>
                <span className="in-status-summary">
                  {chosen.length > 0
                    ? chosen.map(id => (
                      <span key={id} className="in-chip">{statusNames[id] || id}</span>
                    ))
                    : <span className="in-status-empty">статусы не выбраны — уведомления не отправляются</span>}
                </span>
                <span className="in-status-action">{open ? 'Свернуть' : 'Выбрать статусы'}</span>
              </button>

              {open && (
                <div className="in-status-panel">
                  <button
                    type="button"
                    className="aui-button aui-button-link"
                    style={{ marginBottom: 8, padding: 0 }}
                    onClick={() => setValue(CLOSING_KEY, formatClosing({
                      ...map,
                      [key]: statuses.filter(st => st.done).map(st => st.value),
                    }))}
                  >
                    Отметить статусы категории «Готово»
                  </button>
                  <div className="in-status-grid">
                    {statuses.map(s => (
                      <label key={s.value}>
                        <input
                          type="checkbox"
                          checked={chosen.includes(s.value)}
                          onChange={e => toggleStatus(key, s.value, e.target.checked)}
                          style={{ marginRight: 6 }}
                        />
                        {s.label}
                        {s.done && <span className="in-badge-done">категория «Готово»</span>}
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </React.Fragment>
          );
        })}
      </div>

      <div style={hintStyle}>
        Закрывающим считается только выбранный здесь статус: в разных workflow закрытие
        называется по-разному. Проект без выбранных статусов уведомлений о закрытии не шлёт.
      </div>
    </div>
  );
}

// Значения совпадают с константами IssueRecipients на бэкенде.
const BUILT_IN_RECIPIENTS = [
  ['reporter', 'Автор задачи'],
  ['creator', 'Создатель задачи'],
  ['assignee', 'Исполнитель'],
  ['watchers', 'Наблюдатели'],
];

// Значение «ни одного получателя»: пустая настройка на бэкенде означает
// наблюдателей (так действие работало до появления выбора), поэтому снятые
// галки сохраняются отдельным значением — см. IssueRecipients.NONE.
const NO_RECIPIENTS = 'none';

// Выбранные получатели действия; пустая настройка — наблюдатели, как на бэкенде.
function selectedRecipients(values, recipientsKey) {
  const chosen = parseKeys(values[recipientsKey]);
  if (chosen.length === 0) {
    return ['watchers'];
  }
  return chosen.filter(v => v !== NO_RECIPIENTS);
}

// Кому уходит уведомление: встроенные поля задачи плюс кастомные user picker-поля.
function RecipientsField({ recipientsKey, values, setValue }) {
  const userFields = PAGE_DATA.userFields || [];
  const selected = selectedRecipients(values, recipientsKey);

  function toggle(value, checked) {
    const next = checked ? [...selected, value] : selected.filter(v => v !== value);
    setValue(recipientsKey, (next.length ? next : [NO_RECIPIENTS]).join(','));
  }

  return (
    <div className="field-group" style={{ marginBottom: 12 }}>
      <div className="label">Кому отправлять</div>

      {BUILT_IN_RECIPIENTS.map(([value, label]) => (
        <label key={value} style={{ marginRight: 16 }}>
          <input
            type="checkbox"
            checked={selected.includes(value)}
            onChange={e => toggle(value, e.target.checked)}
            style={{ marginRight: 6 }}
          />
          {label}
        </label>
      ))}

      <div style={{ marginTop: 8 }}>
        <div style={{ fontWeight: 600, marginBottom: 4 }}>Поля с пользователями</div>
        {userFields.length === 0 && <div style={hintStyle}>Полей типа «user picker» в инстансе нет.</div>}
        {userFields.map(field => (
          <div key={field.value}>
            <label>
              <input
                type="checkbox"
                checked={selected.includes(field.value)}
                onChange={e => toggle(field.value, e.target.checked)}
                style={{ marginRight: 6 }}
              />
              {field.label}
              <span style={{ ...hintStyle, marginLeft: 6 }}>{field.scope}</span>
            </label>
          </div>
        ))}
      </div>

      <div style={hintStyle}>
        Поле, которого нет в схеме экрана конкретной задачи, получателя не даёт —
        выбирать поля отдельно на каждый тип задачи не нужно.
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
        const liveChannels = action.channels.filter(ch => isChannelOn(values, ch.channel));
        const noTemplates = liveChannels.every(ch => !(values[ch.templateKey] || '').trim());
        const scope = action.scopeFixed ? action.defaultScope : (values[action.scopeKey] || action.defaultScope);
        const noProjects = !action.scopeFixed && scope === 'selected' && selected.length === 0;
        const noRecipients = action.recipientsKey
          && selectedRecipients(values, action.recipientsKey).length === 0;

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

            <div className="field-group" style={{ marginBottom: 12 }}>
              <div className="label">Область</div>
              {action.scopeFixed && (
                <div style={hintStyle}>
                  Работает в проектах, для которых ниже выбраны закрывающие статусы.
                </div>
              )}
              {!action.scopeFixed && [['all', 'Во всех проектах'], ['selected', 'Только в проектах со вкладки «Проекты»']].map(([value, label]) => (
                <label key={value} style={{ marginRight: 16 }}>
                  <input
                    type="radio"
                    name={action.scopeKey}
                    checked={scope === value}
                    onChange={() => setValue(action.scopeKey, value)}
                    style={{ marginRight: 6 }}
                  />
                  {label}
                </label>
              ))}
            </div>

            {enabled && noTemplates && (
              <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
                {liveChannels.length === 0
                  ? 'Все каналы отключены на вкладке «Каналы» — уведомления по этому действию отправляться не будут.'
                  : 'Шаблоны не заданы — уведомления по этому действию отправляться не будут.'}
              </div>
            )}

            {enabled && noRecipients && (
              <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
                Получатели не выбраны — уведомления по этому действию отправляться не будут.
              </div>
            )}

            {enabled && noProjects && (
              <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
                На вкладке «Проекты» не отмечено ни одного проекта — уведомления по этому действию не отправятся.
              </div>
            )}

            {action.recipientsKey && (
              <RecipientsField
                recipientsKey={action.recipientsKey}
                values={values}
                setValue={setValue}
              />
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

            {action.channels.map(ch => {
              const channelOff = !isChannelOn(values, ch.channel);

              return (
                <div key={ch.templateKey} className="field-group" style={{ marginBottom: 12 }}>
                  <label className="label" htmlFor={ch.templateKey}>
                    {CHANNEL_TITLES[ch.channel] || ch.channel}
                    {channelOff && <span style={{ ...hintStyle, marginLeft: 8, fontWeight: 'normal' }}>канал отключён</span>}
                  </label>
                  <textarea
                    id={ch.templateKey}
                    className="textarea"
                    rows={3}
                    value={values[ch.templateKey] || ''}
                    readOnly={channelOff}
                    onChange={channelOff ? undefined : e => setValue(ch.templateKey, e.target.value)}
                    style={{ width: '100%', background: channelOff ? '#f4f5f7' : undefined }}
                  />
                </div>
              );
            })}

            <div style={hintStyle}>
              Доступные плейсхолдеры: {action.placeholders.map(p => '{' + p + '}').join(', ')}
            </div>
          </fieldset>
        );
      })}
    </>
  );
}

// Справочники уже в PAGE_DATA, загружать на вкладках нечего.
const PROJECT_LABELS = Object.fromEntries(PAGE_DATA.projects.map(p => [p.value, p.label]));

// Вкладка «Проекты»: к каким проектам относятся действия с областью «только в выбранных».
function ProjectsTab({ values, setValue }) {
  return (
    <ProjectsPanel
      projects={PAGE_DATA.projects}
      selected={parseKeys(values[PROJECTS_KEY])}
      labels={PROJECT_LABELS}
      setValue={setValue}
    />
  );
}

// Вкладка «Действия»: что отправляем, где это работает и каким текстом.
function ActionsTab({ values, setValue }) {
  return (
    <ActionsPanel
      actions={PAGE_DATA.actions}
      labels={PROJECT_LABELS}
      selected={parseKeys(values[PROJECTS_KEY])}
      statuses={PAGE_DATA.statuses}
      values={values}
      setValue={setValue}
    />
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
          {[['channels', 'Каналы'], ['actions', 'Действия'], ['projects', 'Проекты']].map(([id, label]) => (
            <li key={id} className={'menu-item' + (tab === id ? ' active-tab' : '')}>
              <a href="#" onClick={e => { e.preventDefault(); setTab(id); }}>{label}</a>
            </li>
          ))}
        </ul>

        <div className="tabs-pane active-pane">
          {tab === 'actions' && <ActionsTab values={values} setValue={setValue} />}
          {tab === 'projects' && <ProjectsTab values={values} setValue={setValue} />}
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

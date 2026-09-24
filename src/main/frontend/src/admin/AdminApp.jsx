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

const PROJECTS_KEY = 'sd.projects';
const CATEGORIES_KEY = 'sd.categories';
const HIDE_COMMENT_TEXT_KEY = 'comment.hideText';
const CLOSING_KEY = 'closed.statuses';
const CLOSED_ACTION = 'closed';
const CHANNEL_TITLES = { MATTERMOST: 'Mattermost', TELEGRAM: 'Telegram' };

// Ключ флага канала — как в AdminSettingsServiceImpl: имя канала в нижнем регистре + ".enabled".
const isChannelOn = (values, channel) => values[channel.toLowerCase() + '.enabled'] === 'true';
const hintStyle = { fontSize: 11, color: '#707070' };

// Проверяем шаблоны до отправки: сервер отвечает 400 с именем ключа вроде
// action.closed.template.mattermost, а поле при этом может быть на другой вкладке.
function templateErrors(values) {
  const errors = {};
  (PAGE_DATA.actions || []).forEach(action => {
    (action.channels || []).forEach(ch => {
      [ch.templateKey, ch.templateKeyNoText].filter(Boolean).forEach(key => {
        const unknown = [...new Set([...(values[key] || '').matchAll(/\{([a-zA-Z0-9_]+)}/g)].map(m => m[1]))]
          .filter(name => !action.placeholders.includes(name));
        if (unknown.length) {
          errors[key] = 'Неизвестные плейсхолдеры: ' + unknown.map(n => '{' + n + '}').join(', ');
        }
      });
    });
  });
  return errors;
}

// Перед отправкой убираем read-only .isSet ключи и пустые секреты
// (пустое значение секрета означает «не менять», см. AdminSettingsResource).
function buildPayload(values, saved) {
  return Object.fromEntries(
    Object.entries(values).filter(([key, val]) => {
      if (key.endsWith('.isSet')) return false;
      if ((key + '.isSet') in values && !val) return false;
      // ключ, которого админ не касался, не отправляем: иначе параллельная
      // правка другого администратора затиралась бы этим снимком
      return saved[key] !== val;
    })
  );
}

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

const parseKeys = raw => (raw || '').split(',').filter(Boolean);

// Проекты области действия: отмеченные явно плюс проекты отмеченных категорий.
// Разворот только для экрана — в настройке категории остаются категориями,
// иначе новый проект в категории пришлось бы отмечать руками.
function scopedProjects(values) {
  const chosenCategories = parseKeys(values[CATEGORIES_KEY]);
  const byCategory = PAGE_DATA.projects
    .filter(p => p.category && chosenCategories.includes(p.category))
    .map(p => p.value);
  return [...new Set([...parseKeys(values[PROJECTS_KEY]), ...byCategory])];
}

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
  const listRef = useRef(null);

  // активный пункт держим в зоне видимости только при переборе стрелками —
  // в ref-колбэке это срабатывало на каждый рендер и дёргало список при вводе
  useEffect(() => {
    listRef.current?.children[active]?.scrollIntoView({ block: 'nearest' });
  }, [active]);

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
              <button
                type="button"
                className="in-chip-remove"
                onClick={() => onRemove(key)}
                aria-label={'Убрать ' + (labels[key] || key)}
              >×</button>
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
        <ul className="in-suggestions" id="in-project-suggestions" role="listbox" ref={listRef}>
          {suggestions.map((item, index) => (
            <li
              key={item.value}
              id={`in-project-option-${index}`}
              role="option"
              aria-selected={index === active}
              className={'in-suggestion' + (index === active ? ' is-active' : '')}
              onMouseEnter={() => setActive(index)}
              onClick={() => pick(item)}
            >
              {item.label}
              {item.serviceDesk && <span style={{ ...hintStyle, marginLeft: 6 }}>Service Desk</span>}
            </li>
          ))}
        </ul>
      )}

      {text && suggestions.length === 0 && !closed && (
        <div style={{ ...hintStyle, marginTop: 4 }}>
          {projects.some(p => selected.includes(p.value)
            && (p.value.toLowerCase().includes(text) || p.label.toLowerCase().includes(text)))
            ? 'Проект уже выбран.'
            : 'Ничего не найдено.'}
        </div>
      )}
    </div>
  );
}

// Проекты, к которым применяется логика портала: SD-проекты полным списком
// (их немного), остальные — через пикер.
function ProjectsPanel({ projects, labels, values, setValue }) {
  const categories = PAGE_DATA.categories || [];
  const chosenCategories = parseKeys(values[CATEGORIES_KEY]);
  const selected = parseKeys(values[PROJECTS_KEY]);

  // проект, попавший в область через категорию, отмечен, но снимается только категорией —
  // иначе галка возвращалась бы сама и это выглядело бы сбоем
  const viaCategory = new Set(projects
    .filter(p => p.category && chosenCategories.includes(p.category))
    .map(p => p.value));

  const sdProjects = projects.filter(p => p.serviceDesk);
  const sdKeys = sdProjects.map(p => p.value);
  const otherSelected = selected.filter(key => !sdKeys.includes(key));
  const otherViaCategory = [...viaCategory].filter(key => !sdKeys.includes(key) && !selected.includes(key));

  function setSelected(next) {
    setValue(PROJECTS_KEY, next.join(','));
  }

  function toggleCategory(id, checked) {
    setValue(CATEGORIES_KEY, (checked
      ? [...chosenCategories, id]
      : chosenCategories.filter(c => c !== id)).join(','));
  }

  return (
    <fieldset className="in-section">
      <legend>Проекты с логикой портала</legend>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Категории проектов</div>
        {categories.length === 0 && <div style={hintStyle}>Категорий проектов в инстансе нет.</div>}
        {categories.map(c => (
          <label key={c.value} style={{ marginRight: 16 }}>
            <input
              type="checkbox"
              checked={chosenCategories.includes(c.value)}
              onChange={e => toggleCategory(c.value, e.target.checked)}
              style={{ marginRight: 6 }}
            />
            {c.label}
          </label>
        ))}
        <div style={hintStyle}>
          Проект, добавленный в отмеченную категорию позже, попадёт в область сам —
          но закрывающие статусы для него всё равно нужно выбрать на вкладке «Действия».
        </div>
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Service Desk</div>
        {sdProjects.length === 0 && <div style={hintStyle}>Service Desk-проекты не найдены.</div>}
        {sdProjects.map(p => (
          <div key={p.value} style={{ marginBottom: 4 }}>
            <label>
              <input
                type="checkbox"
                checked={selected.includes(p.value) || viaCategory.has(p.value)}
                disabled={viaCategory.has(p.value)}
                onChange={e => setSelected(e.target.checked
                  ? [...selected, p.value]
                  : selected.filter(k => k !== p.value))}
                style={{ marginRight: 6 }}
              />
              {p.label}
              {viaCategory.has(p.value) && <span style={{ ...hintStyle, marginLeft: 6 }}>через категорию</span>}
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
        {otherViaCategory.length > 0 && (
          <div style={{ ...hintStyle, marginTop: 6 }}>
            Через категории также включены: {otherViaCategory.map(key => labels[key] || key).join(', ')}
          </div>
        )}
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
                      // дополняем: статус, отмеченный вручную, терять нельзя
                      [key]: [...new Set([...chosen, ...statuses.filter(st => st.done).map(st => st.value)])],
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
function ActionsPanel({ actions, labels, selected, statuses, values, setValue, errors }) {
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
              {!action.scopeFixed && [
                ['all', 'Во всех проектах'],
                ['selected', 'Только в проектах со вкладки «Проекты»'],
                ['service_desk', 'Только в Service Desk-проектах'],
              ].map(([value, label]) => (
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
                    aria-invalid={Boolean(errors[ch.templateKey])}
                    style={{ width: '100%', background: channelOff ? '#f4f5f7' : undefined }}
                  />
                  {errors[ch.templateKey] && (
                    <div className="in-field-error">{errors[ch.templateKey]}</div>
                  )}

                  {ch.templateKeyNoText && (
                    <>
                      <label className="label" htmlFor={ch.templateKeyNoText} style={{ marginTop: 8 }}>
                        {(CHANNEL_TITLES[ch.channel] || ch.channel) + ' — без текста комментария'}
                      </label>
                      <textarea
                        id={ch.templateKeyNoText}
                        className="textarea"
                        rows={2}
                        value={values[ch.templateKeyNoText] || ''}
                        readOnly={channelOff}
                        onChange={channelOff ? undefined : e => setValue(ch.templateKeyNoText, e.target.value)}
                        aria-invalid={Boolean(errors[ch.templateKeyNoText])}
                        style={{ width: '100%', background: channelOff ? '#f4f5f7' : undefined }}
                      />
                      {errors[ch.templateKeyNoText] && (
                        <div className="in-field-error">{errors[ch.templateKeyNoText]}</div>
                      )}
                      <div style={hintStyle}>
                        Уходит, когда текст отправлять нельзя: запрет в настройках ниже,
                        личная настройка получателя или комментарий с ограничением по группе или роли.
                        Плейсхолдер {'{comment}'} в нём остаётся пустым.
                      </div>
                    </>
                  )}
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
      labels={PROJECT_LABELS}
      values={values}
      setValue={setValue}
    />
  );
}

// Вкладка «Действия»: что отправляем, где это работает и каким текстом.
function ActionsTab({ values, setValue, errors }) {
  return (
    <>
      <fieldset className="in-section">
        <legend>Комментарии</legend>
        <label>
          <input
            type="checkbox"
            checked={values[HIDE_COMMENT_TEXT_KEY] !== 'true'}
            onChange={e => setValue(HIDE_COMMENT_TEXT_KEY, e.target.checked ? 'false' : 'true')}
            style={{ marginRight: 6 }}
          />
          Отправлять текст комментария в уведомлениях
        </label>
        <div style={hintStyle}>
          Запрет действует на все действия с текстом комментария. Каждый пользователь
          может дополнительно отключить текст у себя; включить вопреки этой настройке — нет.
        </div>
      </fieldset>

      <ActionsPanel
        actions={PAGE_DATA.actions}
        labels={PROJECT_LABELS}
        selected={scopedProjects(values)}
        statuses={PAGE_DATA.statuses}
        values={values}
        setValue={setValue}
        errors={errors}
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
  const successTimerRef = useRef(null);
  // снимок сохранённых значений: по нему считаем, что менять на сервере и
  // предупреждать ли об уходе со страницы
  const [saved, setSaved] = useState({});

  useEffect(() => {
    const controller = new AbortController();
    getAdminSettings(controller.signal)
      .then(data => { setValues(data); setSaved(data); setLoading(false); })
      .catch(e => {
        if (e.name !== 'AbortError') { setError(e.message); setLoading(false); }
      });
    return () => {
      controller.abort();
      clearTimeout(successTimerRef.current);
    };
  }, []);

  const payload = buildPayload(values, saved);
  const dirty = Object.keys(payload).length > 0;
  const errors = templateErrors(values);
  const errorKeys = Object.keys(errors);

  // Форма длинная, уход по F5 или меню Jira унёс бы её молча
  useEffect(() => {
    if (!dirty) return undefined;
    function warn(e) { e.preventDefault(); e.returnValue = ''; }
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  async function handleSave() {
    if (errorKeys.length > 0) {
      setError('Исправьте плейсхолдеры в шаблонах — они отмечены под полями.');
      return;
    }
    setSaving(true); setError(null); setSuccess(false);
    try {
      await saveAdminSettings(payload);
      // секреты сервер обратно не отдаёт: помечаем их установленными и чистим поля,
      // иначе введённый токен ушёл бы ещё раз при следующем сохранении
      const next = { ...values };
      Object.keys(values).filter(k => k.endsWith('.isSet')).forEach(isSetKey => {
        const secretKey = isSetKey.slice(0, -'.isSet'.length);
        if (values[secretKey]) { next[isSetKey] = 'true'; next[secretKey] = ''; }
      });
      setValues(next);
      setSaved(next);
      setSuccess(true);
      clearTimeout(successTimerRef.current);
      successTimerRef.current = setTimeout(() => setSuccess(false), 2500);
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  function setValue(key, val) {
    setValues(prev => ({ ...prev, [key]: val }));
  }

  if (loading) return <div className="in-loading">Загрузка…</div>;

  const tabs = [['channels', 'Каналы'], ['actions', 'Действия'], ['projects', 'Проекты']];

  return (
    <div className="in-admin-wrap">
      <h2>Настройки Issue Notifier</h2>

      <div className="aui-tabs horizontal-tabs">
        <ul className="tabs-menu" role="tablist">
          {tabs.map(([id, label]) => (
            <li key={id} className={'menu-item' + (tab === id ? ' active-tab' : '')}>
              <button
                type="button"
                role="tab"
                id={`in-admin-tab-${id}`}
                aria-selected={tab === id}
                aria-controls={`in-admin-panel-${id}`}
                className="in-tab-button"
                onClick={() => setTab(id)}
              >{label}</button>
            </li>
          ))}
        </ul>

        <div
          className="tabs-pane active-pane"
          role="tabpanel"
          id={`in-admin-panel-${tab}`}
          aria-labelledby={`in-admin-tab-${tab}`}
        >
          {tab === 'actions' && <ActionsTab values={values} setValue={setValue} errors={errors} />}
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

      {/* строка действий липнет к низу: страница длинная, иначе баннер и кнопка
          оказываются в разных концах экрана */}
      <div className="in-admin-actions">
        <button type="button" className="aui-button aui-button-primary" onClick={handleSave}
                disabled={saving || !dirty}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
        {error && <span className="in-admin-status is-error">{error}</span>}
        {!error && success && <span className="in-admin-status is-success">Сохранено</span>}
        {!error && !success && !dirty && <span className="in-admin-status">Изменений нет</span>}
        {!error && !success && dirty && errorKeys.length > 0 && (
          <span className="in-admin-status is-error">Шаблоны с ошибками: {errorKeys.length}</span>
        )}
      </div>
    </div>
  );
}

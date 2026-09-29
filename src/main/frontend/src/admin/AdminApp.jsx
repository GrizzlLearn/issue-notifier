import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings, getContexts, saveContexts, testChannel } from '../api';

// Справочники страницы (проекты, статусы, каталог действий) сервлет кладёт прямо
// в HTML — см. AdminPageData. Поэтому поиск проектов идёт по массиву в памяти
// браузера, без запросов на каждое нажатие клавиши.
const PAGE_DATA = window.ISSUE_NOTIFIER_DATA || { projects: [], statuses: [], actions: [], userFields: [] };
const MAX_SUGGESTIONS = 20;

const SECTIONS = [
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

// Положительный ключ: пустая настройка — не уведомляем (рассылка по всем полям шумная).
const WATCHERS_ENABLED_KEY = 'watchers.enabled';
const COMMENT_TEXT_MODE_KEY = 'comment.textMode';
const WATCHED_FIELDS_KEY = 'watchers.fields';

// Значения совпадают с константами WatchedFields на бэкенде: пустая настройка —
// все группы, снятые галки сохраняются отдельным значением 'none'.
// Третий элемент — подпись под галкой: сотрудник техподдержки не должен угадывать,
// что попадает в группу.
const FIELD_GROUPS = [
  ['description', 'Описание', 'Поле «Описание» задачи.'],
  ['custom', 'Кастомные поля',
    'Поля, созданные у вас в Администрирование → Поля задач: «Заказчик», «Тип обращения», '
    + 'а также Sprint, Epic Link, Story Points. Обычно самый шумный источник уведомлений.'],
  ['other', 'Прочие поля задачи',
    'Встроенные поля Jira, кроме описания: тема, статус, исполнитель, автор, приоритет, '
    + 'срок, метки, компоненты, версии, резолюция, вложения, связи задач, оценки времени.'],
];
const NO_FIELDS = 'none';

// Режимы текста комментария — как CommentTextMode на бэкенде.
const COMMENT_TEXT_MODES = [
  ['hidden', 'Всегда без текста комментария', 'Текста не будет ни у кого.'],
  ['shown', 'Всегда с текстом', 'Текст получат все; личная настройка пользователя не спрашивается.'],
  ['user', 'Пусть каждый выбирает сам', 'В настройках пользователя появляется галка.'],
];
const CLOSED_ACTION = 'closed';
const DEFAULT_CONTEXT = 'default';
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

// Проверочная отправка канала. Отправляем значения секции прямо из формы —
// админ проверяет введённый токен до сохранения; результат приходит от бэкенда
// текстом (например, «Пользователь не найден в Mattermost»).
function ChannelTestButton({ section, values }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
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
      const message = await testChannel(section.channel, settings);
      if (alive.current) setResult(message);
    } catch (e) {
      if (alive.current) setError(e.message);
    } finally {
      if (alive.current) setBusy(false);
    }
  }

  return (
    <div className="field-group">
      <button type="button" className="aui-button" onClick={handleClick} disabled={busy}>
        {busy ? 'Отправка…' : 'Проверить'}
      </button>
      <span style={{ marginLeft: 8 }}>
        {error && <span className="in-status-text is-error">{error}</span>}
        {result && <span className="in-status-text is-success">{result}</span>}
      </span>
      <div style={hintStyle}>
        Сообщение придёт вам. Проверяются значения из формы, сохранять их для этого не нужно;
        пустое поле токена означает «взять сохранённый».
      </div>
    </div>
  );
}

// Проекты контекста: отмеченные явно плюс проекты отмеченных категорий.
// Разворот только для экрана — в настройке категории остаются категориями,
// иначе новый проект в категории пришлось бы отмечать руками.
function contextProjects(context) {
  const categories = context.categories || [];
  const byCategory = PAGE_DATA.projects
    .filter(p => p.category && categories.includes(p.category))
    .map(p => p.value);
  return [...new Set([...(context.projects || []), ...byCategory])];
}

// Настройка действия в контексте; записи нет — действие выключено, как на бэкенде.
function contextAction(context, actionKey) {
  return (context.actions || {})[actionKey] || { enabled: false, recipients: '' };
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

// Карточка одного контекста: имя, состав (категории, SD-проекты, остальные через
// пикер) и удаление. У встроенного контекста «Остальные проекты» состава нет —
// в него попадает всё, что не попало в явные контексты.
function ContextCard({ context, projects, labels, onChange, onRemove }) {
  const categories = PAGE_DATA.categories || [];
  const chosenCategories = context.categories || [];
  const selected = context.projects || [];

  // проект, попавший в контекст через категорию, отмечен, но снимается только категорией —
  // иначе галка возвращалась бы сама и это выглядело бы сбоем
  const viaCategory = new Set(projects
    .filter(p => p.category && chosenCategories.includes(p.category))
    .map(p => p.value));

  const sdProjects = projects.filter(p => p.serviceDesk);
  const sdKeys = sdProjects.map(p => p.value);
  const otherSelected = selected.filter(key => !sdKeys.includes(key));
  const otherViaCategory = [...viaCategory].filter(key => !sdKeys.includes(key) && !selected.includes(key));

  const setSelected = next => onChange({ ...context, projects: next });

  function toggleCategory(id, checked) {
    onChange({
      ...context,
      categories: checked ? [...chosenCategories, id] : chosenCategories.filter(c => c !== id),
    });
  }

  if (context.id === DEFAULT_CONTEXT) {
    return (
      <fieldset className="in-section">
        <legend>Остальные проекты</legend>
        <div style={hintStyle}>
          Встроенный контекст: сюда попадают задачи проектов, которых нет ни в одном
          контексте выше. Удалить его нельзя. Какие действия в нём включены — на вкладке «Действия».
        </div>
      </fieldset>
    );
  }

  return (
    <fieldset className="in-section">
      <legend>{context.name || 'Новый контекст'}</legend>

      <div className="field-group" style={{ marginBottom: 16 }}>
        <label className="label" htmlFor={`in-ctx-name-${context.id}`}>Название контекста</label>
        <input
          id={`in-ctx-name-${context.id}`}
          className="text"
          type="text"
          value={context.name || ''}
          onChange={e => onChange({ ...context, name: e.target.value })}
          placeholder="Например: заявки на доступ"
          style={{ width: '100%', maxWidth: 400 }}
        />
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Категории проектов</div>
        {categories.length === 0 && <div style={hintStyle}>Категорий проектов в инстансе нет.</div>}
        <div className="in-check-grid">
          {categories.map(c => (
            <label key={c.value} className="in-check">
              <input
                type="checkbox"
                checked={chosenCategories.includes(c.value)}
                onChange={e => toggleCategory(c.value, e.target.checked)}
              />
              <span>{c.label}</span>
            </label>
          ))}
        </div>
        <div style={hintStyle}>
          Проект, добавленный в отмеченную категорию позже, попадёт в контекст сам —
          но закрывающие статусы для него всё равно нужно выбрать на вкладке «Действия».
        </div>
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontWeight: 600, marginBottom: 6 }}>Service Desk</div>
        {sdProjects.length === 0 && <div style={hintStyle}>Service Desk-проекты не найдены.</div>}
        <div className="in-check-grid">
          {sdProjects.map(p => (
            <label key={p.value} className="in-check">
              <input
                type="checkbox"
                checked={selected.includes(p.value) || viaCategory.has(p.value)}
                disabled={viaCategory.has(p.value)}
                onChange={e => setSelected(e.target.checked
                  ? [...selected, p.value]
                  : selected.filter(k => k !== p.value))}
              />
              <span>
                {p.label}
                {viaCategory.has(p.value) && <span className="in-check-note"> через категорию</span>}
              </span>
            </label>
          ))}
        </div>
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

      <div style={{ marginTop: 16 }}>
        <button type="button" className="aui-button aui-button-link" style={{ padding: 0 }}
                onClick={onRemove}>
          Удалить контекст
        </button>
        <div style={hintStyle}>
          Его проекты вернутся в «Остальные проекты», а настройки действий контекста пропадут.
        </div>
      </div>
    </fieldset>
  );
}

// Закрывающие статусы задаются отдельно для каждого выбранного проекта:
// в разных workflow закрытие называется по-разному. Строка проекта — кнопка:
// раскрывается список статусов, выбранные видны чипами и без раскрытия.
function ClosingStatusesField({ labels, selected, statuses, disabled, map, onChange }) {
  const [openProject, setOpenProject] = useState(null);
  const statusNames = Object.fromEntries(statuses.map(s => [s.value, s.label]));

  function toggleStatus(projectKey, statusId, checked) {
    const current = map[projectKey] || [];
    const next = checked ? [...current, statusId] : current.filter(id => id !== statusId);
    onChange({ ...map, [projectKey]: next });
  }

  if (selected.length === 0) {
    return <div style={hintStyle}>В контексте нет проектов — отметьте их на вкладке «Контекст проектов», чтобы выбрать закрывающие статусы.</div>;
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
                disabled={disabled}
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
                    onClick={() => onChange({
                      ...map,
                      // дополняем: статус, отмеченный вручную, терять нельзя
                      [key]: [...new Set([...chosen, ...statuses.filter(st => st.done).map(st => st.value)])],
                    })}
                  >
                    Отметить статусы категории «Готово»
                  </button>
                  <div className="in-status-grid">
                    {statuses.map(s => (
                      <label key={s.value} className="in-check">
                        <input
                          type="checkbox"
                          checked={chosen.includes(s.value)}
                          disabled={disabled}
                          onChange={e => toggleStatus(key, s.value, e.target.checked)}
                        />
                        <span>
                          {s.label}
                          {s.done && <span className="in-badge-done">категория «Готово»</span>}
                        </span>
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
function selectedRecipients(raw) {
  const chosen = parseKeys(raw);
  if (chosen.length === 0) {
    return ['watchers'];
  }
  return chosen.filter(v => v !== NO_RECIPIENTS);
}

// Кому уходит уведомление: встроенные поля задачи плюс кастомные user picker-поля.
function RecipientsField({ raw, disabled, onChange }) {
  const userFields = PAGE_DATA.userFields || [];
  const selected = selectedRecipients(raw);

  function toggle(value, checked) {
    const next = checked ? [...selected, value] : selected.filter(v => v !== value);
    onChange((next.length ? next : [NO_RECIPIENTS]).join(','));
  }

  return (
    <div className="field-group" style={{ marginBottom: 12 }}>
      <div className="label">Кому отправлять</div>

      <div className="in-check-grid">
        {BUILT_IN_RECIPIENTS.map(([value, label]) => (
          <label key={value} className="in-check">
            <input
              type="checkbox"
              checked={selected.includes(value)}
              disabled={disabled}
              onChange={e => toggle(value, e.target.checked)}
            />
            <span>{label}</span>
          </label>
        ))}
      </div>

      <div style={{ marginTop: 8 }}>
        <div style={{ fontWeight: 600, marginBottom: 4 }}>Поля с пользователями</div>
        {userFields.length === 0 && <div style={hintStyle}>Полей типа «user picker» в инстансе нет.</div>}
        <div className="in-check-grid">
          {userFields.map(field => (
            <label key={field.value} className="in-check">
              <input
                type="checkbox"
                checked={selected.includes(field.value)}
                disabled={disabled}
                onChange={e => toggle(field.value, e.target.checked)}
              />
              <span>{field.label} <span className="in-check-note">{field.scope}</span></span>
            </label>
          ))}
        </div>
      </div>

      <div style={hintStyle}>
        Поле, которого нет в схеме экрана конкретной задачи, получателя не даёт —
        выбирать поля отдельно на каждый тип задачи не нужно.
      </div>
    </div>
  );
}

// Действие в выбранном контексте: галка «уведомлять», получатели и шаблоны текста.
// Пока действие выключено, остальные его настройки задизейблены — иначе непонятно,
// работает ли уже настроенное. Шаблоны общие на инстанс, получатели и статусы — на контекст.
function ActionsPanel({ actions, labels, context, statuses, values, setValue, setContext, errors }) {
  const selected = contextProjects(context);

  function setAction(actionKey, patch) {
    const current = contextAction(context, actionKey);
    setContext({
      ...context,
      actions: { ...(context.actions || {}), [actionKey]: { ...current, ...patch } },
    });
  }

  return (
    <>
      {actions.map(action => {
        const setting = contextAction(context, action.key);
        const enabled = setting.enabled;
        const liveChannels = action.channels.filter(ch => isChannelOn(values, ch.channel));
        const noTemplates = liveChannels.every(ch => !(values[ch.templateKey] || '').trim());
        const noRecipients = action.recipientsConfigurable
          && selectedRecipients(setting.recipients).length === 0;
        const noStatuses = action.key === CLOSED_ACTION
          && Object.values(context.closedStatuses || {}).every(ids => !ids || ids.length === 0);

        const broken = enabled && (noTemplates || noRecipients || noStatuses);

        return (
          // <details> вместо своего состояния: раскрытие, фокус и клавиатура —
          // штатное поведение браузера, а действий со временем станет больше
          <details key={action.key} className="in-action">
            <summary className="in-action-head">
              <span className="in-action-title">{action.title}</span>
              <span className={'in-action-state' + (enabled ? ' in-on' : '')}>
                {enabled ? 'уведомляем' : 'выключено'}
              </span>
              {broken && (
                <span className="in-action-warn" title="Уведомления по этому действию не отправятся">
                  не отправится
                </span>
              )}
            </summary>

            <div className="in-action-body">
              <div className="field-group" style={{ marginBottom: 12 }}>
                <label className="in-check">
                  <input
                    type="checkbox"
                    checked={enabled}
                    onChange={e => setAction(action.key, { enabled: e.target.checked })}
                  />
                  <span>Уведомлять в контексте «{context.name || 'Остальные проекты'}»</span>
                </label>
                {!enabled && (
                  <div style={hintStyle}>
                    Если хочешь настроить — включи эту опцию.
                  </div>
                )}
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

              {enabled && noStatuses && (
                <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
                  Закрывающие статусы не выбраны — уведомления о закрытии не отправятся.
                </div>
              )}

              {action.recipientsConfigurable && (
                <RecipientsField
                  raw={setting.recipients}
                  disabled={!enabled}
                  onChange={raw => setAction(action.key, { recipients: raw })}
                />
              )}

              {action.key === CLOSED_ACTION && (
                <ClosingStatusesField
                  labels={labels}
                  selected={selected}
                  statuses={statuses}
                  disabled={!enabled}
                  map={context.closedStatuses || {}}
                  onChange={map => setContext({ ...context, closedStatuses: map })}
                />
              )}

              {action.channels.map(ch => {
                const channelOff = !isChannelOn(values, ch.channel);
                const readOnly = channelOff || !enabled;

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
                      readOnly={readOnly}
                      onChange={readOnly ? undefined : e => setValue(ch.templateKey, e.target.value)}
                      aria-invalid={Boolean(errors[ch.templateKey])}
                      style={{ width: '100%', background: readOnly ? '#f4f5f7' : undefined }}
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
                          readOnly={readOnly}
                          onChange={readOnly ? undefined : e => setValue(ch.templateKeyNoText, e.target.value)}
                          aria-invalid={Boolean(errors[ch.templateKeyNoText])}
                          style={{ width: '100%', background: readOnly ? '#f4f5f7' : undefined }}
                        />
                        {errors[ch.templateKeyNoText] && (
                          <div className="in-field-error">{errors[ch.templateKeyNoText]}</div>
                        )}
                        <div style={hintStyle}>
                          Уходит, когда текст отправлять нельзя: запрет в «Тексте комментариев» выше,
                          личная настройка получателя или комментарий с ограничением по группе или роли.
                          Плейсхолдер {'{comment}'} в нём остаётся пустым.
                        </div>
                      </>
                    )}
                  </div>
                );
              })}

              <div style={hintStyle}>
                Текст шаблона общий на весь инстанс: контекст решает, кому и о чём
                уведомлять, а не какими словами. Доступные плейсхолдеры:{' '}
                {action.placeholders.map(p => '{' + p + '}').join(', ')}
              </div>
            </div>
          </details>
        );
      })}
    </>
  );
}

// Справочники уже в PAGE_DATA, загружать на вкладках нечего.
const PROJECT_LABELS = Object.fromEntries(PAGE_DATA.projects.map(p => [p.value, p.label]));

// Вкладка «Контекст проектов»: сколько контекстов и что в каждом.
function ContextsTab({ contexts, setContexts }) {
  function update(id, next) {
    setContexts(contexts.map(c => (c.id === id ? next : c)));
  }

  function add() {
    // id выдаёт сервер при сохранении; пока контекст новый, ключ нужен только React
    setContexts([...contexts.filter(c => c.id !== DEFAULT_CONTEXT),
      { id: `new-${Date.now()}`, name: '', projects: [], categories: [], actions: {}, closedStatuses: {} },
      ...contexts.filter(c => c.id === DEFAULT_CONTEXT)]);
  }

  const explicit = contexts.filter(c => c.id !== DEFAULT_CONTEXT);

  return (
    <>
      <div style={{ ...hintStyle, marginBottom: 12 }}>
        Контекст — это набор проектов со своим набором включённых действий: например,
        в одной категории нужны только упоминания, в другой ещё и переходы в закрывающий
        статус. Проект и категория входят ровно в один контекст. Тексты уведомлений общие
        на инстанс и задаются на вкладке «Действия».
      </div>

      {explicit.length === 0 && (
        <div className="aui-message aui-message-info" style={{ marginBottom: 12 }}>
          Явных контекстов нет — все проекты настраиваются как «Остальные проекты».
        </div>
      )}

      {contexts.map(context => (
        <ContextCard
          key={context.id}
          context={context}
          projects={PAGE_DATA.projects}
          labels={PROJECT_LABELS}
          onChange={next => update(context.id, next)}
          onRemove={() => setContexts(contexts.filter(c => c.id !== context.id))}
        />
      ))}

      <button type="button" className="aui-button" onClick={add}>Добавить контекст</button>
    </>
  );
}

// Отмеченные группы полей; пустая настройка — все группы, как на бэкенде.
function watchedGroups(values) {
  const chosen = parseKeys(values[WATCHED_FIELDS_KEY]);
  if (chosen.length === 0) {
    return FIELD_GROUPS.map(([value]) => value);
  }
  return chosen.filter(v => v !== NO_FIELDS);
}

// Рассылка наблюдателям об изменениях задач: общий выключатель и группы полей,
// которые считаются поводом для уведомления.
function IssueChangesSection({ values, setValue }) {
  const on = values[WATCHERS_ENABLED_KEY] === 'true';
  const groups = watchedGroups(values);

  function toggleGroup(value, checked) {
    const next = checked ? [...groups, value] : groups.filter(v => v !== value);
    setValue(WATCHED_FIELDS_KEY, (next.length ? next : [NO_FIELDS]).join(','));
  }

  return (
    <fieldset className="in-section">
      <legend>Изменения задач</legend>
      <label className="in-check">
        <input
          type="checkbox"
          checked={on}
          onChange={e => setValue(WATCHERS_ENABLED_KEY, e.target.checked ? 'true' : 'false')}
        />
        <span>Уведомлять наблюдателей об изменениях задач</span>
      </label>
      <div style={hintStyle}>
        По умолчанию выключено. Пока выключено — рассылка об изменении полей не идёт.
        Уведомления о действиях ниже работают независимо, включая те, где получателями
        выбраны наблюдатели.
      </div>

      {on && (
        <div style={{ marginTop: 10, marginLeft: 20 }}>
          <div className="label">Какие изменения считать поводом</div>
          {FIELD_GROUPS.map(([value, label, note]) => (
            <label key={value} className="in-check" style={{ marginBottom: 6 }}>
              <input
                type="checkbox"
                checked={groups.includes(value)}
                onChange={e => toggleGroup(value, e.target.checked)}
              />
              <span>{label}
                <span className="in-check-note" style={{ display: 'block' }}>{note}</span>
              </span>
            </label>
          ))}
          <div style={hintStyle}>
            Снятая галка убирает такие поля из сообщения; если после этого менять
            нечего — уведомление не отправляется. Комментарии сюда не входят — это
            отдельное действие ниже. На уведомления о действиях (назначение,
            закрытие) галки не влияют.
          </div>
          {groups.length === 0 && (
            <div className="aui-message aui-message-warning" style={{ marginTop: 8 }}>
              Ни одна группа полей не выбрана — уведомления об изменениях приходить не будут.
            </div>
          )}
        </div>
      )}
    </fieldset>
  );
}

// Вкладка «Действия»: выбранный контекст и его настройки плюс общие настройки инстанса.
function ActionsTab({ values, setValue, contexts, setContexts, errors }) {
  const [contextId, setContextId] = useState(DEFAULT_CONTEXT);
  // контекст мог быть удалён на соседней вкладке, пока эта помнила его id
  const context = contexts.find(c => c.id === contextId) || contexts[contexts.length - 1];

  return (
    <>
      <IssueChangesSection values={values} setValue={setValue} />

      <fieldset className="in-section">
        <legend>Текст комментариев</legend>
        {COMMENT_TEXT_MODES.map(([value, label, hint]) => (
          <label key={value} className="in-check" style={{ marginBottom: 4 }}>
            <input
              type="radio"
              name={COMMENT_TEXT_MODE_KEY}
              checked={values[COMMENT_TEXT_MODE_KEY] === value}
              onChange={() => setValue(COMMENT_TEXT_MODE_KEY, value)}
            />
            <span>{label}
              <span className="in-check-note" style={{ display: 'block' }}>{hint}</span>
            </span>
          </label>
        ))}
        <div style={hintStyle}>
          Настройка действует на все действия с текстом комментария во всех контекстах.
        </div>
      </fieldset>

      <fieldset className="in-section">
        <legend>Действия контекста</legend>
        <div className="field-group" style={{ marginBottom: 12 }}>
          <label className="label" htmlFor="in-context-select">Контекст проектов</label>
          <select
            id="in-context-select"
            className="select"
            value={context ? context.id : DEFAULT_CONTEXT}
            onChange={e => setContextId(e.target.value)}
          >
            {contexts.map(c => (
              <option key={c.id} value={c.id}>
                {c.id === DEFAULT_CONTEXT ? 'Остальные проекты' : (c.name || 'Новый контекст')}
              </option>
            ))}
          </select>
          <div style={hintStyle}>
            Контексты и их состав — на вкладке «Контекст проектов».
          </div>
        </div>

        {context && (
          <ActionsPanel
            actions={PAGE_DATA.actions}
            labels={PROJECT_LABELS}
            context={context}
            statuses={PAGE_DATA.statuses}
            values={values}
            setValue={setValue}
            setContext={next => setContexts(contexts.map(c => (c.id === context.id ? next : c)))}
            errors={errors}
          />
        )}
      </fieldset>
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
  // контексты живут отдельным эндпоинтом и правятся списком целиком
  const [contexts, setContexts] = useState([]);
  const [savedContexts, setSavedContexts] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([getAdminSettings(controller.signal), getContexts(controller.signal)])
      .then(([data, ctx]) => {
        setValues(data);
        setSaved(data);
        setContexts(ctx);
        setSavedContexts(JSON.stringify(ctx));
        setLoading(false);
      })
      .catch(e => {
        if (e.name !== 'AbortError') { setError(e.message); setLoading(false); }
      });
    return () => {
      controller.abort();
      clearTimeout(successTimerRef.current);
    };
  }, []);

  const payload = buildPayload(values, saved);
  const contextsDirty = JSON.stringify(contexts) !== savedContexts;
  const dirty = Object.keys(payload).length > 0 || contextsDirty;
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
    const nameless = contexts.find(c => c.id !== DEFAULT_CONTEXT && !(c.name || '').trim());
    if (nameless) {
      setError('У контекста не задано название — вкладка «Контекст проектов».');
      return;
    }
    setSaving(true); setError(null); setSuccess(false);
    try {
      // контексты первыми: они валидируются на сервере строже, и при отказе
      // настройки каналов не должны уехать половиной
      if (contextsDirty) {
        await saveContexts(contexts);
        const fresh = await getContexts();
        setContexts(fresh);
        setSavedContexts(JSON.stringify(fresh));
      }
      if (Object.keys(payload).length > 0) {
        await saveAdminSettings(payload);
      }
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

  // порядок совпадает со сценарием настройки: подключить канал, отметить
  // проекты, включить действия
  const tabs = [['channels', 'Каналы'], ['projects', 'Контекст проектов'], ['actions', 'Действия']];

  return (
    <div className="in-admin-wrap">
      <h2>Настройки Issue Notifier</h2>

      {/* своя раскладка вкладок: AUI стилизует .menu-item a, а ссылка на "#"
          дёргала hash страницы и не давала доступной кнопки */}
      <div>
        <div className="in-tabs" role="tablist">
          {tabs.map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              id={`in-admin-tab-${id}`}
              aria-selected={tab === id}
              aria-controls={`in-admin-panel-${id}`}
              className={'in-tab' + (tab === id ? ' in-active' : '')}
              onClick={() => setTab(id)}
            >{label}</button>
          ))}
        </div>

        <div
          className="in-tab-panel"
          role="tabpanel"
          id={`in-admin-panel-${tab}`}
          aria-labelledby={`in-admin-tab-${tab}`}
        >
          {tab === 'actions' && (
            <ActionsTab values={values} setValue={setValue} contexts={contexts}
                        setContexts={setContexts} errors={errors} />
          )}
          {tab === 'projects' && <ContextsTab contexts={contexts} setContexts={setContexts} />}
          {tab === 'channels' && SECTIONS.map(section => (
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

              {section.channel && <ChannelTestButton section={section} values={values} />}
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

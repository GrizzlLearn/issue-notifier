import React, { useState } from 'react';
import { PAGE_DATA, DEFAULT_CONTEXT, PROJECT_LABELS, contextProjects, hintStyle } from './adminData';

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
const CHANNEL_TITLES = { MATTERMOST: 'Mattermost', TELEGRAM: 'Telegram', EMAIL: 'Email (тело письма — разметка HTML)' };

// Ключ флага канала — как в AdminSettingsServiceImpl: имя канала в нижнем регистре + ".enabled".
const isChannelOn = (values, channel) => values[channel.toLowerCase() + '.enabled'] === 'true';

const parseKeys = raw => (raw || '').split(',').filter(Boolean);

// Настройка действия в контексте; записи нет — действие выключено, как на бэкенде.
function contextAction(context, actionKey) {
  return (context.actions || {})[actionKey] || { enabled: false, recipients: '' };
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
        Поле без значения в конкретной задаче получателя не даёт —
        выбирать поля отдельно на каждый тип задачи не нужно.
      </div>
    </div>
  );
}

// Действие в выбранном контексте: галка «уведомлять», получатели и шаблоны текста.
// Пока действие выключено, остальные его настройки задизейблены — иначе непонятно,
// работает ли уже настроенное. Шаблоны общие на инстанс, получатели и статусы — на контекст.
function ActionsPanel({ actions, labels, context, contexts, statuses, values, setValue, setContext, errors }) {
  const selected = contextProjects(context, contexts);

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
                    <label className="label" htmlFor={`${context.id}-${ch.templateKey}`}>
                      {CHANNEL_TITLES[ch.channel] || ch.channel}
                      {channelOff && <span style={{ ...hintStyle, marginLeft: 8, fontWeight: 'normal' }}>канал отключён</span>}
                    </label>
                    {ch.subjectKey && (
                      <>
                        <input
                          id={`${context.id}-${ch.subjectKey}`}
                          className="text"
                          type="text"
                          placeholder={ch.subjectDefault}
                          value={values[ch.subjectKey] || ''}
                          readOnly={readOnly}
                          onChange={readOnly ? undefined : e => setValue(ch.subjectKey, e.target.value)}
                          aria-invalid={Boolean(errors[ch.subjectKey])}
                          aria-label="Тема письма"
                          style={{ width: '100%', marginBottom: 6, background: readOnly ? '#f4f5f7' : undefined }}
                        />
                        {errors[ch.subjectKey] && (
                          <div className="in-field-error">{errors[ch.subjectKey]}</div>
                        )}
                        <div style={hintStyle}>
                          Тема письма. Пусто — {ch.subjectDefault}
                        </div>
                      </>
                    )}
                    <textarea
                      id={`${context.id}-${ch.templateKey}`}
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
                        <label className="label" htmlFor={`${context.id}-${ch.templateKeyNoText}`} style={{ marginTop: 8 }}>
                          {(CHANNEL_TITLES[ch.channel] || ch.channel) + ' — без текста комментария'}
                        </label>
                        <textarea
                          id={`${context.id}-${ch.templateKeyNoText}`}
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

// Что включено в контексте — строкой в свёрнутой шапке, чтобы не раскрывать
// каждый контекст ради ответа «а тут что-нибудь настроено?».
function enabledActionTitles(context) {
  return (PAGE_DATA.actions || [])
    .filter(action => contextAction(context, action.key).enabled)
    .map(action => action.title);
}

// Вкладка «Действия»: все контексты списком, каждый раскрывается по клику.
export default function ActionsTab({ values, setValue, contexts, setContexts, errors }) {

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
        <legend>Действия по контекстам</legend>
        <div style={{ ...hintStyle, marginBottom: 12 }}>
          Каждый контекст настраивается отдельно. Состав контекстов — на вкладке
          «Контекст проектов».
        </div>

        {contexts.map(context => {
          const enabled = enabledActionTitles(context);
          const isDefault = context.id === DEFAULT_CONTEXT;
          const projects = contextProjects(context, contexts);

          return (
            // <details> вместо своего состояния: раскрытие, фокус и клавиатура —
            // штатное поведение браузера, а контекстов у админа может быть много
            <details key={context.id} className="in-context">
              <summary className="in-context-head">
                <span className="in-context-title">
                  {isDefault ? 'Остальные проекты' : (context.name || 'Новый контекст')}
                </span>
                <span className="in-context-scope">
                  {isDefault
                    ? 'проекты вне других контекстов'
                    : `проектов: ${projects.length}`}
                </span>
                <span className={'in-context-state' + (enabled.length ? ' in-on' : '')}>
                  {enabled.length ? enabled.join(', ') : 'ничего не включено'}
                </span>
              </summary>

              <div className="in-context-body">
                <ActionsPanel
                  actions={PAGE_DATA.actions}
                  labels={PROJECT_LABELS}
                  context={context}
                  contexts={contexts}
                  statuses={PAGE_DATA.statuses}
                  values={values}
                  setValue={setValue}
                  setContext={next => setContexts(contexts.map(c => (c.id === context.id ? next : c)))}
                  errors={errors}
                />
              </div>
            </details>
          );
        })}
      </fieldset>
    </>
  );
}


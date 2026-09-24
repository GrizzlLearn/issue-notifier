import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  getUserSettings, saveUserSettings,
  getDelegation, saveDelegation, removeDelegation,
  resolveProject, resolveUser, apiBase,
} from '../api';
import AjsMultiSelect from '../shared/AjsMultiSelect';

// Резолвит ключи в {value,label} для пред-заполнения пикера; ключ, который не удалось
// разрешить (пользователь/проект удалён), показываем как есть — сам ключ вместо лейбла.
// AbortError пробрасываем дальше (не глотаем) — иначе Promise.all в загрузке модалки
// не узнает об отмене и вызовет setState после размонтирования.
async function resolveItems(keys, resolveLabel, signal) {
  return Promise.all(keys.map(async key => {
    let label;
    try {
      label = await resolveLabel(key, signal);
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      label = null;
    }
    return { value: key, label: label || key };
  }));
}

const CHANNELS = [
  { id: 'EMAIL', label: 'Email' },
  { id: 'MATTERMOST', label: 'Mattermost' },
  { id: 'TELEGRAM', label: 'Telegram' },
];

function useSuccessTimer(delay = 2500) {
  const [success, setSuccess] = useState(false);
  const timerRef = useRef(null);
  useEffect(() => () => clearTimeout(timerRef.current), []);
  const showSuccess = useCallback(() => {
    setSuccess(true);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setSuccess(false), delay);
  }, [delay]);
  return [success, showSuccess];
}

// В строке кнопок статус компактный: полноразмерный aui-message ломал бы её раскладку.
function StatusBanner({ error, success }) {
  if (error) return <span className="in-status-text is-error">{error}</span>;
  if (success) return <span className="in-status-text is-success">Сохранено</span>;
  return null;
}

function SettingsTab({ settings, onChange, telegramBotUsername, projectItems, onSaved }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, showSuccess] = useSuccessTimer();

  // Подстраховка на случай неполного/битого ответа сервера — UI не должен падать
  const projects = settings.projects ?? ['*'];
  const channels = settings.channels ?? [];
  const allProjects = projects.length === 1 && projects[0] === '*';
  // Каналы, выключенные администратором, не показываем — выбрать их всё равно нельзя,
  // уведомления по ним не уйдут (см. AdminSettingsService.isChannelEnabled)
  const enabledChannels = settings.enabledChannels ?? CHANNELS.map(ch => ch.id);
  const visibleChannels = CHANNELS.filter(ch => enabledChannels.includes(ch.id));

  // Конкретный список проектов храним отдельно от settings.projects — пока включено
  // "Все проекты", settings.projects равен ['*'], и при выключении чекбокса без этого
  // стейта список оказался бы потерян (пикер спрятан через CSS, но жив и хранит свой
  // выбор сам — читать его оттуда напрямую нечем, поэтому синхронизация через стейт)
  const [explicitProjects, setExplicitProjects] = useState(allProjects ? [] : projects);

  const chatId = (settings.telegramChatId || '').trim();
  const chatIdInvalid = channels.includes('TELEGRAM') && chatId !== '' && !/^-?\d+$/.test(chatId);
  // конфигурации, при которых не придёт ничего — админ-страница предупреждает так же
  const noChannels = channels.length === 0;
  const telegramWithoutChatId = channels.includes('TELEGRAM') && chatId === '';

  function handleProjectsChange(keys) {
    setExplicitProjects(keys);
    onChange({ ...settings, projects: keys });
  }

  function toggleAllProjects(checked) {
    onChange({ ...settings, projects: checked ? ['*'] : explicitProjects });
  }

  function toggleChannel(id) {
    const next = channels.includes(id)
      ? channels.filter(c => c !== id)
      : [...channels, id];
    onChange({ ...settings, channels: next });
  }

  async function handleSave() {
    if (chatIdInvalid) {
      setError('Telegram Chat ID — это число, его присылает бот в ответ на /start.');
      return;
    }
    setSaving(true); setError(null);
    try {
      await saveUserSettings(settings);
      onSaved(settings);
      showSuccess();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      {settings.enabled && noChannels && (
        <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
          Не выбран ни один канал доставки — уведомления приходить не будут.
        </div>
      )}
      {settings.enabled && telegramWithoutChatId && (
        <div className="aui-message aui-message-warning" style={{ marginBottom: 12 }}>
          Telegram выбран, но Chat ID не указан — в Telegram ничего не придёт.
        </div>
      )}

      <div className="field-group">
        <label>
          <input
            type="checkbox"
            checked={settings.enabled}
            onChange={e => onChange({ ...settings, enabled: e.target.checked })}
            style={{ marginRight: 6 }}
          />
          Получать уведомления
        </label>
      </div>

      <div className="field-group">
        <label className="label">Каналы доставки</label>
        {visibleChannels.length === 0 && (
          <div className="description">Все каналы отключены администратором.</div>
        )}
        {visibleChannels.map(ch => (
          <label key={ch.id} style={{ display: 'block', marginBottom: 4 }}>
            <input
              type="checkbox"
              checked={channels.includes(ch.id)}
              onChange={() => toggleChannel(ch.id)}
              style={{ marginRight: 6 }}
            />
            {ch.label}
          </label>
        ))}
      </div>

      {settings.commentTextAllowed !== false && (
        <div className="field-group">
          <label>
            <input
              type="checkbox"
              checked={!settings.commentTextHidden}
              onChange={e => onChange({ ...settings, commentTextHidden: !e.target.checked })}
              style={{ marginRight: 6 }}
            />
            Показывать текст комментария в уведомлениях
          </label>
        </div>
      )}

      {channels.includes('TELEGRAM') && (
        <div className="field-group">
          <label className="label" htmlFor="in-telegram-chat-id">Telegram Chat ID</label>
          <input
            id="in-telegram-chat-id"
            className="text"
            type="text"
            inputMode="numeric"
            value={settings.telegramChatId || ''}
            onChange={e => onChange({ ...settings, telegramChatId: e.target.value })}
            placeholder="123456789"
            aria-invalid={chatIdInvalid}
            style={{ width: '100%' }}
          />
          {chatIdInvalid && (
            <div className="description" style={{ color: '#ae2a19' }}>
              Chat ID состоит только из цифр (может начинаться с минуса).
            </div>
          )}
          <div className="description">
            {telegramBotUsername
              ? <>Найдите бота <code>@{telegramBotUsername.replace(/^@/, '')}</code> в Telegram и напишите{' '}
                  <code>/start</code> — он ответит вашим числовым ID.</>
              : 'Найдите бота плагина в Telegram, напишите /start — он ответит вашим числовым ID. Имя бота уточните у администратора.'}
          </div>
        </div>
      )}

      <div className="field-group">
        <label className="label" htmlFor="in-projects">Проекты — уведомления об изменениях задач</label>
        <div className="description" style={{ marginBottom: 6 }}>
          Ограничивает только уведомления об изменениях в задачах, за которыми вы наблюдаете.
          Упоминания через @ и другие уведомления о действиях приходят независимо от этого списка.
        </div>
        <label style={{ display: 'block', marginBottom: 6 }}>
          <input
            type="checkbox"
            checked={allProjects}
            onChange={e => toggleAllProjects(e.target.checked)}
            style={{ marginRight: 6 }}
          />
          Все проекты
        </label>
        {/* смонтирован всегда (даже под "Все проекты" скрыт через CSS) — чтобы не терять
            уже введённый набор проектов при переключении чекбокса туда-обратно */}
        <div style={{ display: allProjects ? 'none' : 'block' }}>
          <AjsMultiSelect id="in-projects" initialItems={projectItems} url={`${apiBase()}/projects`}
                          ariaLabel="Проекты" onChange={handleProjectsChange} />
        </div>
      </div>

      {/* статус рядом с кнопкой: тело модалки скроллится, баннер наверху был бы не виден */}
      <div className="in-actions">
        <StatusBanner error={error} success={success} />
        <button type="button" className="aui-button aui-button-primary in-actions-end" onClick={handleSave} disabled={saving}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

function DelegationTab({ delegation, delegateItems, onSaved, onDirtyChange }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, showSuccess] = useSuccessTimer();

  const [toUserKeys, setToUserKeys] = useState(delegation?.toUserKeys ?? []);
  const [activeUntil, setActiveUntil] = useState(delegation?.activeUntil ?? '');
  // AjsMultiSelect читает initialItems только при монтировании — обычный ререндер
  // с новыми props его не обновит. Когда список получателей сбрасывается не через сам
  // пикер (см. handleRemove), форсируем пересоздание виджета через смену key.
  const [pickerItems, setPickerItems] = useState(delegateItems);
  const [pickerKey, setPickerKey] = useState(0);

  useEffect(() => {
    setToUserKeys(delegation?.toUserKeys ?? []);
    setActiveUntil(delegation?.activeUntil ?? '');
  }, [delegation]);

  const today = new Date().toISOString().slice(0, 10);
  const dateInPast = activeUntil !== '' && activeUntil < today;

  useEffect(() => {
    const saved = delegation?.toUserKeys ?? [];
    const changed = toUserKeys.join(',') !== saved.join(',')
      || activeUntil !== (delegation?.activeUntil ?? '');
    onDirtyChange(changed);
  }, [toUserKeys, activeUntil, delegation, onDirtyChange]);

  async function handleSave() {
    if (dateInPast) {
      setError('Дата окончания уже прошла — такая делегация не работает.');
      return;
    }
    setSaving(true); setError(null);
    try {
      await saveDelegation({ toUserKeys, activeUntil: activeUntil || null });
      const updated = await getDelegation();
      onSaved(updated);
      showSuccess();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  async function handleRemove() {
    setSaving(true); setError(null);
    try {
      await removeDelegation();
      onSaved({ toUserKeys: [], activeUntil: null });
      setPickerItems([]);
      setPickerKey(k => k + 1);
      showSuccess();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      <p className="in-hint">
        Уведомления будут пересылаться указанному коллеге. Для бессрочного делегирования оставьте дату пустой.
      </p>

      <div className="field-group">
        <label className="label" htmlFor="in-delegate">Получатели</label>
        <AjsMultiSelect key={pickerKey} id="in-delegate" initialItems={pickerItems} url={`${apiBase()}/users`}
                        ariaLabel="Получатели делегирования" onChange={setToUserKeys} />
      </div>

      <div className="field-group">
        <label className="label" htmlFor="in-until">Активно до (необязательно)</label>
        <input
          id="in-until"
          className="text"
          type="date"
          value={activeUntil}
          min={today}
          onChange={e => setActiveUntil(e.target.value)}
          aria-invalid={dateInPast}
          style={{ width: '100%' }}
        />
        {dateInPast && (
          <div className="description" style={{ color: '#ae2a19' }}>
            Дата уже прошла — делегация не будет работать.
          </div>
        )}
      </div>

      {toUserKeys.length === 0 && (
        <div className="description">
          {delegation?.toUserKeys?.length > 0
            ? 'Чтобы прекратить пересылку, нажмите «Снять делегацию».'
            : 'Выберите хотя бы одного получателя, чтобы сохранить делегирование.'}
        </div>
      )}

      <div className="in-actions">
        {delegation?.toUserKeys?.length > 0 && (
          <button type="button" className="aui-button aui-button-danger" onClick={handleRemove} disabled={saving}>
            Снять делегацию
          </button>
        )}
        <StatusBanner error={error} success={success} />
        <button
          type="button"
          className="aui-button aui-button-primary in-actions-end"
          onClick={handleSave}
          disabled={saving || toUserKeys.length === 0}
        >
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

export default function UserSettingsModal({ onClose }) {
  const [tab, setTab] = useState('settings');
  const [settings, setSettings] = useState(null);
  const [delegation, setDelegation] = useState(null);
  const [projectItems, setProjectItems] = useState([]);
  const [delegateItems, setDelegateItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [delegationDirty, setDelegationDirty] = useState(false);
  const dialogRef = useRef(null);
  const savedSettingsRef = useRef('');

  // Загрузка данных с отменой при размонтировании. Лейблы для уже сохранённых ключей
  // (проекты/делегаты) резолвим здесь же, до первого рендера пикеров — виджет читает
  // начальные <option selected> только один раз, при инициализации.
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      getUserSettings(controller.signal),
      getDelegation(controller.signal),
    ])
      .then(async ([s, d]) => {
        const projectKeys = (s.projects || []).filter(k => k !== '*');
        const [projItems, delItems] = await Promise.all([
          resolveItems(projectKeys, resolveProject, controller.signal),
          resolveItems(d.toUserKeys || [], resolveUser, controller.signal),
        ]);
        setSettings(s);
        savedSettingsRef.current = JSON.stringify(s);
        setDelegation(d);
        setProjectItems(projItems);
        setDelegateItems(delItems);
        setLoading(false);
      })
      .catch(e => {
        if (e.name !== 'AbortError') { setLoadError(e.message); setLoading(false); }
      });
    return () => controller.abort();
  }, []);

  // Фокус на диалог при открытии и возврат на элемент, с которого модалку открыли
  // (WCAG 2.1 SC 2.4.3)
  useEffect(() => {
    const opener = document.activeElement;
    dialogRef.current?.focus();
    return () => opener?.focus?.();
  }, []);

  // Фон не должен скроллиться под модалкой
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);

  // Несохранённые правки: закрытие спрашивает подтверждение
  const isDirty = useCallback(
    () => delegationDirty || (settings !== null && JSON.stringify(settings) !== savedSettingsRef.current),
    [delegationDirty, settings]);

  const handleClose = useCallback(() => {
    // eslint-disable-next-line no-alert
    if (isDirty() && !window.confirm('Есть несохранённые изменения. Закрыть окно?')) return;
    onClose();
  }, [isDirty, onClose]);

  // Escape закрывает модалку, но не перехватывает Escape у пикера — там он
  // закрывает список подсказок, и потерять из-за этого форму было бы обидно
  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key !== 'Escape' || e.defaultPrevented) return;
      if (document.activeElement?.closest?.('.jira-multi-select')) return;
      handleClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleClose]);

  // Tab не должен уводить фокус на страницу под модалкой
  function handleDialogKeyDown(e) {
    if (e.key !== 'Tab' || !dialogRef.current) return;
    const focusable = [...dialogRef.current.querySelectorAll(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
      .filter(el => el.offsetParent !== null);
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    } else if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    }
  }

  function renderBody() {
    if (loading) return <div className="in-loading">Загрузка…</div>;
    if (loadError) return <div className="aui-message aui-message-error">{loadError}</div>;
    // Оба таба остаются смонтированными — переключение скрывает их через CSS,
    // не размонтируя, чтобы не терять незасохранённые правки
    return (
      <>
        <div role="tabpanel" id="in-panel-settings" aria-labelledby="in-tab-settings"
             hidden={tab !== 'settings'}>
          <SettingsTab settings={settings} onChange={setSettings}
                       telegramBotUsername={settings?.telegramBotUsername} projectItems={projectItems}
                       onSaved={saved => { savedSettingsRef.current = JSON.stringify(saved); }} />
        </div>
        <div role="tabpanel" id="in-panel-delegation" aria-labelledby="in-tab-delegation"
             hidden={tab !== 'delegation'}>
          <DelegationTab delegation={delegation} delegateItems={delegateItems} onSaved={setDelegation}
                         onDirtyChange={setDelegationDirty} />
        </div>
      </>
    );
  }

  return (
    <>
      <div className="in-backdrop" onClick={handleClose} />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="in-modal-title"
        tabIndex="-1"
        className="in-dialog"
        onKeyDown={handleDialogKeyDown}
      >
        <div className="in-dialog-header">
          <h2 id="in-modal-title" className="in-dialog-title">Настройки уведомлений</h2>
          <button
            type="button"
            className="in-dialog-close"
            onClick={handleClose}
            aria-label="Закрыть"
          >×</button>
        </div>

        <div className="in-dialog-tabs" role="tablist">
          {[['settings', 'Настройки'], ['delegation', 'Делегирование']].map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              id={`in-tab-${id}`}
              aria-selected={tab === id}
              aria-controls={`in-panel-${id}`}
              className={`in-dialog-tab${tab === id ? ' in-active' : ''}`}
              onClick={() => setTab(id)}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="in-dialog-body">
          {renderBody()}
        </div>
      </div>
    </>
  );
}

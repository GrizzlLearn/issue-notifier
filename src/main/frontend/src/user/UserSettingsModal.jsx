import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  getUserSettings, saveUserSettings,
  getDelegation, saveDelegation, removeDelegation,
} from '../api';

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

function StatusBanner({ error, success }) {
  if (error) return <div className="aui-message aui-message-error" style={{ marginBottom: 12 }}>{error}</div>;
  if (success) return <div className="aui-message aui-message-success" style={{ marginBottom: 12 }}>Сохранено</div>;
  return null;
}

function SettingsTab({ settings, onChange, telegramBotUsername }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, showSuccess] = useSuccessTimer();
  const isSavingRef = useRef(false);

  const allProjects = settings.projects.length === 1 && settings.projects[0] === '*';

  function toggleChannel(id) {
    const next = settings.channels.includes(id)
      ? settings.channels.filter(c => c !== id)
      : [...settings.channels, id];
    onChange({ ...settings, channels: next });
  }

  function handleProjectsChange(e) {
    const val = e.target.value.trim();
    onChange({ ...settings, projects: val ? val.split(',').map(s => s.trim()).filter(Boolean) : ['*'] });
  }

  async function handleSave() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null);
    try {
      await saveUserSettings(settings);
      showSuccess();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
      isSavingRef.current = false;
    }
  }

  return (
    <div>
      <StatusBanner error={error} success={success} />

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
        {CHANNELS.map(ch => (
          <label key={ch.id} style={{ display: 'block', marginBottom: 4 }}>
            <input
              type="checkbox"
              checked={settings.channels.includes(ch.id)}
              onChange={() => toggleChannel(ch.id)}
              style={{ marginRight: 6 }}
            />
            {ch.label}
          </label>
        ))}
      </div>

      {settings.channels.includes('TELEGRAM') && (
        <div className="field-group">
          <label className="label" htmlFor="in-telegram-chat-id">Telegram Chat ID</label>
          <input
            id="in-telegram-chat-id"
            className="text"
            type="text"
            value={settings.telegramChatId || ''}
            onChange={e => onChange({ ...settings, telegramChatId: e.target.value })}
            placeholder="123456789"
            style={{ width: '100%' }}
          />
          <div className="description">
            {telegramBotUsername
              ? <>Найдите бота <code>@{telegramBotUsername}</code> в Telegram и напишите{' '}
                  <code>/start</code> — он ответит вашим числовым ID.</>
              : 'Найдите бота плагина в Telegram, напишите /start — он ответит вашим числовым ID. Имя бота уточните у администратора.'}
          </div>
        </div>
      )}

      <div className="field-group">
        <label className="label" htmlFor="in-projects">Проекты</label>
        <label style={{ display: 'block', marginBottom: 6 }}>
          <input
            type="checkbox"
            checked={allProjects}
            onChange={e => onChange({ ...settings, projects: e.target.checked ? ['*'] : [] })}
            style={{ marginRight: 6 }}
          />
          Все проекты
        </label>
        {!allProjects && (
          <input
            id="in-projects"
            className="text"
            type="text"
            value={settings.projects.join(', ')}
            onChange={handleProjectsChange}
            placeholder="PROJ, TEST, DEV"
            style={{ width: '100%' }}
          />
        )}
      </div>

      <div className="in-actions">
        <button type="button" className="aui-button aui-button-primary in-actions-end" onClick={handleSave} disabled={saving}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

function DelegationTab({ delegation, onSaved }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, showSuccess] = useSuccessTimer();
  const isSavingRef = useRef(false);
  // Защита от state update на размонтированный компонент
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  const [toUserKey, setToUserKey] = useState(delegation?.toUserKey ?? '');
  const [activeUntil, setActiveUntil] = useState(delegation?.activeUntil ?? '');

  useEffect(() => {
    setToUserKey(delegation?.toUserKey ?? '');
    setActiveUntil(delegation?.activeUntil ?? '');
  }, [delegation]);

  async function handleSave() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null);
    try {
      await saveDelegation({ toUserKey, activeUntil: activeUntil || null });
      const updated = await getDelegation();
      if (!mountedRef.current) return;
      onSaved(updated);
      showSuccess();
    } catch (e) {
      if (!mountedRef.current) return;
      setError(e.message);
    } finally {
      if (mountedRef.current) setSaving(false);
      isSavingRef.current = false;
    }
  }

  async function handleRemove() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null);
    try {
      await removeDelegation();
      if (!mountedRef.current) return;
      onSaved(null);
      showSuccess();
    } catch (e) {
      if (!mountedRef.current) return;
      setError(e.message);
    } finally {
      if (mountedRef.current) setSaving(false);
      isSavingRef.current = false;
    }
  }

  return (
    <div>
      <StatusBanner error={error} success={success} />
      <p className="in-hint">
        Уведомления будут пересылаться указанному коллеге. Для бессрочного делегирования оставьте дату пустой.
      </p>

      <div className="field-group">
        <label className="label" htmlFor="in-delegate">Ключ пользователя</label>
        <input
          id="in-delegate"
          className="text"
          type="text"
          value={toUserKey}
          onChange={e => setToUserKey(e.target.value)}
          placeholder="jsmith"
          style={{ width: '100%' }}
        />
        <div className="description">Используйте ключ пользователя из профиля Jira, не email</div>
      </div>

      <div className="field-group">
        <label className="label" htmlFor="in-until">Активно до (необязательно)</label>
        <input
          id="in-until"
          className="text"
          type="date"
          value={activeUntil}
          onChange={e => setActiveUntil(e.target.value)}
          style={{ width: '100%' }}
        />
      </div>

      <div className="in-actions">
        {delegation && (
          <button type="button" className="aui-button aui-button-danger" onClick={handleRemove} disabled={saving}>
            Снять делегацию
          </button>
        )}
        <button
          type="button"
          className="aui-button aui-button-primary in-actions-end"
          onClick={handleSave}
          disabled={saving || !toUserKey.trim()}
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
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const dialogRef = useRef(null);

  // Загрузка данных с отменой при размонтировании
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      getUserSettings(controller.signal),
      getDelegation(controller.signal),
    ])
      .then(([s, d]) => { setSettings(s); setDelegation(d); setLoading(false); })
      .catch(e => {
        if (e.name !== 'AbortError') { setLoadError(e.message); setLoading(false); }
      });
    return () => controller.abort();
  }, []);

  // Фокус на диалог при открытии (WCAG 2.1 SC 2.4.3)
  useEffect(() => {
    dialogRef.current?.focus();
  }, []);

  // Закрытие по Escape
  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key === 'Escape' && !loading) onClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [loading, onClose]);

  function renderBody() {
    if (loading) return <div className="in-loading">Загрузка…</div>;
    if (loadError) return <div className="aui-message aui-message-error">{loadError}</div>;
    // Оба таба остаются смонтированными — переключение скрывает их через CSS,
    // не размонтируя, чтобы не терять незасохранённые правки
    return (
      <>
        <div style={{ display: tab === 'settings' ? 'block' : 'none' }}>
          <SettingsTab settings={settings} onChange={setSettings}
                       telegramBotUsername={settings?.telegramBotUsername} />
        </div>
        <div style={{ display: tab === 'delegation' ? 'block' : 'none' }}>
          <DelegationTab delegation={delegation} onSaved={setDelegation} />
        </div>
      </>
    );
  }

  return (
    <>
      <div
        className="in-backdrop"
        onClick={loading ? undefined : onClose}
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="in-modal-title"
        tabIndex="-1"
        className="in-dialog"
      >
        <div className="in-dialog-header">
          <h2 id="in-modal-title" className="in-dialog-title">Настройки уведомлений</h2>
          <button
            type="button"
            className="in-dialog-close"
            onClick={onClose}
            aria-label="Закрыть"
          >×</button>
        </div>

        <div className="in-dialog-tabs">
          {[['settings', 'Настройки'], ['delegation', 'Делегирование']].map(([id, label]) => (
            <button
              key={id}
              type="button"
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

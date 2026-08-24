import React, { useState, useEffect, useRef } from 'react';
import {
  getUserSettings, saveUserSettings,
  getDelegation, saveDelegation, removeDelegation,
} from '../api';

const CHANNELS = [
  { id: 'EMAIL', label: 'Email' },
  { id: 'MATTERMOST', label: 'Mattermost' },
];

// Хук для автоматического сброса флага успеха с очисткой таймера при размонтировании
function useSuccessTimer(delay = 2500) {
  const [success, setSuccess] = useState(false);
  const timerRef = useRef(null);
  useEffect(() => () => clearTimeout(timerRef.current), []);
  function showSuccess() {
    setSuccess(true);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setSuccess(false), delay);
  }
  return [success, showSuccess];
}

function StatusBanner({ error, success }) {
  if (error) return <div className="aui-message aui-message-error" style={{ marginBottom: 12 }}>{error}</div>;
  if (success) return <div className="aui-message aui-message-success" style={{ marginBottom: 12 }}>Сохранено</div>;
  return null;
}

function SettingsTab({ settings, onChange }) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, showSuccess] = useSuccessTimer();
  // Защита от двойного сабмита — ref обновляется синхронно, в отличие от state
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

      <div style={{ textAlign: 'right', marginTop: 16 }}>
        <button className="aui-button aui-button-primary" onClick={handleSave} disabled={saving}>
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
      // Перечитываем с сервера чтобы получить полный объект (с серверными полями)
      const updated = await getDelegation();
      onSaved(updated);
      showSuccess();
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
      isSavingRef.current = false;
    }
  }

  async function handleRemove() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null);
    try {
      await removeDelegation();
      onSaved(null);
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
      <p style={{ color: '#5e6c84', marginBottom: 16 }}>
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

      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 16 }}>
        {delegation && (
          <button className="aui-button aui-button-danger" onClick={handleRemove} disabled={saving}>
            Снять делегацию
          </button>
        )}
        <button
          className="aui-button aui-button-primary"
          onClick={handleSave}
          disabled={saving || !toUserKey.trim()}
          style={{ marginLeft: 'auto' }}
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
  const [delegation, setDelegation] = useState(undefined);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  // AbortController отменяет незавершённые запросы при размонтировании
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

  function renderBody() {
    if (loading) return <div style={{ textAlign: 'center', padding: 32, color: '#5e6c84' }}>Загрузка…</div>;
    if (loadError) return <div className="aui-message aui-message-error">{loadError}</div>;
    if (tab === 'settings') return <SettingsTab settings={settings} onChange={setSettings} />;
    return <DelegationTab delegation={delegation} onSaved={setDelegation} />;
  }

  return (
    <>
      {/* backdrop — не закрывает модалку пока данные загружаются */}
      <div
        onClick={loading ? undefined : onClose}
        style={{
          position: 'fixed', inset: 0,
          background: 'rgba(9,30,66,0.54)',
          zIndex: 2999,
        }}
      />

      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="in-modal-title"
        style={{
          position: 'fixed', top: '50%', left: '50%',
          transform: 'translate(-50%, -50%)',
          zIndex: 3000, background: '#fff',
          borderRadius: 3, width: 500,
          boxShadow: '0 8px 32px rgba(9,30,66,0.25)',
          display: 'flex', flexDirection: 'column',
          maxHeight: '90vh',
        }}
      >
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px', borderBottom: '1px solid #dfe1e6',
        }}>
          <h2 id="in-modal-title" style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
            Настройки уведомлений
          </h2>
          <button
            onClick={onClose}
            aria-label="Закрыть"
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: 20, color: '#5e6c84', lineHeight: 1,
            }}
          >×</button>
        </div>

        <div style={{ display: 'flex', borderBottom: '1px solid #dfe1e6', padding: '0 20px' }}>
          {[['settings', 'Настройки'], ['delegation', 'Делегирование']].map(([id, label]) => (
            <button key={id} onClick={() => setTab(id)} style={{
              background: 'none', border: 'none', cursor: 'pointer',
              padding: '10px 12px', fontSize: 14, fontWeight: 500,
              color: tab === id ? '#0052cc' : '#5e6c84',
              borderBottom: tab === id ? '2px solid #0052cc' : '2px solid transparent',
              marginBottom: -1,
            }}>{label}</button>
          ))}
        </div>

        <div style={{ padding: 20, overflowY: 'auto', flex: 1 }}>
          {renderBody()}
        </div>
      </div>
    </>
  );
}

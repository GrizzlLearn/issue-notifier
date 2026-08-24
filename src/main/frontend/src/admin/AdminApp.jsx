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
      { key: 'mattermost.token', label: 'Bearer-токен', type: 'password', placeholder: '••••••••' },
    ],
  },
  {
    title: 'Telegram',
    fields: [
      { key: 'telegram.enabled', label: 'Включить канал', type: 'checkbox' },
      { key: 'telegram.botToken', label: 'Токен бота', type: 'password', placeholder: '123456:ABC-DEF…' },
    ],
  },
];

export default function AdminApp() {
  const [values, setValues] = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);
  const isSavingRef = useRef(false);
  const successTimerRef = useRef(null);

  useEffect(() => {
    getAdminSettings()
      .then(data => { setValues(data); setLoading(false); })
      .catch(e => { setError(e.message); setLoading(false); });
    return () => clearTimeout(successTimerRef.current);
  }, []);

  async function handleSave() {
    if (isSavingRef.current) return;
    isSavingRef.current = true;
    setSaving(true); setError(null); setSuccess(false);
    try {
      await saveAdminSettings(values);
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

  if (loading) return <div className="aui-message">Загрузка…</div>;

  return (
    <div style={{ maxWidth: 600 }}>
      <h2>Настройки Issue Notifier</h2>

      {error && <div className="aui-message aui-message-error" style={{ marginBottom: 16 }}>{error}</div>}
      {success && <div className="aui-message aui-message-success" style={{ marginBottom: 16 }}>Сохранено</div>}

      {SECTIONS.map(section => (
        <fieldset key={section.title} style={{
          border: '1px solid #dfe1e6', borderRadius: 3,
          padding: '16px 20px', marginBottom: 20,
        }}>
          <legend style={{ fontWeight: 600, padding: '0 8px', color: '#172b4d' }}>
            {section.title}
          </legend>

          {section.fields.map(field => (
            <div key={field.key} className="field-group" style={{ marginBottom: 12 }}>
              {field.type === 'checkbox' ? (
                <label>
                  <input
                    type="checkbox"
                    // REST API хранит все настройки как Map<String, String>,
                    // поэтому boolean передаётся строкой "true"/"false"
                    checked={values[field.key] === 'true'}
                    onChange={e => setValue(field.key, e.target.checked ? 'true' : 'false')}
                    style={{ marginRight: 6 }}
                  />
                  {field.label}
                </label>
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
                    autoComplete={field.type === 'password' ? 'new-password' : undefined}
                  />
                </>
              )}
            </div>
          ))}
        </fieldset>
      ))}

      <div style={{ marginTop: 8 }}>
        <button className="aui-button aui-button-primary" onClick={handleSave} disabled={saving}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
      </div>
    </div>
  );
}

import React, { useState, useEffect, useRef } from 'react';
import { getAdminSettings, saveAdminSettings, getContexts, saveContexts } from '../api';
import { PAGE_DATA, DEFAULT_CONTEXT } from './adminData';
import ChannelsTab, { SECTIONS } from './ChannelsTab';
import ContextsTab from './ContextsTab';
import ActionsTab from './ActionsTab';
import DiagnosticsTab from './DiagnosticsTab';

// Проверяем шаблоны до отправки: сервер отвечает 400 с именем ключа вроде
// action.closed.template.mattermost, а поле при этом может быть на другой вкладке.
function templateErrors(values) {
  const errors = {};
  (PAGE_DATA.actions || []).forEach(action => {
    (action.channels || []).forEach(ch => {
      [ch.templateKey, ch.templateKeyNoText, ch.subjectKey].filter(Boolean).forEach(key => {
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
  // каналы, по которым проверка прошла в этом сеансе: включить канал без неё
  // нельзя, иначе настройку сохраняют «на глаз» и уведомления молча не уходят
  const [testedChannels, setTestedChannels] = useState([]);
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
    // Пустое нажатие «Добавить контекст» не блокирует сохранение других вкладок.
    const contextsToSave = contexts.filter(c => !(c.id.startsWith('new-')
      && !(c.name || '').trim()
      && !(c.projects || []).length && !(c.categories || []).length
      && !Object.keys(c.actions || {}).length && !Object.keys(c.closedStatuses || {}).length));
    const nameless = contextsToSave.find(c => c.id !== DEFAULT_CONTEXT && !(c.name || '').trim());
    if (nameless) {
      setTab('projects');
      setError('У контекста не задано название — вкладка «Контекст проектов».');
      return;
    }
    // проверяем только те каналы, которые включают прямо сейчас: уже включённый
    // и сохранённый канал не должен требовать проверки на каждую правку
    const untested = SECTIONS.filter(section => section.channel
      && values[`${section.channel.toLowerCase()}.enabled`] === 'true'
      && saved[`${section.channel.toLowerCase()}.enabled`] !== 'true'
      && !testedChannels.includes(section.channel));
    if (untested.length > 0) {
      setError(`Отправьте проверку перед включением: ${untested.map(s => s.title).join(', ')}.`);
      return;
    }
    setSaving(true); setError(null); setSuccess(false);
    try {
      // контексты первыми: они валидируются на сервере строже, и при отказе
      // настройки каналов не должны уехать половиной
      if (contextsDirty) {
        await saveContexts(contextsToSave);
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

  function handleCancel() {
    setValues(saved);
    setContexts(JSON.parse(savedContexts));
    setTestedChannels([]);
    setError(null);
    setSuccess(false);
    clearTimeout(successTimerRef.current);
  }

  if (loading) return <div className="in-loading">Загрузка…</div>;

  // порядок совпадает со сценарием настройки: подключить канал, отметить
  // проекты, включить действия
  const tabs = [['channels', 'Каналы'], ['projects', 'Контекст проектов'], ['actions', 'Действия'],
    ['diagnostics', 'Диагностика']];

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

        <fieldset disabled={saving} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}
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
          {tab === 'diagnostics' && <DiagnosticsTab values={values} setValue={setValue} />}
          {tab === 'channels' && (
            <ChannelsTab values={values} setValue={setValue}
                         onTested={channel => setTestedChannels(prev =>
                           prev.includes(channel) ? prev : [...prev, channel])} />
          )}
        </fieldset>
      </div>

      {/* строка действий липнет к низу: страница длинная, иначе баннер и кнопка
          оказываются в разных концах экрана */}
      <div className="in-admin-actions">
        <button type="button" className="aui-button aui-button-primary" onClick={handleSave}
                disabled={saving || !dirty}>
          {saving ? 'Сохранение…' : 'Сохранить'}
        </button>
        <button type="button" className="aui-button" onClick={handleCancel}
                disabled={saving || !dirty || !savedContexts}>
          Отменить изменения
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

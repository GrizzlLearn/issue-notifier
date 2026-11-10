import React, { useEffect, useState } from 'react';
import { getFailedNotifications } from '../api';

const time = value => value == null ? '—' : new Date(value).toLocaleString('ru-RU');

export function FailedNotificationsTable({ items, nextBeforeId }) {
  if (items.length === 0) return <p>{nextBeforeId != null
    ? 'На этой странице нет доступных событий с ошибками.'
    : 'Нет доступных для просмотра ошибок отправки.'}</p>;
  return (
    <div style={{ overflowX: 'auto' }}>
      <table className="aui">
        <thead><tr>
          <th scope="col">Задача</th><th scope="col">Событие и контекст</th>
          <th scope="col">Время ошибки</th><th scope="col">Неудачных попыток</th>
          <th scope="col">Следующая попытка</th><th scope="col">Причина</th>
        </tr></thead>
        <tbody>{items.map(item => (
          <tr key={item.id}>
            <td>{item.issueUrl
              ? <a href={item.issueUrl}>{item.issueKey || item.issueId}</a>
              : item.issueKey || item.issueId || '—'}</td>
            <td>{item.event}<div className="description">{item.context}</div></td>
            <td>{time(item.lastFailedAt)}</td><td>{item.attempts}</td>
            <td>{time(item.nextAttemptAt)}</td>
            <td style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{item.error || '—'}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

export default function FailedNotificationsTab() {
  const [pages, setPages] = useState([null]);
  const [refresh, setRefresh] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const beforeId = pages[pages.length - 1];

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setData(null);
    getFailedNotifications(beforeId, controller.signal)
      .then(result => { if (!controller.signal.aborted) setData(result); })
      .catch(e => { if (!controller.signal.aborted) setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [beforeId, refresh]);

  function refreshNewest() {
    setLoading(true);
    setPages([null]);
    setRefresh(value => value + 1);
  }

  return (
    <div>
      <p>Здесь показаны события с ошибками, ожидающие повторной отправки. Одно событие может
        включать несколько получателей и каналов. После успешной обработки оно исчезнет из списка.
        При повторе уже подтверждённые отправки в каналы пропускаются. Дубль возможен,
        если Jira остановилась после отправки до сохранения результата.</p>
      <p>Найдите нужную задачу и событие, затем посмотрите столбец «Причина»: в нём указаны
        действие, канал, получатель и ошибка. Исправьте причину сбоя — плагин повторит обработку
        автоматически, время указано в столбце «Следующая попытка». Нажмите «Обновить», чтобы
        увидеть актуальные данные с первой страницы; кнопки «Назад» и «Далее» переключают страницы.
        Это список текущих проблем, а не история всех отправок.</p>
      <button type="button" className="aui-button" disabled={loading}
              onClick={refreshNewest}>Обновить</button>
      <div aria-live="polite" aria-busy={loading}>
        {loading && <p>Загрузка…</p>}
        {error && <p className="aui-message aui-message-error">{error}</p>}
        {!loading && data && <FailedNotificationsTable items={data.items} nextBeforeId={data.nextBeforeId} />}
      </div>
      <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
        <button type="button" className="aui-button" disabled={loading || pages.length === 1}
                onClick={() => { setLoading(true); setPages(previous => previous.slice(0, -1)); }}>Назад</button>
        <button type="button" className="aui-button"
                disabled={loading || data?.nextBeforeId == null}
                onClick={() => { setLoading(true); setPages(previous => [...previous, data.nextBeforeId]); }}>Далее</button>
      </div>
    </div>
  );
}

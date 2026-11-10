export const apiBase = () => {
  const ctx = window.AJS ? AJS.contextPath() : '';
  return `${ctx}/rest/issue-notifier/1`;
};

const mutationHeaders = {
  'Content-Type': 'application/json',
  'X-Atlassian-Token': 'no-check',
};

// Бэкенд отдаёт ошибки как {"error":"..."} — показываем текст, а не сырой JSON.
// При истёкшей сессии Jira возвращает HTML логин-страницы: её в баннер пускать нельзя.
async function checkOk(resp) {
  if (resp.ok) return resp;
  if (resp.status === 401 || resp.status === 403) {
    throw new Error('Нет доступа: возможно, сессия истекла — обновите страницу.');
  }
  const text = await resp.text().catch(() => '');
  let message = text;
  try {
    message = JSON.parse(text).error || text;
  } catch (e) {
    // не JSON — оставляем как есть, но HTML-страницу целиком не показываем
    if (text.trimStart().startsWith('<') || text.length > 300) message = '';
  }
  throw new Error(message || resp.statusText || 'Ошибка запроса');
}

async function request(path, { method = 'GET', data, headers, signal } = {}) {
  return checkOk(await fetch(`${apiBase()}${path}`, {
    method,
    credentials: 'same-origin',
    signal,
    headers: method === 'GET' ? headers
      : method === 'DELETE' ? { 'X-Atlassian-Token': 'no-check', ...headers }
        : { ...mutationHeaders, ...headers },
    body: data === undefined ? undefined : JSON.stringify(data),
  }));
}

async function getJson(path, signal) {
  return (await request(path, { signal })).json();
}

export function getUserSettings(signal) {
  return getJson('/user/settings', signal);
}

export async function saveUserSettings(data) {
  await request('/user/settings', { method: 'PUT', data });
}

export function getDelegation(signal) {
  return getJson('/user/delegation', signal);
}

export async function saveDelegation(data) {
  await request('/user/delegation', { method: 'PUT', data });
}

export async function removeDelegation() {
  await request('/user/delegation', { method: 'DELETE' });
}

// Пикеры пропускают HTTP-ошибки, но сетевые ошибки и отмена запроса доходят до вызывающего.
async function resolveLabel(type, key, signal) {
  const resp = await fetch(`${apiBase()}/${type}/${encodeURIComponent(key)}`, { credentials: 'same-origin', signal });
  return resp.ok ? (await resp.json()).label : null;
}

export function resolveProject(key, signal) {
  return resolveLabel('projects', key, signal);
}

export function resolveUser(key, signal) {
  return resolveLabel('users', key, signal);
}

export function getAdminSettings(signal) {
  return getJson('/admin/settings', signal);
}

export async function saveAdminSettings(data) {
  await request('/admin/settings', { method: 'PUT', data });
}

// Контекстам нужен Response: вместе с телом читаем ETag для защиты от перезаписи.
export async function getContexts(signal) {
  const resp = await request('/admin/contexts', { signal });
  return { contexts: await resp.json(), etag: resp.headers.get('ETag') };
}

export async function saveContexts(contexts, etag) {
  const resp = await request('/admin/contexts', {
    method: 'PUT', data: contexts, headers: { 'If-Match': etag },
  });
  return { contexts: await resp.json(), etag: resp.headers.get('ETag') };
}

// Проверка использует несохранённые значения формы.
export async function testChannel(channel, settings, recipientType, recipient) {
  const resp = await request('/admin/settings/test', {
    method: 'POST', data: { channel, settings, recipientType, recipient },
  });
  return (await resp.json()).message;
}

export function getFailedNotifications(beforeId, signal) {
  const query = beforeId == null ? '' : `?beforeId=${encodeURIComponent(beforeId)}`;
  return getJson(`/admin/failed-notifications${query}`, signal);
}

export async function retryFailedNotification(id) {
  await request(`/admin/failed-notifications/${encodeURIComponent(id)}/retry`, { method: 'POST' });
}

export async function discardFailedNotification(id) {
  await request(`/admin/failed-notifications/${encodeURIComponent(id)}/discard`, { method: 'POST' });
}
export function getIntakeDiagnostics(signal) {
  return getJson('/admin/intake-diagnostics', signal);
}

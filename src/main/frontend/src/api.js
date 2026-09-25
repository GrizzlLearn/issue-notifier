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

export async function getUserSettings(signal) {
  const resp = await fetch(`${apiBase()}/user/settings`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveUserSettings(data) {
  await checkOk(await fetch(`${apiBase()}/user/settings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

export async function getDelegation(signal) {
  const resp = await fetch(`${apiBase()}/user/delegation`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveDelegation(data) {
  await checkOk(await fetch(`${apiBase()}/user/delegation`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

export async function removeDelegation() {
  await checkOk(await fetch(`${apiBase()}/user/delegation`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: { 'X-Atlassian-Token': 'no-check' },
  }));
}

// Резолв уже сохранённых ключей (проект/пользователь) в человекочитаемые лейблы для
// пред-заполнения пикеров — через собственный REST (см. ProjectPickerResource/UserPickerResource),
// фронтенд не ходит в REST API самой Jira напрямую.
export async function resolveProject(key, signal) {
  const resp = await fetch(`${apiBase()}/projects/${encodeURIComponent(key)}`, { credentials: 'same-origin', signal });
  if (!resp.ok) return null;
  return (await resp.json()).label;
}

export async function resolveUser(key, signal) {
  const resp = await fetch(`${apiBase()}/users/${encodeURIComponent(key)}`, { credentials: 'same-origin', signal });
  if (!resp.ok) return null;
  return (await resp.json()).label;
}

export async function getAdminSettings(signal) {
  const resp = await fetch(`${apiBase()}/admin/settings`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveAdminSettings(data) {
  await checkOk(await fetch(`${apiBase()}/admin/settings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

// Проверочная отправка: отправляем значения прямо из формы, ещё не сохранённые,
// чтобы админ проверил введённый токен до записи в настройки.
export async function testChannel(channel, settings) {
  const resp = await fetch(`${apiBase()}/admin/settings/test`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify({ channel, settings }),
  });
  await checkOk(resp);
  return (await resp.json()).message;
}

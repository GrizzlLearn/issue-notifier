export const apiBase = () => {
  const ctx = window.AJS ? AJS.contextPath() : '';
  return `${ctx}/rest/issue-notifier/1`;
};

const base = apiBase;

const mutationHeaders = {
  'Content-Type': 'application/json',
  'X-Atlassian-Token': 'no-check',
};

async function checkOk(resp) {
  if (!resp.ok) {
    const text = await resp.text().catch(() => resp.statusText);
    throw new Error(text || resp.statusText);
  }
  return resp;
}

export async function getUserSettings(signal) {
  const resp = await fetch(`${base()}/user/settings`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveUserSettings(data) {
  await checkOk(await fetch(`${base()}/user/settings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

export async function getDelegation(signal) {
  const resp = await fetch(`${base()}/user/delegation`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveDelegation(data) {
  await checkOk(await fetch(`${base()}/user/delegation`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

export async function removeDelegation() {
  await checkOk(await fetch(`${base()}/user/delegation`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: { 'X-Atlassian-Token': 'no-check' },
  }));
}

// Резолв уже сохранённых ключей (проект/пользователь) в человекочитаемые лейблы для
// пред-заполнения пикеров — через собственный REST (см. ProjectPickerResource/UserPickerResource),
// фронтенд не ходит в REST API самой Jira напрямую.
export async function resolveProject(key, signal) {
  const resp = await fetch(`${base()}/projects/${encodeURIComponent(key)}`, { credentials: 'same-origin', signal });
  if (!resp.ok) return null;
  return (await resp.json()).label;
}

export async function resolveUser(key, signal) {
  const resp = await fetch(`${base()}/users/${encodeURIComponent(key)}`, { credentials: 'same-origin', signal });
  if (!resp.ok) return null;
  return (await resp.json()).label;
}

export async function getAdminSettings(signal) {
  const resp = await fetch(`${base()}/admin/settings`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function saveAdminSettings(data) {
  await checkOk(await fetch(`${base()}/admin/settings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: mutationHeaders,
    body: JSON.stringify(data),
  }));
}

export async function getSdProjects(signal) {
  const resp = await fetch(`${base()}/admin/sd-projects`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

export async function getActions(signal) {
  const resp = await fetch(`${base()}/admin/actions`, { credentials: 'same-origin', signal });
  await checkOk(resp);
  return resp.json();
}

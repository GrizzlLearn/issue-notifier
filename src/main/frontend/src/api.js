const base = () => {
  const ctx = window.AJS ? AJS.contextPath() : '';
  return `${ctx}/rest/issue-notifier/1`;
};

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
  const dto = await resp.json();
  return dto.toUserKey ? dto : null;
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

const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('API keeps request options, empty responses and picker error handling', async () => {
  const calls = [];
  let response = { ok: true, json: async () => { throw new Error('empty body'); } };
  const api = { window: {}, fetch: async (url, options) => {
    calls.push({ url, options });
    return response;
  } };
  vm.createContext(api);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/api.js'), 'utf8').replace(/export /g, ''), api);
  assert.equal(await api.saveUserSettings({ channels: [] }), undefined);
  assert.equal(calls[0].options.method, 'PUT');
  assert.equal(calls[0].options.body, '{"channels":[]}');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.headers['X-Atlassian-Token'], 'no-check');
  await api.removeDelegation();
  assert.equal(calls[1].options.method, 'DELETE');
  assert.equal(calls[1].options.body, undefined);
  assert.equal(calls[1].options.headers['Content-Type'], undefined);
  response = { ok: true, json: async () => ({ label: 'Project' }) };
  const signal = new AbortController().signal;
  assert.equal((await api.getUserSettings(signal)).label, 'Project');
  assert.equal(calls[2].options.signal, signal);
  assert.equal(calls[2].options.headers, undefined);
  assert.equal(await api.resolveProject('A/B', signal), 'Project');
  assert.equal(calls[3].url, '/rest/issue-notifier/1/projects/A%2FB');
  response = { ok: false, status: 403 };
  assert.equal(await api.resolveUser('user'), null);
  await assert.rejects(api.getAdminSettings(), /Нет доступа/);
  const aborted = new Error('aborted');
  api.fetch = async () => { throw aborted; };
  await assert.rejects(api.resolveUser('user', signal), error => error === aborted);
});

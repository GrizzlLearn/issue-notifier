const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

async function main() {
  const requests = [];
  let status = 200;
  const api = {
    window: {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: status === 200, status, headers: { get: () => '"v2"' },
        json: async () => [{ id: 'saved' }],
        text: async () => JSON.stringify({ error: 'Контексты изменены другим администратором' }) };
    },
  };
  vm.createContext(api);
  vm.runInContext(read('api.js').replace(/export /g, ''), api);
  assert.equal((await api.getContexts()).etag, '"v2"');
  const saved = await api.saveContexts([{ id: 'new' }], '"v1"');
  assert.equal(saved.contexts[0].id, 'saved');
  assert.equal(requests[1].options.headers['If-Match'], '"v1"');
  assert.equal(requests.length, 2, 'PUT response must not require GET');
  status = 409;
  await assert.rejects(api.saveContexts([], '"v1"'), /другим администратором/);

  const app = read('admin/AdminApp.jsx');
  const handler = app.slice(app.indexOf('  async function handleSave()'), app.indexOf('  function setValue('));
  async function scenario(conflict) {
    const events = [];
    const state = {
      errorKeys: [], contexts: [{ id: 'default' }], DEFAULT_CONTEXT: 'default',
      SECTIONS: [], values: { setting: 'changed' }, saved: {}, testedChannels: [],
      contextsDirty: true, contextsEtag: '"old"', payload: { setting: 'changed' },
      setSaving() {}, setSuccess() {}, setTab() {},
      setError: value => { state.error = value; },
      setContexts: value => { events.push('contexts'); state.contexts = value; },
      setSavedContexts: value => { events.push('baseline'); state.baseline = value; },
      setContextsEtag: value => { state.etag = value; },
      setValues: () => assert.fail('failed settings must retain edits'),
      setSaved: () => assert.fail('failed settings must remain dirty'),
      saveContexts: async (_, etag) => {
        assert.equal(etag, '"old"');
        if (conflict) throw new Error('conflict');
        return { contexts: [{ id: 'canonical' }], etag: '"new"' };
      },
      saveAdminSettings: async () => { events.push('settings'); throw new Error('failed'); },
    };
    vm.createContext(state);
    vm.runInContext(handler, state);
    await state.handleSave();
    if (conflict) {
      assert.deepEqual(events, []);
      assert.equal(state.error, 'conflict');
    } else {
      assert.deepEqual(events, ['contexts', 'baseline', 'settings']);
      assert.equal(state.etag, '"new"');
      assert.match(state.error, /^Контексты сохранены\. Остальные настройки не удалось сохранить: failed$/);
    }
  }
  await scenario(false);
  await scenario(true);
  console.log('contextsSave: ETag, canonical PUT response, conflict and partial-save state passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });

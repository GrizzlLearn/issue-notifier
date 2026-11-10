const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const frontend = path.join(__dirname, '..');
const React = require('../node_modules/react');
const { renderToStaticMarkup } = require('../node_modules/react-dom/server');
const babel = require('../node_modules/@babel/core');

async function main() {
  const calls = [];
  const signal = new AbortController().signal;
  const api = { window: {}, fetch: async (url, options) => {
    calls.push({ url, options });
    return { ok: true, json: async () => ({ items: [], nextBeforeId: null }) };
  } };
  vm.createContext(api);
  vm.runInContext(fs.readFileSync(path.join(frontend, 'src/api.js'), 'utf8').replace(/export /g, ''), api);
  await api.getFailedNotifications(null, signal);
  await api.getFailedNotifications(42, signal);
  assert.equal(calls[0].url, '/rest/issue-notifier/1/admin/failed-notifications');
  assert.equal(calls[1].url, '/rest/issue-notifier/1/admin/failed-notifications?beforeId=42');
  assert.equal(calls[1].options.signal, signal);
  assert.equal(calls[1].options.credentials, 'same-origin');
  await api.retryFailedNotification(42);
  assert.equal(calls[2].url, '/rest/issue-notifier/1/admin/failed-notifications/42/retry');
  assert.equal(calls[2].options.method, 'POST');
  assert.equal(calls[2].options.credentials, 'same-origin');
  await api.discardFailedNotification(42);
  assert.equal(calls[3].url, '/rest/issue-notifier/1/admin/failed-notifications/42/discard');
  assert.equal(calls[3].options.method, 'POST');
  await api.getIntakeDiagnostics(signal);
  assert.equal(calls[4].url, '/rest/issue-notifier/1/admin/intake-diagnostics');
  const source = fs.readFileSync(path.join(frontend, 'src/admin/FailedNotificationsTab.jsx'), 'utf8');
  const refreshHandler = source.slice(source.indexOf('  function refreshNewest()'), source.indexOf('\n  return (', source.indexOf('  function refreshNewest()')));
  const refreshState = { setLoading: value => assert.equal(value, true),
    setPages: value => assert.equal(JSON.stringify(value), '[null]'),
    setRefresh: update => assert.equal(update(5), 6) };
  vm.createContext(refreshState);
  vm.runInContext(refreshHandler, refreshState);
  refreshState.refreshNewest();
  const compiled = babel.transformFileSync(path.join(frontend, 'src/admin/FailedNotificationsTab.jsx'), {
    configFile: false, babelrc: false,
    presets: [require.resolve('../node_modules/@babel/preset-env'), require.resolve('../node_modules/@babel/preset-react')],
  }).code;
  const module = { exports: {} };
  vm.runInNewContext(compiled, {
    exports: module.exports, module,
    require: name => name === 'react' ? React : {},
  });
  const { FailedNotificationsTable, default: Tab } = module.exports;
  assert.match(renderToStaticMarkup(React.createElement(FailedNotificationsTable, { items: [] })), /Нет доступных для просмотра ошибок отправки/);
  assert.match(renderToStaticMarkup(React.createElement(FailedNotificationsTable, { items: [], nextBeforeId: 7 })), /На этой странице нет доступных/);
  const html = renderToStaticMarkup(React.createElement(FailedNotificationsTable, { items: [{
    id: 1, issueId: 23, issueKey: 'PROJ-23', issueUrl: '/browse/PROJ-23', event: 'Комментарий',
    context: '<b>Контекст</b>', error: '<script>secret</script>\nОшибка канала', attempts: 2,
    lastFailedAt: null, nextAttemptAt: null,
  }] }));
  assert.match(html, /href="\/browse\/PROJ-23"/);
  assert.match(html, /&lt;script&gt;secret&lt;\/script&gt;/);
  assert.match(html, /&lt;b&gt;Контекст&lt;\/b&gt;/);
  assert.match(html, /white-space:pre-wrap/);
  assert.doesNotMatch(html, /<script>/);
  const quarantined = renderToStaticMarkup(React.createElement(FailedNotificationsTable, { items: [{ id: 2, quarantinedAt: 123, attempts: 1 }], onDiscard: () => {} }));
  assert.match(quarantined, /Карантин: автоматический повтор остановлен/);
  assert.match(quarantined, /Удалить из карантина/);
  const initial = renderToStaticMarkup(React.createElement(Tab));
  assert.match(initial, /Загрузка…/);
  assert.match(initial, /уже подтверждённые отправки в каналы пропускаются/);
  assert.match(initial, /disabled=""/);
  console.log('failedNotifications: endpoint, signal, empty/loading, task link and escaped multiline errors passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });

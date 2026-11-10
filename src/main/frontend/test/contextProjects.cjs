const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const projects = [
  { value: 'A', category: '1' }, { value: 'B', category: '1' },
  { value: 'C', category: '2' }, { value: 'D' },
];
const contexts = [
  { id: 'category', categories: ['1'] },
  { id: 'explicit', projects: ['A'] },
  { id: 'default' },
];
const source = fs.readFileSync(path.join(__dirname, '../src/admin/adminData.js'), 'utf8')
  .replace(/export /g, '');
const sandbox = { window: { ISSUE_NOTIFIER_DATA: { projects } } };
vm.createContext(sandbox);
vm.runInContext(source, sandbox);
const keys = context => Array.from(sandbox.contextProjects(context, contexts));
assert.deepEqual(keys(contexts[0]), ['B']);
assert.deepEqual(keys(contexts[1]), ['A']);
assert.deepEqual(keys(contexts[2]), ['C', 'D']);
assert.deepEqual(Array.from(sandbox.contextProjects({ id: 'default' }, [{ id: 'default' }])),
  ['A', 'B', 'C', 'D']);
console.log('contextProjects: explicit precedence and default complement passed');

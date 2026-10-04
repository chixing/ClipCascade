const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const identityPath = require.resolve('../src/DeviceIdentity');

function restart() {
  delete require.cache[identityPath];
  return require(identityPath);
}

function storage() {
  const values = new Map();
  return { values, writes: 0,
    get: async key => values.get(key) ?? null,
    async set(key, value) { this.writes++; values.set(key, value); },
  };
}

test('reconnects and app restarts reuse one persisted ID without touching other data', async () => {
  const saved = storage();
  saved.values.set('unrelated-setting', 'synthetic opaque setting');
  const initial = restart();
  const ids = await Promise.all(Array.from({ length: 8 }, () => initial.getOrCreateDeviceId(saved)));
  assert.equal(new Set(ids).size, 1);
  assert.equal(saved.writes, 1);
  assert.match(ids[0], /^mobile-[0-9a-f-]+$/);
  assert.ok(ids[0].length <= 64);
  const restarted = await restart().getOrCreateDeviceId(saved);
  assert.equal(restarted, ids[0]);
  assert.equal(saved.writes, 1);
  assert.equal(saved.values.get('unrelated-setting'), 'synthetic opaque setting');
});

test('different installations generate distinct IDs', async () => {
  const first = await restart().getOrCreateDeviceId(storage());
  const second = await restart().getOrCreateDeviceId(storage());
  assert.notEqual(first, second);
});

test('failed persistence rejects the connection and allows a later retry', async () => {
  const saved = storage();
  let fail = true;
  saved.set = async (key, value) => {
    if (fail) throw new Error('synthetic storage failure');
    saved.values.set(key, value);
  };
  const identity = restart();
  await assert.rejects(identity.getOrCreateDeviceId(saved), /synthetic storage failure/);
  fail = false;
  const retried = await identity.getOrCreateDeviceId(saved);
  assert.equal(retried, saved.values.get('clipcascade_device_id'));
});

test('P2P URL replaces an old ID while preserving unrelated encoded query values', () => {
  const { withDeviceIdQuery } = restart();
  assert.equal(withDeviceIdQuery('wss://example.invalid/p2psignaling', 'mobile-synthetic'),
    'wss://example.invalid/p2psignaling?deviceId=mobile-synthetic');
  assert.equal(withDeviceIdQuery('wss://example.invalid/p2psignaling?other=a%2Bb&deviceId=old&flag=1', "synthetic'&id"),
    "wss://example.invalid/p2psignaling?other=a%2Bb&flag=1&deviceId=synthetic'%26id");
});

test('clearing account/settings data retains the installation ID', async () => {
  const { DEVICE_ID_KEY } = restart();
  const entries = new Map([
    [DEVICE_ID_KEY, 'synthetic-installation-id'],
    ['username', 'synthetic-account'],
    ['password', 'synthetic-credential'],
    ['hashed_password', 'synthetic-encryption-key'],
    ['csrf_token', 'synthetic-csrf'],
    ['cipher_enabled', 'true'],
    ['wsIsRunning', 'true'],
  ]);
  const AsyncStorage = {
    getAllKeys: async () => [...entries.keys()],
    multiRemove: async keys => keys.forEach(key => entries.delete(key)),
  };
  // Evaluate the real storage helpers with their imported dependency replaced.
  const source = readFileSync(resolve(__dirname, '../src/AsyncStorageManagement.js'), 'utf8')
    .replace(/^import .*;.*$/gm, '')
    .replace(/export const /g, 'const ');
  const context = { AsyncStorage, DEVICE_ID_KEY };
  vm.runInNewContext(source + '\nthis.clearAsyncStorage = clearAsyncStorage;', context);
  await context.clearAsyncStorage();
  assert.equal(entries.size, 1);
  assert.equal(entries.get(DEVICE_ID_KEY), 'synthetic-installation-id');
});

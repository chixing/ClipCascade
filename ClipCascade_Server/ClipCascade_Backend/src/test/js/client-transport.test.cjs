// Execute real transport functions without a DOM, account, or live connection.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {randomUUID} = require('node:crypto');
const assets = path.resolve(__dirname, '../../main/resources/static/assets/js');
const source = fs.readFileSync(path.join(assets, 'main.js'), 'utf8');
const binary = require(path.join(assets, 'binary-protocol.js'));
function functionSource(name) {
  const start = source.search(new RegExp(`(?:async )?function ${name}\\(`));
  assert.notEqual(start, -1);
  const next = source.indexOf('\n}', start);
  return source.slice(start, next + 2);
}
function setup(plan) {
  const messages = [];
  const context = vm.createContext({TextEncoder, TextDecoder, Uint8Array, console,
    window: {crypto: {randomUUID}}, ClipCascadeBinary: binary,
    getWebDeviceInfo: () => ({deviceId: 'browser-id', deviceType: 'web', osInfo: 'Linux', friendlyName: 'Browser'}),
    ENDPOINTS: {websocketSend: '/app/cliptext'}, clipboardSendGeneration: 0,
    stompClient: {connected: true, publish: message => messages.push(message)},
    binaryRelayRecipients: async () => plan});
  for (const name of ['newHistoryTransferId', 'textClipboardMessage', 'sendTextToServerStomp', 'disconnectStomp']) {
    vm.runInContext(functionSource(name), context);
  }
  return {context, messages, send: text => context.sendTextToServerStomp(text)};
}
test('mixed recipients share identity, byte size, and one fresh history transfer UUID', async () => {
  const {context, messages, send} = setup({binary: ['new'], legacy: ['old']});
  await send('héllo');
  assert.equal(messages.length, 2);
  const modern = binary.decodeFrame(messages[0].binaryBody);
  const legacy = JSON.parse(messages[1].body);
  assert.deepEqual(modern.metadata, legacy.metadata);
  assert.equal(legacy.metadata.deviceId, 'browser-id');
  assert.equal(legacy.metadata.contentSizeBytes, 6);
  assert.match(legacy.metadata.historyTransferId, /^[0-9a-f-]{36}$/);
  assert.equal(messages[0].headers['x-clipcascade-recipients'], 'new');
  assert.equal(messages[1].headers['x-clipcascade-recipients'], 'old');
  await send('next');
  assert.notEqual(JSON.parse(messages[3].body).metadata.historyTransferId, legacy.metadata.historyTransferId);
});
test('old server fallback and empty recipients preserve upstream routing', async () => {
  const old = setup(null); await old.send('old');
  assert.equal(old.messages.length, 1);
  assert.equal(JSON.parse(old.messages[0].body).metadata.deviceId, 'browser-id');
  const empty = setup({binary: [], legacy: []}); await empty.send('alone');
  assert.equal(empty.messages.length, 0);
});
test('replacement connection, cancel, and newer update invalidate pending recipient lookup', async () => {
  for (const action of ['replace', 'cancel', 'newer']) {
    const {context, messages, send} = setup(null);
    let resolve;
    context.binaryRelayRecipients = () => new Promise(r => {resolve = r;});
    const pending = send('stale');
    if (action === 'replace') context.stompClient = {connected: true, publish: m => messages.push(m)};
    if (action === 'newer') context.clipboardSendGeneration++;
    if (action === 'cancel') {
      context.stompClient._ccRecipients = {reset() {}};
      context.stompClient.deactivate = () => {};
      context.disconnectStomp();
    }
    resolve({binary: ['new'], legacy: ['old']}); await pending;
    assert.equal(messages.length, 0);
  }
});
test('connection replacement during binary preparation suppresses both representations', async () => {
  const {context, messages, send} = setup({binary: ['new'], legacy: ['old']});
  let resolve, started;
  const preparing = new Promise(r => {started = r;});
  context.ClipCascadeBinary = {...binary, encodeContent: () => new Promise(r => {resolve = r; started();})};
  const pending = send('stale');
  await preparing;
  context.stompClient = {connected: true};
  resolve(await binary.encodeContent('stale', 'text')); await pending;
  assert.equal(messages.length, 0);
});
test('CONNECT combines identity with capability, limit, and no-self-echo headers', () => {
  const {context} = setup(null);
  context.$ = () => ({val: () => 'ws://synthetic.invalid'});
  context.SELECTORS = {brokerUrlInput: '#url'};
  context.ClipCascadeRelay = {RelayRecipients: class {}};
  let options;
  context.StompJs = {Client: class {constructor(value) {options = value;} activate() {}}};
  vm.runInContext(functionSource('connectStomp'), context);
  context.connectStomp(1);
  assert.equal(options.connectHeaders.deviceId, 'browser-id');
  assert.equal(options.connectHeaders['x-clipcascade-binary'], 'ccb1');
  assert.equal(options.connectHeaders['x-clipcascade-recipient-updates'], '1');
  assert.equal(options.connectHeaders['x-clipcascade-receive-limit'], 'unlimited');
  assert.equal(options.connectHeaders['x-clipcascade-no-self-echo'], 'true');
});

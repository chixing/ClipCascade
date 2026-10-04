const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

// Exercise the real template script against the DOM methods used by device
// rendering. No dependency or browser installation is needed for this regression.
class Element {
  constructor(tag) {
    this.tagName = tag;
    this.children = [];
    this.style = {};
    this.listeners = {};
    this.value = '';
    this.classList = { add() {}, remove() {} };
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  addEventListener(type, listener) { this.listeners[type] = listener; }
}

function dashboard(input) {
  const elements = new Map();
  const requests = [];
  const document = {
    createElement: tag => new Element(tag),
    getElementById: id => {
      if (!elements.has(id)) elements.set(id, new Element('div'));
      return elements.get(id);
    },
    addEventListener() {},
    querySelectorAll: () => [],
  };
  const context = vm.createContext({ document, input, console, URLSearchParams,
    confirm: () => true,
    alert: message => assert.fail(message),
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => [] };
    },
  });
  const template = readFileSync(resolve(__dirname, '../../main/resources/templates/dashboard.html'), 'utf8');
  const script = [...template.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
  vm.runInContext(script, context);
  vm.runInContext('devices = input; renderDevices(); populateDeviceFilters();', context);
  return { elements, requests };
}

test('quoted names and IDs remain literal data when rendering and renaming devices', () => {
  const id = 'browser\'"<&';
  const name = `Chi's "Mac" <img src=x onerror=alert(1)>`;
  const { elements } = dashboard([{ id, friendlyName: name, displayName: name,
    deviceType: 'web', osInfo: 'macOS', ipAddress: '<untrusted-address>', online: true }]);
  const cards = elements.get('devices-container').children;
  assert.equal(cards.length, 1);
  const nameElement = cards[0].children.find(child => child.className === 'device-name');
  assert.equal(nameElement.textContent, name);
  assert.equal(nameElement.innerHTML, undefined);
  assert.equal(nameElement.onclick, undefined);
  nameElement.listeners.click();
  assert.equal(elements.get('rename-device-id').value, id);
  assert.equal(elements.get('rename-input').value, name);
  assert.equal(elements.get('filter-device').children[1].value, id);
  assert.equal(elements.get('filter-device').children[1].textContent, name);
});

test('device removal binds the literal ID and encodes the request path', async () => {
  const id = `browser');alert(1);//`;
  const { elements, requests } = dashboard([{ id, displayName: 'Browser', online: true }]);
  const remove = elements.get('devices-container').children[0].children[0];
  let stopped = false;
  await remove.listeners.click({ stopPropagation() { stopped = true; } });
  assert.equal(stopped, true);
  assert.equal(requests[0].url, `/admin/devices/${encodeURIComponent(id)}`);
  assert.equal(requests[0].options.method, 'DELETE');
});

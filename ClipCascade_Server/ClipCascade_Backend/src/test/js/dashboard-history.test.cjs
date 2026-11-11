const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

function dashboard() {
  const elements = new Map();
  const requests = [];
  function element() {
    return { value: '', textContent: '', disabled: false, style: {},
      classList: { add() {}, remove() {} },
      set innerHTML(value) { this.html = value; },
      get innerHTML() {
        return this.html ?? this.textContent.replaceAll('&', '&amp;').replaceAll('<', '&lt;')
          .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
      } };
  }
  const document = { createElement: element, addEventListener() {},
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    }, querySelectorAll: () => [] };
  const page = { content: [{ id: 123, pinned: true, payloadType: 'text',
    payloadPreview: '<script>bad()</script>', deviceName: 'Mac', payloadSize: 1 }],
    totalElements: 51, totalPages: 2 };
  const context = vm.createContext({ document, console, URLSearchParams,
    alert: message => assert.fail(message),
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => page };
    } });
  const template = readFileSync(resolve(__dirname, '../../main/resources/templates/dashboard.html'), 'utf8');
  vm.runInContext([...template.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1], context);
  return { context, elements, requests, document, page };
}

test('history uses Spring Page pagination and renders pinned controls with escaped content', () => {
  const { context, elements, page } = dashboard();
  context.page = page;
  vm.runInContext('renderHistory(page)', context);
  assert.equal(elements.get('btn-next').disabled, false);
  assert.equal(elements.get('btn-prev').disabled, true);
  const html = elements.get('history-tbody').innerHTML;
  assert.match(html, /togglePinned\(123, false\)/);
  assert.match(html, /aria-pressed="true"/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>bad/);
  vm.runInContext('currentPage = 1; updatePagination(page)', context);
  assert.equal(elements.get('btn-next').disabled, true);
});

test('pin updates send JSON and CSRF, and unpinned filter is sent as false', async () => {
  const { context, document, requests } = dashboard();
  document.getElementById('filter-pinned').value = 'false';
  await vm.runInContext('csrfToken="synthetic-token"; togglePinned(123, true)', context);
  assert.equal(requests[0].url, '/admin/clipboard-history/123/pin');
  assert.equal(requests[0].options.method, 'PUT');
  assert.equal(requests[0].options.headers['X-CSRF-TOKEN'], 'synthetic-token');
  assert.equal(requests[0].options.body, '{"pinned":true}');
  assert.match(requests[1].url, /pinned=false/);
});

test('opaque encrypted history has no image probe and cannot copy ciphertext as plaintext', async () => {
  const { context, elements, page } = dashboard();
  page.content[0] = { id: 123, payloadType: 'image', payloadFormat: 'ccb1',
    payload: 'opaque-frame', payloadPreview: '[Encrypted image: 20 B]', payloadSize: 20 };
  context.page = page;
  vm.runInContext('renderHistory(page)', context);
  assert.doesNotMatch(elements.get('history-tbody').innerHTML, /<img/);
  assert.match(elements.get('history-tbody').innerHTML, /Encrypted image/);
  const alerts = [];
  context.alert = message => alerts.push(message);
  context.item = { payloadType: 'text', payloadFormat: 'ccb1', payload: 'opaque-frame' };
  await vm.runInContext('currentViewItem = item; copyToClipboard()', context);
  assert.deepEqual(alerts, ['Encrypted content cannot be copied as plaintext. Use Download.']);
});

test('explicit utf8 history previews Base64-looking text and escapes markup', async () => {
  for (const payload of ['A'.repeat(200), 'A'.repeat(200) + '<script>bad()</script>']) {
    const { context, elements } = dashboard();
    context.fetchAPI = async () => ({ id: 123, payloadType: 'text', payloadFormat: 'utf8',
      payload, createdAt: 0 });
    await vm.runInContext('viewItem(123)', context);
    const body = elements.get('modal-body').innerHTML;
    assert.match(body, /<pre id="modal-text-content">A{200}/);
    assert.doesNotMatch(body, /File preview not available|<script>bad/);
    if (payload.includes('<script>')) assert.match(body, /&lt;script&gt;bad/);
    assert.equal(elements.get('btn-download').style.display, 'none');
  }
});

test('legacy and opaque history keep their existing preview policies', async () => {
  for (const payloadFormat of ['legacy', undefined, 'ccb1']) {
    const { context, elements } = dashboard();
    context.fetchAPI = async () => ({ id: 123, payloadType: 'text', payloadFormat,
      payload: 'A'.repeat(200), createdAt: 0 });
    await vm.runInContext('viewItem(123)', context);
    const body = elements.get('modal-body');
    if (payloadFormat === 'ccb1') {
      assert.match(body.textContent, /Encrypted clipboard content/);
    } else {
      assert.match(body.innerHTML, /File preview not available/);
    }
    assert.equal(elements.get('btn-download').style.display, 'inline-block');
  }
});

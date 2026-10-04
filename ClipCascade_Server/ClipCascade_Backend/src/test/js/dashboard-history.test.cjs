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

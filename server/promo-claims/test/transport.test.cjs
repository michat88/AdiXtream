'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { createEmulatorDatabase } = require('../src/emulator-db.cjs');
const { stringifyExact } = require('../src/exact-json.cjs');
async function setup(t, handler, config = {}) {
  const http = createServer(handler);
  await new Promise(r => http.listen(0, '127.0.0.1', r));
  t.after(() => new Promise(r => { http.close(r); http.closeAllConnections(); }));
  return createEmulatorDatabase({ port: http.address().port, namespace: 'demo-transport-test', ...config });
}
test('transport cannot target a production namespace or non-loopback URL', () => {
  for (const config of [{ port: 9000, namespace: 'production' }, { port: 80, namespace: 'demo-test' },
    { port: 9000, namespace: 'demo-test?auth=anything' }]) assert.throws(() => createEmulatorDatabase(config), /EMULATOR_CONFIG_INVALID/);
});
test('transport preserves exact JSON and requires ETag on read and conditional PUT', async t => {
  const text = '{"large":9223372036854775808,"decimal":0.1234567890123456789}';
  let put = false;
  const db = await setup(t, (req, res) => {
    assert.equal(req.url, '/.json?ns=demo-transport-test'); assert.equal(req.headers.authorization, 'Bearer owner');
    if (req.method === 'GET') { assert.equal(req.headers['x-firebase-etag'], 'true'); res.setHeader('etag', '"ETAG-1"'); res.end(text); }
    else {
      put = true; assert.equal(req.headers['if-match'], '"ETAG-1"');
      let data = ''; req.on('data', chunk => { data += chunk; }); req.on('end', () => { assert.equal(data, text); res.end(text); });
    }
  });
  const read = await db.read(); assert.equal(stringifyExact(read.root), text);
  assert.equal(await db.compareAndSet(read.etag, read.root), true); assert(put);
});
test('HTTP 412 is a conflict; no ordinary PATCH or blind retry occurs', async t => {
  let requests = 0;
  const db = await setup(t, (req, res) => { requests++; assert.equal(req.method, 'PUT'); res.statusCode = 412; res.end('{}'); });
  assert.equal(await db.compareAndSet('"old"', {}), false); assert.equal(requests, 1);
});
test('native unquoted RTDB ETag is passed through unchanged', async t => {
  const db = await setup(t, (req, res) => {
    if (req.method === 'GET') { res.setHeader('etag', 'opaque/firebase+tag='); res.end('{}'); }
    else { assert.equal(req.headers['if-match'], 'opaque/firebase+tag='); res.end('{}'); }
  });
  const snapshot = await db.read(); assert.equal(snapshot.etag, 'opaque/firebase+tag=');
  assert.equal(await db.compareAndSet(snapshot.etag, snapshot.root), true);
});
test('read without an ETag cannot proceed to a blind write', async t => {
  const db = await setup(t, (_req, res) => { res.end('{}'); });
  await assert.rejects(db.read(), e => e.code === 'TEMPORARY_UNAVAILABLE');
});
test('redirects are not followed and write outcome stays unknown', async t => {
  const db = await setup(t, (_req, res) => { res.statusCode = 302; res.setHeader('location', '/other'); res.end(); });
  await assert.rejects(db.read(), e => e.code === 'TEMPORARY_UNAVAILABLE');
  await assert.rejects(db.compareAndSet('"old"', {}), e => e.code === 'UNKNOWN_RESULT');
});
test('response body timeout is covered after headers have arrived', async t => {
  const db = await setup(t, (_req, res) => { res.writeHead(200, { etag: '"old"' }); res.write('{'); }, { timeoutMs: 30 });
  await assert.rejects(db.read(), e => e.code === 'TEMPORARY_UNAVAILABLE');
});
test('oversized snapshots are bounded and no raw body is exposed', async t => {
  const db = await setup(t, (_req, res) => { res.setHeader('etag', '"old"'); res.end('SYNTHETIC_PRIVATE'.repeat(100)); }, { maxBytes: 32 });
  await assert.rejects(db.read(), e => e.code === 'TEMPORARY_UNAVAILABLE' && !e.message.includes('PRIVATE'));
});
test('HTTP 500 or connection loss during a write cannot be reported as success', async t => {
  const db = await setup(t, (_req, res) => { res.statusCode = 500; res.end('SYNTHETIC_PRIVATE'); });
  await assert.rejects(db.compareAndSet('"old"', {}), e => e.code === 'UNKNOWN_RESULT');
});

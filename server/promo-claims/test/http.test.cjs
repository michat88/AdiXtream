'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { createPromoHandler } = require('../src/http.cjs');
const { createClaimService } = require('../src/claims.cjs');
const { NOW, ownerA, ownerB, body, memoryDb } = require('./helpers.cjs');
async function server(t, verifySession) {
  const db = memoryDb();
  const service = createClaimService({ db, clock: () => NOW });
  const http = createServer(createPromoHandler({ service, ...(verifySession ? { verifySession } : {}) }));
  await new Promise(r => http.listen(0, '127.0.0.1', r));
  t.after(() => new Promise(r => { http.close(r); http.closeAllConnections(); }));
  return { db, base: `http://127.0.0.1:${http.address().port}` };
}
const verifier = async headers => headers.authorization === 'Bearer SYNTHETIC-A' ? ownerA :
  headers.authorization === 'Bearer SYNTHETIC-B' ? ownerB : null;
async function post(base, value = body(), options = {}) {
  const response = await fetch(base + (options.path || '/v1/promo/claim'), { method: options.method || 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer SYNTHETIC-A', ...options.headers },
    ...(options.method === 'GET' ? {} : { body: typeof value === 'string' ? value : JSON.stringify(value) }) });
  return { response, result: await response.json() };
}
test('default HTTP authentication denies before database read, including forged owner headers', async t => {
  const { db, base } = await server(t);
  const r = await post(base, body(), { headers: { 'X-Principal-Id': ownerA.principalId, 'X-Verified-Owner': 'true' } });
  assert.equal(r.response.status, 401); assert.equal(r.result.outcome, 'AUTH_REQUIRED'); assert.equal(db.reads, 0);
});
test('HTTP claim and receipt share a stable operation; response is minimal and no-store', async t => {
  const { db, base } = await server(t, verifier);
  const first = await post(base), receipt = await post(base, body(), { path: '/v1/promo/receipt' });
  assert.equal(first.response.status, 200); assert.equal(first.result.outcome, 'APPLIED');
  assert.equal(receipt.result.operationId, first.result.operationId); assert.equal(receipt.result.outcome, 'REPLAYED');
  assert.equal(first.response.headers.get('cache-control'), 'no-store'); assert.equal(db.writes, 1);
  for (const forbidden of ['SYNTHETIC-EXISTING', 'principalId', 'ownerBindings', 'promo_codes', 'unrelated']) assert(!JSON.stringify(first.result).includes(forbidden));
});
test('valid session cannot claim a different owner record', async t => {
  const { db, base } = await server(t, verifier);
  const r = await post(base, body(), { headers: { Authorization: 'Bearer SYNTHETIC-B' } });
  assert.equal(r.response.status, 403); assert.equal(r.result.outcome, 'OWNERSHIP_REQUIRED'); assert.equal(db.writes, 0);
});
test('owner, days or expiry injected in body are rejected instead of trusted', async t => {
  const { db, base } = await server(t, verifier);
  for (const extra of [{ principalId: ownerA.principalId }, { days: 999 }, { expired_at: NOW }]) {
    const r = await post(base, { ...body(), ...extra }); assert.equal(r.response.status, 400);
  }
  assert.equal(db.reads, 0);
});
test('invalid JSON returns request error without internal exception details', async t => {
  const { db, base } = await server(t, verifier);
  const r = await post(base, '{bad JSON');
  assert.equal(r.response.status, 400); assert.equal(r.result.outcome, 'REQUEST_INVALID'); assert.equal(db.reads, 0);
});
test('oversized body and compressed or incorrect media types are rejected', async t => {
  const { db, base } = await server(t, verifier);
  assert.equal((await post(base, 'X'.repeat(5000))).response.status, 413);
  assert.equal((await post(base, body(), { headers: { 'Content-Type': 'text/plain' } })).response.status, 415);
  assert.equal((await post(base, body(), { headers: { 'Content-Encoding': 'gzip' } })).response.status, 415);
  assert.equal(db.reads, 0);
});
test('only exact POST routes are accepted', async t => {
  const { db, base } = await server(t, verifier);
  assert.equal((await post(base, body(), { method: 'GET' })).response.status, 405);
  assert.equal((await post(base, body(), { path: '/v1/promo/claim?deviceId=other' })).response.status, 404);
  assert.equal((await post(base, body(), { path: '/debug' })).response.status, 404);
  assert.equal(db.reads, 0);
});
test('unknown lookup response is retryable and does not declare failure of a prior commit', async t => {
  const { base } = await server(t, verifier);
  const r = await post(base, body(), { path: '/v1/promo/receipt' });
  assert.equal(r.response.status, 503); assert.equal(r.result.outcome, 'UNKNOWN_RESULT');
  assert.equal(r.result.retryable, true); assert.equal(r.response.headers.get('retry-after'), '2');
});
test('verifier exceptions never leak credentials, URLs or exception messages', async t => {
  const { db, base } = await server(t, async () => { throw new Error('SYNTHETIC_PRIVATE_INFORMATION'); });
  const r = await post(base);
  assert.deepEqual(r.result, { contractVersion: 1, outcome: 'TEMPORARY_UNAVAILABLE', retryable: true });
  assert.equal(db.reads, 0);
});

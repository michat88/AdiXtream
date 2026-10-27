'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { setTimeout: delay } = require('node:timers/promises');
const fixture = JSON.parse(fs.readFileSync(path.join(__dirname,
  '../../..', 'app/src/test/resources/adixtream-atomic-promo-v1.json'), 'utf8'));
const rules = require('./fixtures/legacy-client.rules.json');
const port = Number(process.env.ADIXTREAM_TEST_PORT), ns = process.env.ADIXTREAM_TEST_NS;
assert.equal(port, 19002); assert.equal(ns, 'demo-adixtream-promo-v1');
const A = fixture.deviceId, B = '22222222', DAY = 86400000;
const clone = value => JSON.parse(JSON.stringify(value));
async function http(method, pathname, value, privileged = false) {
  const url = new URL(`http://127.0.0.1:${port}${pathname}.json`); url.searchParams.set('ns', ns);
  const response = await fetch(url, { method: method === 'PATCH' ? 'POST' : method,
    headers: { 'Content-Type': 'application/json',
      ...(method === 'PATCH' ? { 'X-HTTP-Method-Override': 'PATCH' } : {}),
      // Literal emulator credential is used only to install fixtures/rules or inspect state.
      ...(privileged ? { Authorization: 'Bearer owner' } : {}) },
    ...(value === undefined ? {} : { body: JSON.stringify(value) }),
    redirect: 'error', signal: AbortSignal.timeout(10000) });
  return { status: response.status, value: JSON.parse(await response.text()) };
}
const state = async () => (await http('GET', '/', undefined, true)).value;
const denied = response => assert([401, 403].includes(response.status));
function seedValue() {
  const promo = { ...fixture.promo, valid_until: Date.now() + 20 * DAY, expired_at: Date.now() + 20 * DAY };
  return { users: { [A]: clone(fixture.user), [B]: clone(fixture.user) },
    promo_codes: { ALPHA: promo, BETA: clone(promo) }, unrelated: { retain: 'synthetic' } };
}
async function seed(value = seedValue()) { assert.equal((await http('PUT', '/', value, true)).status, 200); }
function patch(deviceId = A, code = 'ALPHA') {
  const p = {};
  for (const [key, value] of Object.entries(fixture.patch)) {
    p[key.replace(A, deviceId).replace('ALPHA', code)] = clone(value);
  }
  return p;
}
test.before(async () => { assert.equal((await http('PUT', '/.settings/rules', rules, true)).status, 200); });
test.beforeEach(async () => { await seed(); });
test.after(async () => {
  await http('DELETE', '/', undefined, true);
  await http('PUT', '/.settings/rules', { rules: { '.read': false, '.write': false } }, true);
});

test('active Kotlin payload commits all four fields without an Admin credential', async () => {
  const before = await state(); assert.equal((await http('PATCH', '/', patch())).status, 200);
  const end = await state();
  assert.equal(end.users[A].redeemed_promos.ALPHA, true);
  assert.equal(end.users[A].expired_at, fixture.user.expired_at + 3 * DAY);
  assert.equal(end.users[A].last_update, fixture.patch[`users/${A}/last_update`]);
  assert.equal(end.promo_codes.ALPHA.used_count, 1);
  for (const field of ['code', 'account_type', 'extend_count', 'custom']) {
    assert.deepEqual(end.users[A][field], before.users[A][field]);
  }
  assert.deepEqual(end.users[B], before.users[B]); assert.deepEqual(end.unrelated, before.unrelated);
});
test('duplicate full PATCH cannot consume quota or add a second bonus', async () => {
  assert.equal((await http('PATCH', '/', patch())).status, 200);
  const before = await state(); denied(await http('PATCH', '/', patch()));
  assert.deepEqual(await state(), before);
});
test('one invalid field rejects the entire PATCH including marker and quota', async () => {
  const before = await state(), p = patch(); p[`users/${A}/expired_at`] = 'invalid';
  denied(await http('PATCH', '/', p)); assert.deepEqual(await state(), before);
});
test('last quota contention allows one complete claim and zero partial claims', async () => {
  const f = seedValue(); f.promo_codes.ALPHA.max_quota = 1; await seed(f);
  const responses = await Promise.all([http('PATCH', '/', patch(A)), http('PATCH', '/', patch(B))]);
  assert.equal(responses.filter(r => r.status === 200).length, 1);
  assert.equal(responses.filter(r => [401, 403].includes(r.status)).length, 1);
  const end = await state(); assert.equal(end.promo_codes.ALPHA.used_count, 1);
  const winners = [A, B].filter(id => end.users[id].redeemed_promos?.ALPHA === true);
  assert.equal(winners.length, 1);
  for (const id of [A, B]) assert.equal(end.users[id].expired_at,
    fixture.user.expired_at + (winners.includes(id) ? 3 * DAY : 0));
});
test('two different numeric-expiry promos preserve both bonuses even with stale reads', async () => {
  const r = await Promise.all([http('PATCH', '/', patch(A, 'ALPHA')), http('PATCH', '/', patch(A, 'BETA'))]);
  assert(r.every(x => x.status === 200)); const end = await state();
  assert.equal(end.users[A].expired_at, fixture.user.expired_at + 6 * DAY);
  assert.equal(end.promo_codes.ALPHA.used_count, 1); assert.equal(end.promo_codes.BETA.used_count, 1);
});
test('numeric Admin renewal after client read is preserved by server increment', async () => {
  assert.equal((await http('PUT', `/users/${A}/expired_at`, fixture.now + 40 * DAY, true)).status, 200);
  assert.equal((await http('PATCH', '/', patch())).status, 200);
  assert.equal((await state()).users[A].expired_at, fixture.now + 43 * DAY);
});
test('expired paused or full promo cannot leave a marker or change expiry', async () => {
  for (const changes of [{ valid_until: Date.now() - DAY }, { status: 'nonaktif' }, { used_count: 10 }]) {
    const f = seedValue(); Object.assign(f.promo_codes.ALPHA, changes); await seed(f);
    const before = await state(); denied(await http('PATCH', '/', patch()));
    assert.deepEqual(await state(), before);
  }
});
test('connection lost during an incomplete PATCH body applies none of its paths', async () => {
  const before = await state(), body = JSON.stringify(patch());
  await new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1');
    socket.setTimeout(3000, () => socket.destroy()); socket.on('error', reject); socket.on('close', resolve);
    socket.resume();
    socket.on('connect', () => {
      socket.write(`POST /.json?ns=${ns} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nX-HTTP-Method-Override: PATCH\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n`);
      socket.end(body.slice(0, Math.floor(body.length / 2)));
    });
  });
  await delay(100); assert.deepEqual(await state(), before);
});
test('lost successful reply is recoverable by a read and duplicate retry remains harmless', async () => {
  await http('PATCH', '/', patch()); // Deliberately discard the accepted reply.
  const record = await http('GET', `/users/${A}`);
  assert.equal(record.status, 200); assert.equal(record.value.redeemed_promos.ALPHA, true);
  assert.equal(record.value.expired_at, fixture.user.expired_at + 3 * DAY);
  const before = await state(); denied(await http('PATCH', '/', patch())); assert.deepEqual(await state(), before);
});
test('pre-existing partial marker is retained and never automatically regranted', async () => {
  const f = seedValue(); f.users[A].redeemed_promos = { ALPHA: true }; await seed(f);
  const before = await state(); denied(await http('PATCH', '/', patch())); assert.deepEqual(await state(), before);
});
test('Free or absent record accepts one absolute-expiry atomic claim under existing rules', async () => {
  const f = seedValue(); delete f.users[A]; await seed(f);
  const p = patch(); p[`users/${A}/expired_at`] = fixture.now + 3 * DAY;
  assert.equal((await http('PATCH', '/', p)).status, 200);
  const end = await state(); assert.equal(end.users[A].expired_at, fixture.now + 3 * DAY);
  assert.equal(end.users[A].redeemed_promos.ALPHA, true); assert.equal(end.promo_codes.ALPHA.used_count, 1);
});

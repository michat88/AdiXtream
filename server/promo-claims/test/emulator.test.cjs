'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { fork } = require('node:child_process');
const path = require('node:path');
const { createEmulatorDatabase } = require('../src/emulator-db.cjs');
const { createClaimService, DAY, LEDGER } = require('../src/claims.cjs');
const { parseExact, stringifyExact } = require('../src/exact-json.cjs');
const { NOW, A, B, ownerA, ownerB, fixture, body, firstCasBarrier, number } = require('./helpers.cjs');
const port = Number(process.env.ADIXTREAM_TEST_PORT), ns = process.env.ADIXTREAM_TEST_NS;
assert.equal(port, 19002); assert.equal(ns, 'demo-adixtream-promo-v1');
const db = createEmulatorDatabase({ port, namespace: ns });
async function http(method, pathname, value, privileged = true) {
  const url = new URL(`http://127.0.0.1:${port}${pathname}.json`); url.searchParams.set('ns', ns);
  const r = await fetch(url, { method, headers: { 'Content-Type': 'application/json', ...(privileged ? { Authorization: 'Bearer owner' } : {}) },
    ...(value === undefined ? {} : { body: stringifyExact(value) }), redirect: 'error', signal: AbortSignal.timeout(10000) });
  return { status: r.status, text: await r.text() };
}
async function seed(value = fixture()) { assert.equal((await http('PUT', '/', value)).status, 200); }
const state = async () => (await db.read()).root;
const client = (database = db) => createClaimService({ db: database, clock: () => NOW, maxAttempts: 24, backoff: async () => {} });
test.before(async () => { assert.equal((await http('PUT', '/.settings/rules', { rules: { '.read': false, '.write': false } })).status, 200); });
test.beforeEach(async () => { await seed(); });
test.after(async () => { await http('DELETE', '/'); });
function wrapped(before, after) {
  let conflicts = 0;
  return { read: () => db.read(), get conflicts() { return conflicts; }, async compareAndSet(etag, root) {
    if (before) await before();
    const committed = await db.compareAndSet(etag, root);
    if (!committed) conflicts++;
    if (after) await after(committed);
    return committed;
  } };
}
async function mutate(change) {
  const { root, etag } = await db.read(); change(root); assert.equal(await db.compareAndSet(etag, root), true);
}
function worker(t, message) {
  const process = fork(path.join(__dirname, 'claim-worker.cjs'), [], { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
  const events = [], waiters = [];
  process.on('message', value => { events.push(value); for (const check of [...waiters]) check(); });
  t.after(() => { if (process.exitCode === null && process.signalCode === null) process.kill('SIGKILL'); });
  const next = type => new Promise((resolve, reject) => {
    const timer = setTimeout(() => { reject(new Error('WORKER_EVENT_TIMEOUT')); }, 15000);
    function check() {
      const index = events.findIndex(e => e.event === type);
      if (index < 0) return;
      clearTimeout(timer); waiters.splice(waiters.indexOf(check), 1); resolve(events.splice(index, 1)[0]);
    }
    waiters.push(check); check();
  });
  process.send(message);
  return { process, next };
}
test('real RTDB atomic grant preserves unrelated stored data and publishes one receipt', async () => {
  const before = await state(), result = await client().claim(ownerA, body()), end = await state();
  assert.equal(result.outcome, 'APPLIED'); assert.equal(number(end.users[A].expired_at), NOW + 13 * DAY);
  assert.equal(number(end.promo_codes.ALPHA.used_count), 1); assert.equal(end.users[A].redeemed_promos.ALPHA, true);
  assert.equal(end[LEDGER].claims[A].ALPHA.operationId, result.operationId);
  assert.equal(stringifyExact(end.unrelated), stringifyExact(before.unrelated));
  assert.equal(stringifyExact(end.users[B]), stringifyExact(before.users[B]));
});
test('real root CAS preserves Firebase object and primitive priorities', async () => {
  const f = fixture();
  f.users[A]['.priority'] = 5;
  f.promo_codes.ALPHA['.priority'] = 'campaign-order';
  f.unrelated['.priority'] = 9;
  f.unrelated.orderedValue = { '.value': 'unchanged', '.priority': 7 };
  await seed(f);
  const before = await state(); await client().claim(ownerA, body()); const after = await state();
  assert.equal(number(after.users[A]['.priority']), number(before.users[A]['.priority']));
  assert.equal(after.promo_codes.ALPHA['.priority'], before.promo_codes.ALPHA['.priority']);
  assert.equal(number(after.unrelated['.priority']), number(before.unrelated['.priority']));
  assert.equal(stringifyExact(after.unrelated.orderedValue), stringifyExact(before.unrelated.orderedValue));
  assert.equal(stringifyExact(after.unrelated.huge), stringifyExact(before.unrelated.huge));
  assert.equal(number(after.users[A].expired_at), NOW + 13 * DAY);
});
test('Admin correction evidence blocks regrant after legacy marker loss and user recreation', async () => {
  const f = fixture(), repairs = '_adixtream_admin_promo_repairs_v1';
  f[repairs] = { [A]: { ALPHA: { version: 1, kind: 'LEGACY_PROMO_REPAIR',
    acknowledgement: 'BONUS_NOT_PREVIOUSLY_CREDITED', bonusDays: 3,
    expiryBefore: NOW + 7 * DAY, expiryAfter: NOW + 10 * DAY,
    usedBefore: 0, usedAfter: 1, quotaDelta: 1, appliedAt: NOW } } };
  await seed(f);
  const before = stringifyExact(await state());
  await assert.rejects(client().claim(ownerA, body()), e => e.code === 'LEGACY_RECONCILIATION_REQUIRED');
  await assert.rejects(client().lookup(ownerA, body()), e => e.code === 'LEGACY_RECONCILIATION_REQUIRED');
  assert.equal(stringifyExact(await state()), before);
  await mutate(root => { delete root.users[A]; });
  await mutate(root => { root.users[A] = fixture().users[A]; root.promo_codes.ALPHA.used_count = 0; });
  await assert.rejects(client().claim(ownerA, body()), e => e.code === 'LEGACY_RECONCILIATION_REQUIRED');
  assert.equal(stringifyExact((await state())[repairs]), stringifyExact(parseExact(before)[repairs]));
  assert.equal((await state())[LEDGER], undefined);
});
test('concurrent Admin repair during server claim cannot produce a second grant', async () => {
  let first = true;
  const adapter = wrapped(async () => {
    if (!first) return; first = false;
    await mutate(root => {
      root.users[A].expired_at = NOW + 13 * DAY;
      root.promo_codes.ALPHA.used_count = 1;
      root._adixtream_admin_promo_repairs_v1 = { [A]: { ALPHA: {
        version: 1, kind: 'LEGACY_PROMO_REPAIR', bonusDays: 3,
        expiryBefore: NOW + 10 * DAY, expiryAfter: NOW + 13 * DAY,
        usedBefore: 0, usedAfter: 1, quotaDelta: 1, appliedAt: NOW,
        acknowledgement: 'BONUS_NOT_PREVIOUSLY_CREDITED' } } };
    });
  });
  await assert.rejects(client(adapter).claim(ownerA, body()), e => e.code === 'LEGACY_RECONCILIATION_REQUIRED');
  const root = await state(); assert.equal(adapter.conflicts, 1);
  assert.equal(number(root.users[A].expired_at), NOW + 13 * DAY);
  assert.equal(number(root.promo_codes.ALPHA.used_count), 1); assert.equal(root[LEDGER], undefined);
});
test('real RTDB last quota race grants only one owner', async () => {
  const f = fixture(); f.promo_codes.ALPHA.max_quota = 1; await seed(f);
  const adapter = wrapped(firstCasBarrier(2)), s = client(adapter);
  const r = await Promise.allSettled([s.claim(ownerA, body()), s.claim(ownerB, body('ALPHA', 'request_0000000002', B))]);
  assert.equal(r.filter(x => x.status === 'fulfilled').length, 1);
  assert.equal(r.find(x => x.status === 'rejected').reason.code, 'QUOTA_EXHAUSTED');
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 1); assert.equal(adapter.conflicts, 1);
});
test('real RTDB two different promos preserve both bonuses for same owner', async () => {
  const adapter = wrapped(firstCasBarrier(2)), s = client(adapter);
  await Promise.all([s.claim(ownerA, body()), s.claim(ownerA, body('BETA', 'request_0000000002'))]);
  const end = await state(); assert.equal(number(end.users[A].expired_at), NOW + 16 * DAY);
  assert.equal(number(end.users[A].revision), 7); assert.equal(Object.keys(end[LEDGER].claims[A]).length, 2);
});
test('real RTDB identical parallel request returns one operation', async () => {
  const adapter = wrapped(firstCasBarrier(2)), s = client(adapter);
  const r = await Promise.all([s.claim(ownerA, body()), s.claim(ownerA, body())]);
  assert.equal(new Set(r.map(x => x.operationId)).size, 1);
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 1);
});
test('separate Node processes cannot bypass cross-instance atomicity', async t => {
  const a = worker(t, { body: body(), pauseBeforeWrite: true }), b = worker(t, { body: body(), pauseBeforeWrite: true });
  await Promise.all([a.next('BEFORE_WRITE'), b.next('BEFORE_WRITE')]);
  a.process.send({ type: 'release' }); b.process.send({ type: 'release' });
  const r = await Promise.all([a.next('RESULT'), b.next('RESULT')]);
  assert.equal(r[0].result.operationId, r[1].result.operationId);
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 1);
});
test('SIGKILL after server commit leaves receipt recoverable without a second grant', async t => {
  const w = worker(t, { body: body(), pauseAfterWrite: true });
  await w.next('COMMITTED'); w.process.kill('SIGKILL');
  await new Promise(resolve => w.process.once('exit', resolve));
  const r = await client().lookup(ownerA, body()); assert.equal(r.outcome, 'REPLAYED');
  assert.equal((await client().claim(ownerA, body())).operationId, r.operationId);
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 1);
});
test('SIGKILL before write leaves all three business fields unchanged', async t => {
  const before = stringifyExact(await state());
  const w = worker(t, { body: body(), pauseBeforeWrite: true });
  await w.next('BEFORE_WRITE'); w.process.kill('SIGKILL');
  await new Promise(resolve => w.process.once('exit', resolve));
  assert.equal(stringifyExact(await state()), before);
  await client().claim(ownerA, body()); assert.equal(number((await state()).promo_codes.ALPHA.used_count), 1);
});
test('admin ban after read forces conflict and prevents grant', async () => {
  let first = true;
  const adapter = wrapped(async () => { if (first) { first = false; await mutate(f => { f.users[A].status = 'banned'; }); } });
  await assert.rejects(client(adapter).claim(ownerA, body()), e => e.code === 'BANNED');
  assert.equal(adapter.conflicts, 1); assert(!(await state())[LEDGER]);
});
test('admin renewal after read is preserved and promo extends the new expiry', async () => {
  let first = true;
  const adapter = wrapped(async () => { if (first) { first = false; await mutate(f => { f.users[A].expired_at = NOW + 40 * DAY; }); } });
  await client(adapter).claim(ownerA, body()); assert.equal(number((await state()).users[A].expired_at), NOW + 43 * DAY);
});
test('legacy marker, changed date aliases, and wrong owner never partially mutate RTDB', async () => {
  for (const [change, context, code] of [
    [f => { f.users[A].redeemed_promos = { ALPHA: true }; }, ownerA, 'LEGACY_RECONCILIATION_REQUIRED'],
    [f => { f.promo_codes.ALPHA.valid_until += DAY; }, ownerA, 'PROMO_DATE_CONFLICT'],
    [() => {}, ownerB, 'OWNERSHIP_REQUIRED'],
  ]) {
    const f = fixture(); change(f); await seed(f); const before = stringifyExact(await state());
    await assert.rejects(client().claim(context, body()), e => e.code === code);
    assert.equal(stringifyExact(await state()), before);
  }
});
test('private receipt and root writes stay denied to unauthenticated emulator clients', async () => {
  await client().claim(ownerA, body());
  assert.equal((await http('GET', '/' + LEDGER, undefined, false)).status, 401);
  assert.equal((await http('PUT', '/', fixture(), false)).status, 401);
});
test('receipt remains private after quota reset and current ban takes precedence', async () => {
  const r = await client().claim(ownerA, body());
  await mutate(f => { f.promo_codes.ALPHA.used_count = 0; f.users[A].status = 'banned'; });
  const replay = await client().claim(ownerA, body('ALPHA', 'new_after_clear_data'));
  assert.equal(replay.operationId, r.operationId); assert.equal(replay.currentEntitlement.state, 'BANNED');
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 0);
});
test('small synthetic contention sample records latency and contention without claiming production capacity', async () => {
  const f = fixture();
  for (let i = 0; i < 12; i++) {
    const id = String(30000000 + i);
    f.users[id] = fixture().users[A]; f.ownerBindings[id] = { principalId: 'load-owner-' + i, status: 'VERIFIED_OWNER' };
  }
  f.promo_codes.ALPHA.max_quota = 12; await seed(f);
  let conflicts = 0;
  const adapter = { read: () => db.read(), async compareAndSet(etag, root) { const r = await db.compareAndSet(etag, root); if (!r) conflicts++; return r; } };
  const times = [], started = performance.now();
  await Promise.all(Array.from({ length: 12 }, async (_, i) => {
    const start = performance.now();
    await createClaimService({ db: adapter, clock: () => NOW, maxAttempts: 24 }).claim(
      { principalId: 'load-owner-' + i, assurance: 'VERIFIED_OWNER' }, body('ALPHA', 'request_load_00000' + i, String(30000000 + i)));
    times.push(performance.now() - start);
  }));
  assert.equal(number((await state()).promo_codes.ALPHA.used_count), 12);
  times.sort((a, b) => a - b);
  console.log('SYNTHETIC_LOAD', JSON.stringify({ clients: 12, applied: 12, conflicts, totalMs: Math.round(performance.now() - started), p95Ms: Math.round(times[Math.ceil(times.length * .95) - 1]), snapshotBytes: Buffer.byteLength(stringifyExact(await state())) }));
});

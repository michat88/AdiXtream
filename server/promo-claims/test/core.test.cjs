'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createClaimService, canonical, DAY, LEDGER } = require('../src/claims.cjs');
const { parseExact, stringifyExact, integer, timestamp } = require('../src/exact-json.cjs');
const { NOW, A, B, ownerA, ownerB, fixture, body, clone, memoryDb, firstCasBarrier, number } = require('./helpers.cjs');
const client = (db, extra = {}) => createClaimService({ db, clock: () => NOW, backoff: async () => {}, ...extra });
const rejects = (promise, code) => assert.rejects(promise, e => e.code === code);
async function unchanged(db, fn) {
  const before = stringifyExact(db.get());
  await fn();
  assert.equal(stringifyExact(db.get()), before);
}

test('one atomic grant preserves unrelated fields and all numeric tokens', async () => {
  const db = memoryDb(), before = db.get();
  const r = await client(db).claim(ownerA, body()), end = db.get();
  assert.equal(r.outcome, 'APPLIED');
  assert.equal(r.currentEntitlement.state, 'PREMIUM');
  assert.equal(number(end.users[A].expired_at), NOW + 13 * DAY);
  assert.equal(number(end.users[A].revision), 6);
  assert.equal(number(end.promo_codes.ALPHA.used_count), 1);
  assert.equal(end.users[A].redeemed_promos.ALPHA, true);
  assert.equal(end[LEDGER].claims[A].ALPHA.operationId, r.operationId);
  assert.equal(stringifyExact(end.unrelated), stringifyExact(before.unrelated));
  for (const k of ['code', 'account_type', 'extend_count', 'custom']) assert.equal(stringifyExact(end.users[A][k]), stringifyExact(before.users[A][k]));
  assert.equal(db.writes, 1);
});
test('two owners competing for one quota: one receipt, one rejection', async () => {
  const f = fixture(); f.promo_codes.ALPHA.max_quota = 1;
  const db = memoryDb(f); db.beforeCas = firstCasBarrier(2);
  const result = await Promise.allSettled([client(db).claim(ownerA, body()), client(db).claim(ownerB, body('ALPHA', 'request_0000000002', B))]);
  assert.equal(result.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(result.find(r => r.status === 'rejected').reason.code, 'QUOTA_EXHAUSTED');
  assert.equal(number(db.get().promo_codes.ALPHA.used_count), 1);
  assert.equal(Object.keys(db.get()[LEDGER].claims).length, 1);
  assert.equal(db.conflicts, 1);
});
test('parallel identical requests commit once across independent service instances', async () => {
  const db = memoryDb(); db.beforeCas = firstCasBarrier(2);
  const r = await Promise.all([client(db).claim(ownerA, body()), client(db).claim(ownerA, body())]);
  assert.equal(new Set(r.map(x => x.operationId)).size, 1);
  assert.equal(r.filter(x => x.outcome === 'REPLAYED').length, 1);
  assert.equal(db.writes, 1);
});
test('different request keys for the same device/promo cannot grant twice', async () => {
  const db = memoryDb(); db.beforeCas = firstCasBarrier(2);
  const r = await Promise.all([client(db).claim(ownerA, body()), client(db).claim(ownerA, body('ALPHA', 'request_0000000002'))]);
  assert.equal(new Set(r.map(x => x.operationId)).size, 1);
  assert.equal(number(db.get().promo_codes.ALPHA.used_count), 1);
  await rejects(client(db).claim(ownerA, body('BETA', 'request_0000000002')), 'IDEMPOTENCY_CONFLICT');
});
test('two different promos on one user preserve both bonuses', async () => {
  const db = memoryDb(); db.beforeCas = firstCasBarrier(2);
  await Promise.all([client(db).claim(ownerA, body()), client(db).claim(ownerA, body('BETA', 'request_0000000002'))]);
  assert.equal(number(db.get().users[A].expired_at), NOW + 16 * DAY);
  assert.equal(number(db.get().users[A].revision), 7);
  assert.equal(Object.keys(db.get()[LEDGER].claims[A]).length, 2);
});
test('timeout after commit is UNKNOWN; receipt lookup and retry never regrant', async () => {
  const db = memoryDb(); db.afterCas = () => { db.afterCas = null; throw new Error('synthetic-lost-response'); };
  await rejects(client(db).claim(ownerA, body()), 'UNKNOWN_RESULT');
  const r = await client(db).lookup(ownerA, body());
  assert.equal(r.outcome, 'REPLAYED');
  assert.equal((await client(db).claim(ownerA, body())).operationId, r.operationId);
  assert.equal(db.writes, 1);
  assert.equal(number(db.get().promo_codes.ALPHA.used_count), 1);
});
test('interruption before commit leaves no marker, quota or entitlement change', async () => {
  const db = memoryDb(); db.beforeCas = () => { throw new Error('synthetic-network'); };
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'UNKNOWN_RESULT'));
  db.beforeCas = null;
  await client(db).claim(ownerA, body());
  assert.equal(db.writes, 1);
});
test('missing receipt remains UNKNOWN, not evidence that an in-flight claim failed', async () => {
  const db = memoryDb();
  await unchanged(db, () => rejects(client(db).lookup(ownerA, body()), 'UNKNOWN_RESULT'));
});
test('request key cannot be reused with a different promo', async () => {
  const db = memoryDb(); await client(db).claim(ownerA, body());
  await unchanged(db, () => rejects(client(db).claim(ownerA, body('BETA')), 'IDEMPOTENCY_CONFLICT'));
});
test('legacy true marker without a receipt requires reconciliation', async () => {
  const f = fixture(); f.users[A].redeemed_promos = { ALPHA: true };
  const db = memoryDb(f);
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'LEGACY_RECONCILIATION_REQUIRED'));
});
test('legacy false or string markers are not silently granted again', async () => {
  for (const marker of [false, 'true']) {
    const f = fixture(); f.users[A].redeemed_promos = { ALPHA: marker };
    const db = memoryDb(f);
    await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'LEGACY_RECONCILIATION_REQUIRED'));
  }
});
test('reset quota, delete/recreate promo or delete/recreate user retains claim tombstone', async () => {
  const db = memoryDb(); const service = client(db); const first = await service.claim(ownerA, body());
  db.mutate(f => { f.promo_codes.ALPHA.used_count = 0; });
  assert.equal((await service.claim(ownerA, body())).operationId, first.operationId);
  db.mutate(f => { delete f.promo_codes.ALPHA; });
  assert.equal((await service.lookup(ownerA, body())).operationId, first.operationId);
  db.mutate(f => { f.promo_codes.ALPHA = fixture().promo_codes.ALPHA; delete f.users[A]; });
  await rejects(service.claim(ownerA, body()), 'DATA_RECONCILIATION_REQUIRED');
  db.mutate(f => { f.users[A] = fixture().users[A]; });
  assert.equal((await service.claim(ownerA, body())).outcome, 'REPLAYED');
  assert.equal(number(db.get().promo_codes.ALPHA.used_count), 0);
});
test('ban after receipt returns current BANNED rather than reactivating old receipt', async () => {
  const db = memoryDb(); await client(db).claim(ownerA, body());
  db.mutate(f => { f.users[A].status = 'banned'; });
  const r = await client(db).claim(ownerA, body());
  assert.equal(r.outcome, 'REPLAYED'); assert.equal(r.currentEntitlement.state, 'BANNED');
  assert.equal(db.writes, 1);
});
for (const [name, change, code] of [
  ['ban', f => { f.users[A].status = 'banned'; }, 'BANNED'],
  ['pause', f => { f.promo_codes.ALPHA.status = 'nonaktif'; }, 'PAUSED'],
  ['ownership revoke', f => { f.ownerBindings[A].status = 'REVOKED'; }, 'OWNERSHIP_REQUIRED'],
]) test(`${name} between read and CAS is revalidated after conflict`, async () => {
  const db = memoryDb();
  db.beforeCas = () => { db.beforeCas = null; db.mutate(change); };
  await rejects(client(db).claim(ownerA, body()), code);
  assert.equal(db.writes, 0); assert.equal(db.conflicts, 1);
  assert.equal(number(db.get().promo_codes.ALPHA.used_count), 0);
});
test('renewal by an admin during claim preserves the renewed base', async () => {
  const db = memoryDb();
  db.beforeCas = () => { db.beforeCas = null; db.mutate(f => { f.users[A].expired_at = NOW + 30 * DAY; f.users[A].revision = 6; }); };
  await client(db).claim(ownerA, body());
  assert.equal(number(db.get().users[A].expired_at), NOW + 33 * DAY);
  assert.equal(number(db.get().users[A].revision), 7);
});
test('promo expiration is checked again on CAS retry', async () => {
  const db = memoryDb(); let now = NOW;
  db.beforeCas = () => { db.beforeCas = null; now += 20 * DAY; db.mutate(f => { f.unrelated.changed = true; }); };
  await rejects(client(db, { clock: () => now }).claim(ownerA, body()), 'EXPIRED');
  assert.equal(db.writes, 0);
});
test('bounded contention returns retryable result without partial writes', async () => {
  const db = memoryDb(); db.beforeCas = () => db.mutate(f => { f.unrelated.changed = true; });
  await rejects(client(db, { maxAttempts: 3 }).claim(ownerA, body()), 'CONTENTION_RETRY_REQUIRED');
  assert.equal(db.writes, 0); assert.equal(db.conflicts, 3); assert(!db.get()[LEDGER]);
});
test('unverified, anonymous and wrong owner contexts cannot touch entitlement', async () => {
  const db = memoryDb();
  await rejects(client(db).claim(null, body()), 'AUTH_REQUIRED');
  await rejects(client(db).claim({ principalId: 'anon', assurance: 'ANONYMOUS' }, body()), 'OWNERSHIP_REQUIRED');
  assert.equal(db.reads, 0);
  await unchanged(db, () => rejects(client(db).claim(ownerB, body()), 'OWNERSHIP_REQUIRED'));
});
test('no first-claim owner binding is created', async () => {
  const f = fixture(); delete f.ownerBindings[A]; const db = memoryDb(f);
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'OWNERSHIP_REQUIRED'));
});
test('expired entitlement starts from server time and not the client clock', async () => {
  const f = fixture(); f.users[A].expired_at = NOW - DAY; const db = memoryDb(f);
  const r = await client(db).claim(ownerA, body());
  assert.equal(r.receipt.expiresAt, NOW + 3 * DAY);
  assert.equal(r.serverTime, NOW);
});
test('known free record with missing expiry can claim without replacing identity fields', async () => {
  const f = fixture(); delete f.users[A].expired_at; delete f.users[A].account_type;
  const db = memoryDb(f); assert.equal((await client(db).claim(ownerA, body())).receipt.expiresAt, NOW + 3 * DAY);
});
test('legacy promo customer missing status can claim a different promo without backfill', async () => {
  const f = fixture(); delete f.users[A].status; f.users[A].redeemed_promos = { OLDPROMO: true };
  const db = memoryDb(f); await client(db).claim(ownerA, body());
  assert.equal(db.get().users[A].status, undefined); assert.equal(db.get().users[A].redeemed_promos.OLDPROMO, true);
});
test('missing status without trusted legacy shape and paid missing expiry require reconciliation', async () => {
  for (const change of [f => { delete f.users[A].status; }, f => { delete f.users[A].expired_at; }]) {
    const f = fixture(); change(f); const db = memoryDb(f);
    await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'DATA_RECONCILIATION_REQUIRED'));
  }
});
test('legacy seconds, numeric strings and ISO use the same expiry semantics', async () => {
  for (const value of [NOW / 1000 + DAY / 1000, String(NOW + DAY), new Date(NOW + DAY).toISOString(), JSON.rawJSON(String(NOW + DAY) + '.0')]) {
    const f = fixture(); f.users[A].expired_at = value; const db = memoryDb(f);
    assert.equal((await client(db).claim(ownerA, body())).receipt.expiresAt, NOW + 4 * DAY);
  }
});
test('one promo date alias is normalized only on successful claim', async () => {
  const f = fixture(); delete f.promo_codes.ALPHA.valid_until;
  const db = memoryDb(f); await client(db).claim(ownerA, body());
  assert.equal(number(db.get().promo_codes.ALPHA.valid_until), NOW + 20 * DAY);
});
test('mismatched aliases never silently extend the campaign', async () => {
  const f = fixture(); f.promo_codes.ALPHA.expired_at += DAY; const db = memoryDb(f);
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'PROMO_DATE_CONFLICT'));
});
for (const [field, value, code] of [
  ['days', 0, 'DATA_INVALID'], ['days', 36501, 'DATA_INVALID'], ['days', 1.5, 'DATA_INVALID'],
  ['used_count', -1, 'DATA_INVALID'], ['max_quota', 0, 'QUOTA_EXHAUSTED'], ['used_count', 11, 'DATA_INVALID'],
  ['valid_until', 'not-a-date', 'DATA_RECONCILIATION_REQUIRED'],
]) test(`invalid promo ${field}=${value} leaves the whole snapshot unchanged`, async () => {
  const f = fixture(); f.promo_codes.ALPHA[field] = value; const db = memoryDb(f);
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), code));
});
test('target huge expiry requires reconciliation but unrelated huge values remain supported', async () => {
  const f = fixture(); f.users[A].expired_at = JSON.rawJSON('922337203685477580812345');
  const db = memoryDb(f);
  await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'DATA_RECONCILIATION_REQUIRED'));
});
test('invalid calendar dates, negative times and numerical underflow are rejected exactly', () => {
  for (const value of ['2026-02-30T00:00:00Z', -1, JSON.rawJSON('1e-99999'), JSON.rawJSON('9007199254740993'), JSON.rawJSON('1.00000000000000000000001')]) {
    assert.throws(() => timestamp(value), e => e.code === 'DATA_RECONCILIATION_REQUIRED');
  }
  assert.equal(integer(JSON.rawJSON('1.000e2')), 100);
});
test('lossless JSON roundtrip preserves unsafe numbers, decimal lexemes and __proto__ data', () => {
  const text = '{"large":922337203685477580812345,"decimal":0.1234567890123456789,"exp":1e+400,"__proto__":{"safe":true}}';
  assert.equal(stringifyExact(parseExact(text)), text);
  assert.equal({}.safe, undefined);
});
test('input rejects injected owner/expiry, bad paths, contract versions and short keys', () => {
  for (const input of [{ ...body(), principalId: ownerA.principalId }, { ...body(), expired_at: NOW },
    { ...body(), deviceId: '../users' }, { ...body(), promoCode: 'A/B' }, { ...body(), contractVersion: 2 },
    { ...body(), idempotencyKey: 'short' }]) assert.throws(() => canonical(input), e => e.code === 'REQUEST_INVALID');
  assert.equal(canonical({ ...body(), deviceId: '-2147483', promoCode: ' alpha ' }).promoCode, 'ALPHA');
});
test('malformed private ledger fails closed instead of losing earlier receipts', async () => {
  for (const ledger of [false, [], { claims: 'broken' }, { requests: 42 }]) {
    const f = fixture(); f[LEDGER] = ledger; const db = memoryDb(f);
    await unchanged(db, () => rejects(client(db).claim(ownerA, body()), 'DATA_INVALID'));
  }
});
test('malformed receipt cannot masquerade as a successful claim', async () => {
  const db = memoryDb(); await client(db).claim(ownerA, body());
  db.mutate(f => { f[LEDGER].claims[A].ALPHA.state = 'PENDING'; });
  await unchanged(db, () => rejects(client(db).lookup(ownerA, body()), 'DATA_RECONCILIATION_REQUIRED'));
});
test('lookup after loss of local intent can find the pair receipt with a new key', async () => {
  const db = memoryDb(); const r = await client(db).claim(ownerA, body());
  const recovered = await client(db).lookup(ownerA, body('ALPHA', 'new_after_clear_data'));
  assert.equal(recovered.operationId, r.operationId); assert.equal(db.writes, 1);
});

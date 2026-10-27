'use strict';
const { parseExact, stringifyExact } = require('../src/exact-json.cjs');
const { DAY } = require('../src/claims.cjs');
const NOW = Date.UTC(2026, 9, 27, 12);
const A = '11111111', B = '22222222';
const ownerA = { assurance: 'VERIFIED_OWNER', principalId: 'synthetic-owner-a' };
const ownerB = { assurance: 'VERIFIED_OWNER', principalId: 'synthetic-owner-b' };
function fixture() {
  const user = () => ({ status: 'aktif', expired_at: NOW + 10 * DAY, revision: 5,
    code: 'SYNTHETIC-EXISTING', account_type: 'premium', extend_count: 3, custom: { preserve: true } });
  const promo = () => ({ status: 'aktif', days: 3, max_quota: 10, used_count: 0,
    valid_until: NOW + 20 * DAY, expired_at: NOW + 20 * DAY, custom: 'preserve' });
  return { users: { [A]: user(), [B]: user() }, promo_codes: { ALPHA: promo(), BETA: promo() },
    ownerBindings: { [A]: { ...ownerA, status: 'VERIFIED_OWNER' }, [B]: { ...ownerB, status: 'VERIFIED_OWNER' } },
    unrelated: { decimal: JSON.rawJSON('0.1234567890123456789012345'), huge: JSON.rawJSON('922337203685477580812345'),
      scientific: JSON.rawJSON('1.234567890123456789e+100'), nested: [false, 'preserve', null] } };
}
function body(promoCode = 'ALPHA', idempotencyKey = 'request_0000000001', deviceId = A) {
  return { contractVersion: 1, deviceId, promoCode, idempotencyKey };
}
const clone = value => parseExact(stringifyExact(value));
function memoryDb(initial = fixture()) {
  let root = clone(initial), version = 0;
  return { reads: 0, writes: 0, conflicts: 0, beforeCas: null, afterCas: null,
    async read() { this.reads++; return { root: clone(root), etag: '"' + version + '"' }; },
    async compareAndSet(etag, next) {
      if (this.beforeCas) await this.beforeCas(this);
      if (etag !== '"' + version + '"') { this.conflicts++; return false; }
      root = clone(next); version++; this.writes++;
      if (this.afterCas) await this.afterCas(this);
      return true;
    },
    get() { return clone(root); },
    mutate(fn) { const next = clone(root); fn(next); root = next; version++; },
  };
}
function firstCasBarrier(n) {
  let arrived = 0, resolve;
  const gate = new Promise(r => { resolve = r; });
  return async () => { if (arrived >= n) return; if (++arrived === n) resolve(); await gate; };
}
const number = value => typeof value === 'number' ? value : Number(value.rawJSON);
module.exports = { NOW, A, B, ownerA, ownerB, fixture, body, clone, memoryDb, firstCasBarrier, number };

'use strict';

const { createHash, randomUUID } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { fail, safeError } = require('./errors.cjs');
const { record, own, table, integer, timestamp } = require('./exact-json.cjs');
const DAY = 86400000;
const LEDGER = '_adixtream_claims_v1';
const REPAIR_LEDGER = '_adixtream_admin_promo_repairs_v1';
const hash = text => createHash('sha256').update(text).digest('hex');

function canonical(body) {
  const keys = ['contractVersion', 'deviceId', 'promoCode', 'idempotencyKey'];
  if (!record(body) || Object.keys(body).length !== keys.length || keys.some(k => !Object.hasOwn(body, k))) fail('REQUEST_INVALID');
  if (body.contractVersion !== 1 || typeof body.deviceId !== 'string' ||
      !/^(?:[0-9]{1,8}|-2147483)$/.test(body.deviceId)) fail('REQUEST_INVALID');
  // Includes the existing abs(Int.MIN_VALUE).toString().take(8) ID edge case.
  if (typeof body.promoCode !== 'string' || body.promoCode.length > 128) fail('REQUEST_INVALID');
  const promoCode = body.promoCode.trim().toUpperCase();
  if (!/^[A-Z0-9_-]{1,64}$/.test(promoCode) || typeof body.idempotencyKey !== 'string' ||
      !/^[A-Za-z0-9_-]{16,100}$/.test(body.idempotencyKey)) fail('REQUEST_INVALID');
  return { contractVersion: 1, deviceId: body.deviceId, promoCode, idempotencyKey: body.idempotencyKey };
}
function principal(context) {
  if (!context) fail('AUTH_REQUIRED');
  if (context.assurance !== 'VERIFIED_OWNER' || typeof context.principalId !== 'string' ||
      !/^[A-Za-z0-9:_-]{1,128}$/.test(context.principalId)) fail('OWNERSHIP_REQUIRED');
  return context.principalId;
}
function userStatus(user) {
  if (user.status === 'banned') return 'BANNED';
  if (user.status === 'aktif') return 'ACTIVE';
  if (user.status === undefined && Object.values(table(user, 'redeemed_promos')).some(v => v === true)) return 'ACTIVE';
  return 'UNKNOWN';
}
function expiry(user) {
  if (user.expired_at !== undefined) return timestamp(user.expired_at);
  if (user.account_type === 'premium' || Object.values(table(user, 'redeemed_promos')).some(v => v === true)) fail('DATA_RECONCILIATION_REQUIRED');
  return 0;
}
function revision(user) {
  const value = user.revision === undefined ? 0 : integer(user.revision);
  if (value < 0 || value >= Number.MAX_SAFE_INTEGER) fail('DATA_INVALID');
  return value;
}
function currentEntitlement(user, now) {
  const status = userStatus(user);
  let expiresAt = null, rev = null;
  try { expiresAt = expiry(user); rev = revision(user); } catch { /* Preserve, don't guess or write back. */ }
  return { state: status === 'BANNED' ? 'BANNED' : status === 'UNKNOWN' || expiresAt === null ? 'UNKNOWN' :
    expiresAt > now ? 'PREMIUM' : expiresAt > 0 ? 'EXPIRED' : 'FREE', expiresAt, revision: rev };
}
function readState(root, owner, input) {
  if (!record(root)) fail('DATA_RECONCILIATION_REQUIRED');
  const binding = own(table(root, 'ownerBindings'), input.deviceId);
  if (!record(binding) || binding.status !== 'VERIFIED_OWNER' || binding.principalId !== owner) fail('OWNERSHIP_REQUIRED');
  const users = table(root, 'users'), user = own(users, input.deviceId);
  if (!record(user)) fail('DATA_RECONCILIATION_REQUIRED');
  const ledger = table(root, LEDGER), claims = table(ledger, 'claims');
  const deviceClaims = table(claims, input.deviceId), requests = table(ledger, 'requests');
  const requestKey = hash(owner + '\0' + input.idempotencyKey);
  const fingerprint = hash(input.deviceId + '\0' + input.promoCode);
  const request = own(requests, requestKey), receipt = own(deviceClaims, input.promoCode);
  if (request !== undefined && (!record(request) || request.fingerprint !== fingerprint)) fail('IDEMPOTENCY_CONFLICT');
  if (receipt !== undefined) {
    if (!record(receipt) || receipt.state !== 'APPLIED' || receipt.deviceId !== input.deviceId ||
        receipt.promoCode !== input.promoCode || typeof receipt.operationId !== 'string' ||
        !/^[a-f0-9-]{36}$/.test(receipt.operationId) ||
        (request !== undefined && request.operationId !== receipt.operationId)) fail('DATA_RECONCILIATION_REQUIRED');
    if (integer(receipt.bonusDays) < 1 || timestamp(receipt.expiresAt) < timestamp(receipt.appliedAt) ||
        integer(receipt.revision) < 1) fail('DATA_RECONCILIATION_REQUIRED');
  } else if (request !== undefined) fail('DATA_RECONCILIATION_REQUIRED');
  return { users, user, ledger, claims, deviceClaims, requests, requestKey, fingerprint, request, receipt };
}
function response(receipt, user, now, outcome) {
  return { contractVersion: 1, operationId: receipt.operationId, outcome, serverTime: now, retryable: false,
    receipt: { bonusDays: integer(receipt.bonusDays), expiresAt: timestamp(receipt.expiresAt),
      revision: integer(receipt.revision), appliedAt: timestamp(receipt.appliedAt) },
    currentEntitlement: currentEntitlement(user, now) };
}
function rejectRepairedPair(root, input) {
  // Admin receipts survive marker removal, quota resets and user recreation.
  // Any recorded pair blocks a fresh grant, including malformed evidence that
  // needs operator review. Never turn an Admin repair into a server receipt.
  const repairs = table(table(root, REPAIR_LEDGER), input.deviceId);
  if (Object.hasOwn(repairs, input.promoCode)) fail('LEGACY_RECONCILIATION_REQUIRED');
}
function decide(root, owner, input, now, operationId) {
  const s = readState(root, owner, input);
  const requestEntry = receipt => ({ fingerprint: s.fingerprint, operationId: receipt.operationId });
  if (s.receipt) {
    const next = s.request ? null : { ...root, [LEDGER]: { ...s.ledger,
      requests: { ...s.requests, [s.requestKey]: requestEntry(s.receipt) } } };
    return { next, result: response(s.receipt, s.user, now, 'REPLAYED') };
  }
  rejectRepairedPair(root, input);
  const status = userStatus(s.user);
  if (status === 'BANNED') fail('BANNED');
  if (status !== 'ACTIVE') fail('DATA_RECONCILIATION_REQUIRED');
  const redeemed = table(s.user, 'redeemed_promos');
  if (Object.hasOwn(redeemed, input.promoCode)) fail('LEGACY_RECONCILIATION_REQUIRED');
  const promos = table(root, 'promo_codes'), promo = own(promos, input.promoCode);
  if (promo === undefined) fail('PROMO_NOT_FOUND');
  if (!record(promo)) fail('DATA_INVALID');
  if (promo.status !== 'aktif') fail('PAUSED');
  const a = promo.valid_until === undefined ? undefined : timestamp(promo.valid_until);
  const b = promo.expired_at === undefined ? undefined : timestamp(promo.expired_at);
  if (a !== undefined && b !== undefined && a !== b) fail('PROMO_DATE_CONFLICT');
  const end = a ?? b;
  if (end === undefined) fail('DATA_RECONCILIATION_REQUIRED');
  if (now >= end) fail('EXPIRED');
  const used = integer(promo.used_count), max = integer(promo.max_quota), days = integer(promo.days);
  if (used < 0 || max < 0 || used > max) fail('DATA_INVALID');
  if (used === max) fail('QUOTA_EXHAUSTED');
  // Same maximum package duration as the existing Admin validator.
  if (days < 1 || days > 36500) fail('DATA_INVALID');
  const expiresAt = Math.max(expiry(s.user), now) + days * DAY;
  if (!Number.isSafeInteger(expiresAt) || expiresAt > 8640000000000000) fail('DATA_RECONCILIATION_REQUIRED');
  const rev = revision(s.user) + 1;
  const user = { ...s.user, expired_at: expiresAt, revision: rev, last_update: new Date(now).toISOString(),
    redeemed_promos: { ...redeemed, [input.promoCode]: true } };
  const receipt = { operationId, state: 'APPLIED', deviceId: input.deviceId, promoCode: input.promoCode,
    bonusDays: days, expiresAt, revision: rev, appliedAt: now };
  return { next: { ...root, users: { ...s.users, [input.deviceId]: user },
    promo_codes: { ...promos, [input.promoCode]: { ...promo, used_count: used + 1, valid_until: end, expired_at: end } },
    [LEDGER]: { ...s.ledger, claims: { ...s.claims, [input.deviceId]: { ...s.deviceClaims, [input.promoCode]: receipt } },
      requests: { ...s.requests, [s.requestKey]: requestEntry(receipt) } } },
    result: response(receipt, user, now, 'APPLIED') };
}
function serverNow(clock) {
  const now = clock();
  if (!Number.isSafeInteger(now) || now < 100000000000 || now > 8640000000000000) fail('TEMPORARY_UNAVAILABLE');
  return now;
}
function createClaimService({ db, clock = Date.now, maxAttempts = 8, backoff = attempt => delay(Math.min(100, 5 * 2 ** attempt) + Math.floor(Math.random() * 10)) }) {
  if (!db || typeof db.read !== 'function' || typeof db.compareAndSet !== 'function' || !Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 32) throw new Error('SERVICE_CONFIG_INVALID');
  return {
    async claim(context, body) {
      const owner = principal(context), input = canonical(body), operationId = randomUUID();
      for (let attempt = 0; attempt < maxAttempts; attempt++) {
        let snapshot;
        try { snapshot = await db.read(); } catch (e) { throw safeError(e); }
        const { next, result } = decide(snapshot.root, owner, input, serverNow(clock), operationId);
        if (next === null) return result;
        let committed;
        try { committed = await db.compareAndSet(snapshot.etag, next); }
        catch { fail('UNKNOWN_RESULT'); }
        if (committed === true) return result;
        if (committed !== false) fail('UNKNOWN_RESULT');
        if (attempt + 1 < maxAttempts) await backoff(attempt);
      }
      fail('CONTENTION_RETRY_REQUIRED');
    },
    async lookup(context, body) {
      const owner = principal(context), input = canonical(body);
      let snapshot;
      try { snapshot = await db.read(); } catch (e) { throw safeError(e); }
      const s = readState(snapshot.root, owner, input), now = serverNow(clock);
      if (!s.receipt) {
        rejectRepairedPair(snapshot.root, input);
        fail('UNKNOWN_RESULT');
      }
      return response(s.receipt, s.user, now, 'REPLAYED');
    },
  };
}
module.exports = { createClaimService, canonical, DAY, LEDGER };

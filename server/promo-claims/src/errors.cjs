'use strict';

// Only these identifiers may cross the HTTP boundary. Never return SDK errors.
const statuses = {
  REQUEST_INVALID: 400, AUTH_REQUIRED: 401, OWNERSHIP_REQUIRED: 403,
  BANNED: 403, PROMO_NOT_FOUND: 404, PAUSED: 409, EXPIRED: 409,
  QUOTA_EXHAUSTED: 409, IDEMPOTENCY_CONFLICT: 409,
  LEGACY_RECONCILIATION_REQUIRED: 409, DATA_RECONCILIATION_REQUIRED: 409,
  PROMO_DATE_CONFLICT: 409, DATA_INVALID: 409, PAYLOAD_TOO_LARGE: 413,
  UNKNOWN_RESULT: 503, TEMPORARY_UNAVAILABLE: 503, CONTENTION_RETRY_REQUIRED: 503,
  METHOD_NOT_ALLOWED: 405, NOT_FOUND: 404, UNSUPPORTED_MEDIA_TYPE: 415,
};
class ClaimError extends Error {
  constructor(code) {
    super(Object.hasOwn(statuses, code) ? code : 'TEMPORARY_UNAVAILABLE');
    this.code = this.message;
    this.status = statuses[this.code];
    this.retryable = this.status === 503;
  }
}
function fail(code) { throw new ClaimError(code); }
function safeError(error) {
  return error instanceof ClaimError ? error : new ClaimError('TEMPORARY_UNAVAILABLE');
}
module.exports = { ClaimError, fail, safeError };

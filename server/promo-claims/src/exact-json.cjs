'use strict';

const { fail } = require('./errors.cjs');
// Root CAS must not round even an unrelated customer's large/decimal number.
// Node 24's source-aware reviver and raw JSON preserve every numeric token.
if (typeof JSON.rawJSON !== 'function' || typeof JSON.isRawJSON !== 'function') {
  throw new Error('NODE_24_REQUIRED');
}
function parseExact(text) {
  try {
    return JSON.parse(text, (_key, value, context) => {
      if (typeof value !== 'number') return value;
      if (typeof context?.source !== 'string') throw new Error('SOURCE_REQUIRED');
      return JSON.rawJSON(context.source);
    });
  } catch { fail('DATA_INVALID'); }
}
function stringifyExact(value) { return JSON.stringify(value); }
function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) && !JSON.isRawJSON(value);
}
function own(object, key) { return record(object) && Object.hasOwn(object, key) ? object[key] : undefined; }
function table(object, key) {
  const value = own(object, key);
  if (value === undefined || value === null) return {};
  if (!record(value)) fail('DATA_INVALID');
  return value;
}
// Convert only business fields being used. No Number() rounding or clamping.
function integer(value, code = 'DATA_INVALID') {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) fail(code);
    return value;
  }
  if (!JSON.isRawJSON(value)) fail(code);
  const m = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value.rawJSON);
  if (!m) fail(code);
  let digits = (m[2] + (m[3] || '')).replace(/^0+/, '');
  if (!digits) return 0;
  const power = Number(m[4] || 0) - (m[3]?.length || 0);
  if (!Number.isSafeInteger(power)) fail(code);
  if (power >= 0) {
    if (digits.length + power > 16) fail(code);
    digits += '0'.repeat(power);
  } else {
    const cut = -power;
    if (cut >= digits.length || !/^0+$/.test(digits.slice(-cut))) fail(code);
    digits = digits.slice(0, -cut);
  }
  const exact = BigInt((m[1] || '') + digits);
  if (exact > BigInt(Number.MAX_SAFE_INTEGER) || exact < BigInt(Number.MIN_SAFE_INTEGER)) fail(code);
  return Number(exact);
}
function timestamp(value) {
  const code = 'DATA_RECONCILIATION_REQUIRED';
  if (typeof value === 'string') {
    if (/^\d{1,16}$/.test(value)) value = JSON.rawJSON(value.replace(/^0+(?=\d)/, ''));
    else {
      if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) fail(code);
      const result = Date.parse(value);
      const canonical = value.includes('.') ? value : value.replace('Z', '.000Z');
      if (!Number.isFinite(result) || new Date(result).toISOString() !== canonical) fail(code);
      value = result;
    }
  }
  let result = integer(value, code);
  if (result < 0) fail(code);
  if (result > 0 && result < 100000000000) result *= 1000;
  if (!Number.isSafeInteger(result) || result > 8640000000000000) fail(code);
  return result;
}
module.exports = { parseExact, stringifyExact, record, own, table, integer, timestamp };

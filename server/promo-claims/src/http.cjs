'use strict';

const { ClaimError, fail, safeError } = require('./errors.cjs');
const { canonical } = require('./claims.cjs');
const MAX_BODY = 4096;
function bodyJson(req) {
  return new Promise((resolve, reject) => {
    let bytes = 0, finished = false;
    const chunks = [];
    const timer = setTimeout(() => finish(new Error('TIMEOUT')), 5000);
    function finish(error) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      req.off('data', data); req.off('end', end); req.off('aborted', abort);
      if (error) { req.resume(); reject(error); }
      else {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch { reject(new ClaimError('REQUEST_INVALID')); }
      }
    }
    function data(chunk) {
      bytes += chunk.length;
      if (bytes > MAX_BODY) { try { fail('PAYLOAD_TOO_LARGE'); } catch (e) { finish(e); } }
      else chunks.push(chunk);
    }
    function end() { finish(); }
    function abort() { finish(new Error('ABORTED')); }
    req.on('data', data); req.once('end', end); req.once('error', finish); req.once('aborted', abort);
    req.once('close', () => req.off('error', finish));
  });
}
// No listener/start script is shipped. The hosting adapter must supply an actual
// session verifier; the default rejects before reading any customer database.
function createPromoHandler({ service, verifySession = async () => null }) {
  if (!service || typeof verifySession !== 'function') throw new Error('HTTP_CONFIG_INVALID');
  return async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      if (!['/v1/promo/claim', '/v1/promo/receipt'].includes(req.url)) fail('NOT_FOUND');
      if (req.method !== 'POST') { res.setHeader('Allow', 'POST'); fail('METHOD_NOT_ALLOWED'); }
      if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json' ||
          (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity')) fail('UNSUPPORTED_MEDIA_TYPE');
      if (Number(req.headers['content-length'] || 0) > MAX_BODY) fail('PAYLOAD_TOO_LARGE');
      // The verifier only receives session headers, never owner claims in JSON.
      const context = await verifySession({ authorization: req.headers.authorization });
      if (!context) fail('AUTH_REQUIRED');
      const input = canonical(await bodyJson(req));
      const result = await (req.url === '/v1/promo/claim' ? service.claim(context, input) : service.lookup(context, input));
      res.statusCode = 200;
      res.end(JSON.stringify(result));
    } catch (error) {
      const e = safeError(error);
      req.resume();
      if (res.destroyed || res.writableEnded) return;
      res.statusCode = e.status;
      if (e.retryable) res.setHeader('Retry-After', '2');
      res.end(JSON.stringify({ contractVersion: 1, outcome: e.code, retryable: e.retryable }));
    }
  };
}
module.exports = { createPromoHandler };

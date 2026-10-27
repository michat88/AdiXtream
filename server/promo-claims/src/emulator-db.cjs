'use strict';

const { fail } = require('./errors.cjs');
const { parseExact, stringifyExact } = require('./exact-json.cjs');
const MAX_BYTES = 8 * 1024 * 1024;

// Deliberately cannot address production or accept a production credential.
// A live transport, hosting and verified identity adapter are separate gates.
function createEmulatorDatabase({ port, namespace, timeoutMs = 10000, maxBytes = MAX_BYTES }) {
  if (!Number.isInteger(port) || port < 1024 || port > 65535 ||
      !/^demo-[a-z0-9-]{1,80}$/.test(namespace) || !Number.isInteger(timeoutMs) || timeoutMs < 1 ||
      !Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_BYTES) throw new Error('EMULATOR_CONFIG_INVALID');
  const url = new URL(`http://127.0.0.1:${port}/.json`);
  url.searchParams.set('ns', namespace);
  async function request(method, body, headers = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let response;
    try {
      const target = new URL(url);
      // Root CAS must retain priorities on unrelated objects and primitives.
      if (method === 'GET') target.searchParams.set('format', 'export');
      response = await fetch(target, { method, headers: { Authorization: 'Bearer owner', 'Content-Type': 'application/json', ...headers },
        ...(body === undefined ? {} : { body }), signal: controller.signal, redirect: 'error' });
      const chunks = [];
      let bytes = 0;
      for await (const chunk of response.body) {
        bytes += chunk.byteLength;
        if (bytes > maxBytes) fail('TEMPORARY_UNAVAILABLE');
        chunks.push(chunk);
      }
      return { status: response.status, text: Buffer.concat(chunks).toString('utf8'), etag: response.headers.get('etag') };
    } catch { fail(method === 'PUT' ? 'UNKNOWN_RESULT' : 'TEMPORARY_UNAVAILABLE'); }
    finally { clearTimeout(timer); if (response?.body && !response.body.locked) await response.body.cancel().catch(() => {}); }
  }
  return {
    async read() {
      const r = await request('GET', undefined, { 'X-Firebase-ETag': 'true' });
      if (r.status !== 200 || !validEtag(r.etag)) fail('TEMPORARY_UNAVAILABLE');
      return { root: parseExact(r.text), etag: r.etag };
    },
    async compareAndSet(etag, root) {
      if (!validEtag(etag)) fail('UNKNOWN_RESULT');
      const body = stringifyExact(root);
      if (Buffer.byteLength(body) > maxBytes) fail('UNKNOWN_RESULT');
      const r = await request('PUT', body, { 'if-match': etag });
      if (r.status === 412) return false;
      if (r.status !== 200) fail('UNKNOWN_RESULT');
      return true;
    },
  };
}
function validEtag(etag) {
  // RTDB returns opaque unquoted base64 ETags (and null_etag for null).
  // Preserve the token verbatim; also accept a quoted HTTP ETag from adapters.
  return typeof etag === 'string' && /^(?:"[^"\r\n]{1,250}"|[A-Za-z0-9_+/=-]{1,250})$/.test(etag);
}
module.exports = { createEmulatorDatabase };

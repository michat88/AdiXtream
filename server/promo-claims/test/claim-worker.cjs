'use strict';
// Test process only: synthetic fixture identities, never a deployment entrypoint.
const { createEmulatorDatabase } = require('../src/emulator-db.cjs');
const { createClaimService } = require('../src/claims.cjs');
const { NOW, ownerA, ownerB } = require('./helpers.cjs');
const db = createEmulatorDatabase({ port: Number(process.env.ADIXTREAM_TEST_PORT), namespace: process.env.ADIXTREAM_TEST_NS });
process.once('message', async message => {
  let released;
  const barrier = new Promise(r => { released = r; });
  process.on('message', event => { if (event.type === 'release') released(); });
  const delegate = { read: () => db.read(), async compareAndSet(etag, root) {
    if (message.pauseBeforeWrite) { message.pauseBeforeWrite = false; process.send({ event: 'BEFORE_WRITE' }); await barrier; }
    const result = await db.compareAndSet(etag, root);
    if (result && message.pauseAfterWrite) { process.send({ event: 'COMMITTED' }); await barrier; }
    return result;
  } };
  try {
    const service = createClaimService({ db: delegate, clock: () => NOW, maxAttempts: 24 });
    const result = await service.claim(message.owner === 'B' ? ownerB : ownerA, message.body);
    process.send({ event: 'RESULT', result }, () => process.exit(0));
  } catch (e) {
    process.send({ event: 'ERROR', code: e.code || 'TEMPORARY_UNAVAILABLE' }, () => process.exit(1));
  }
});

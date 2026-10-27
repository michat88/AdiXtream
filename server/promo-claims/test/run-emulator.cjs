'use strict';
const { spawn } = require('node:child_process');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');
const EXPECTED_SHA = 'b70d99344caf17c98b6f910fa8f6edf32a7c016cb1035e8915f70d38901eb97f';
const jar = process.env.ADIXTREAM_TEST_EMULATOR_JAR;
if (!jar || !fs.existsSync(jar) || createHash('sha256').update(fs.readFileSync(jar)).digest('hex') !== EXPECTED_SHA) {
  console.error('EMULATOR_JAR_MISSING_OR_CHECKSUM_MISMATCH'); process.exit(1);
}
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'adixtream-promo-test-'));
fs.chmodSync(directory, 0o700);
const logPath = path.join(directory, 'emulator.log'), log = fs.openSync(logPath, 'w', 0o600);
const emulator = spawn('java', ['-jar', jar, '--host', '127.0.0.1', '--port', '19002', '--single_project_mode', 'Error'],
  { stdio: ['ignore', log, log], cwd: directory });
let startError, tests;
emulator.on('error', e => { startError = e; });
function stop() { tests?.kill('SIGTERM'); emulator.kill('SIGTERM'); }
process.on('SIGINT', stop); process.on('SIGTERM', stop);
(async () => {
  try {
    let ready = false;
    for (let i = 0; i < 150; i++) {
      if (startError || emulator.exitCode !== null) throw new Error('EMULATOR_START_FAILED');
      if (fs.readFileSync(logPath, 'utf8').includes('Listening at 127.0.0.1:19002')) {
        try {
          const response = await fetch('http://127.0.0.1:19002/.json?ns=demo-adixtream-promo-v1',
            { headers: { Authorization: 'Bearer owner' }, signal: AbortSignal.timeout(500), redirect: 'error' });
          await response.text();
          if (response.status === 200) { ready = true; break; }
        } catch { /* The emulator may announce the listener before it is bound. */ }
      }
      await delay(100);
    }
    if (!ready) throw new Error('EMULATOR_START_TIMEOUT');
    tests = spawn(process.execPath, ['--test', path.join(__dirname, 'emulator.test.cjs')],
      { stdio: 'inherit', env: { ...process.env, ADIXTREAM_TEST_PORT: '19002', ADIXTREAM_TEST_NS: 'demo-adixtream-promo-v1' } });
    process.exitCode = await new Promise((resolve, reject) => { tests.once('exit', code => resolve(code ?? 1)); tests.once('error', reject); });
  } catch { console.error('EMULATOR_TEST_RUN_FAILED'); process.exitCode = 1; }
  finally {
    stop();
    if (emulator.exitCode === null && emulator.signalCode === null) {
      await Promise.race([new Promise(r => emulator.once('exit', r)), delay(3000)]);
      if (emulator.exitCode === null && emulator.signalCode === null) emulator.kill('SIGKILL');
    }
    fs.closeSync(log);
    fs.rmSync(directory, { recursive: true, force: true });
  }
})();

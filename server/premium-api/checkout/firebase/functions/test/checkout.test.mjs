import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import express from 'express';
import { test } from 'node:test';
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { createCheckoutStore } from '../store.mjs';
import { createSandboxCheckout, EMULATOR_PROJECT } from '../checkout.mjs';

if (process.env.GCLOUD_PROJECT !== EMULATOR_PROJECT || !process.env.FIRESTORE_EMULATOR_HOST ||
    !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
    throw new Error('Run with the dedicated Firebase demo project and both emulators.');
}
const app = initializeApp({ projectId: EMULATOR_PROJECT }, 'checkout-tests');
const firestore = getFirestore(app);

const DAY = 86400000;
const SECRET = 'fake-signature-secret';
const GATEWAY = 'https://api.sandbox.midtrans.com';

function fixture() {
    const root = firestore.collection('checkoutTests').doc(crypto.randomUUID());
    const db = createCheckoutStore(root);
    let now = Date.UTC(2026, 10, 24);
    let chargeCount = 0;
    let loseChargeResponse = false;
    const payments = new Map();
    const env = {
        CHECKOUT_SANDBOX_ENABLED: 'enabled', emulated: true,
        FIREBASE_PROJECT_ID: EMULATOR_PROJECT,
        MIDTRANS_SANDBOX_SIGNATURE_SECRET: SECRET,
        MIDTRANS_SANDBOX_SERVER_KEY: 'SB-Mid-server-fake',
    };
    const handler = createSandboxCheckout({
        db,
        clock: () => now,
        verifyIdToken: async token => {
            const uid = token.split('.')[0];
            return { uid, email_verified: uid !== 'unverified' };
        },
        fetchImpl: async (url, options) => {
            assert.equal(new URL(url).origin, GATEWAY);
            assert.equal(options.redirect, 'error');
            assert.equal(options.headers.Authorization, 'Basic ' + btoa(env.MIDTRANS_SANDBOX_SERVER_KEY + ':'));
            if (url.endsWith('/charge')) {
                chargeCount++;
                const request = JSON.parse(options.body);
                assert.equal(request.payment_type, 'qris');
                assert.equal(request.transaction_details.gross_amount, 10000);
                assert.equal(request.custom_expiry.expiry_duration, 60);
                const orderId = request.transaction_details.order_id;
                assert.match(orderId, /^ADX-SB-/);
                assert.equal(payments.has(orderId), false);
                const row = {
                    order_id: orderId, gross_amount: '10000.00', currency: 'IDR',
                    transaction_id: crypto.randomUUID(), status_code: '201', payment_type: 'qris',
                    transaction_status: 'pending', fraud_status: 'accept',
                };
                payments.set(orderId, row);
                if (loseChargeResponse) throw new Error('synthetic lost response');
                return Response.json(row);
            }
            const id = new URL(url).pathname.split('/')[2];
            const row = payments.get(id);
            return row ? Response.json(row) : Response.json({ status_code: '404' }, { status: 404 });
        },
    });
    async function call(path, body, uid = 'owner', environment = env) {
        const headers = uid ? { Authorization: 'Bearer ' + uid + '.synthetic.token' } : {};
        const method = body === undefined ? 'GET' : 'POST';
        if (method === 'POST') headers['Content-Type'] = 'application/json';
        const res = await handler(new Request('https://checkout.invalid/checkout' + path, {
            method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        }), environment);
        return { status: res.status, body: await res.json() };
    }
    const checksum = (algorithm, text) => createHash(algorithm).update(text).digest('hex');
    function hook(id, statusCode = '200', amount = '10000.00') {
        return {
            order_id: id, status_code: statusCode, gross_amount: amount, transaction_status: 'settlement',
            signature_key: checksum('sha512', id + statusCode + amount + SECRET),
        };
    }

    return {
        env, payments, call, hook,
        time: () => now,
        advance: (ms) => { now += ms; },
        charges: () => chargeCount,
        loseCharge: () => { loseChargeResponse = true; },
        voucher(code, days, validUntil) {
            return root.collection('vouchers').doc(checksum('sha256', code)).set({ days, valid_until: validUntil });
        },
    };
}

const orderRequest = () => ({ plan: 'premium-30d', requestId: crypto.randomUUID() });
const settle = (f, order) => Object.assign(f.payments.get(order.id),
    { transaction_status: 'settlement', status_code: '200' });

test('sandbox config, authenticated owner and verified email are required', async () => {
    const f = fixture();
    assert.equal((await f.call('/plans', undefined, null)).status, 401);
    assert.equal((await f.call('/plans', undefined, 'unverified')).status, 403);
    assert.equal((await f.call('/plans', undefined, 'owner', { ...f.env, FIREBASE_PROJECT_ID: 'adixtream-premium' })).status, 503);
    assert.equal((await f.call('/plans', undefined, 'owner', { ...f.env, CHECKOUT_SANDBOX_ENABLED: 'disabled' })).status, 503);
    assert.equal((await f.call('/orders', orderRequest(), 'owner', {
        ...f.env, MIDTRANS_SANDBOX_SERVER_KEY: 'Mid-server-fake',
    })).status, 503);
    const list = await f.call('/plans');
    assert.deepEqual(list.body, { sandbox: true, plans: [{ id: 'premium-30d', days: 30, amount: 10000, currency: 'IDR' }] });
    assert.equal(f.charges(), 0);
});

test('one open order resumes after a lost response, never posts another charge', async () => {
    const f = fixture(), request = orderRequest();
    f.loseCharge();
    assert.equal((await f.call('/orders', request)).status, 503);
    const response = await f.call('/orders/open');
    assert.equal(response.body.state, 'PENDING');
    assert.match(response.body.qrUrl, /^https:\/\/api\.sandbox\.midtrans\.com\/v2\/qris\//);
    assert.equal(response.body.expiresAt - response.body.serverTime, 3600000);
    assert.equal((await f.call('/orders', orderRequest())).body.id, response.body.id);
    assert.equal((await f.call('/orders', request)).body.id, response.body.id);
    assert.equal((await f.call('/orders/' + response.body.id, undefined, 'other')).status, 404);
    assert.equal(f.charges(), 1);
});

test('forged notification or mismatched payment never grants entitlement', async () => {
    const f = fixture();
    const order = (await f.call('/orders', { ...orderRequest(), amount: 1, days: 365 })).body;
    assert.equal(order.amount, 10000);
    assert.equal(order.days, 30);
    const notification = f.hook(order.id);
    assert.equal((await f.call('/midtrans/webhook', { ...notification, signature_key: 'a'.repeat(128) }, null)).status, 401);
    // Even with a signed payload saying settlement, fetch the authoritative status.
    assert.equal((await f.call('/midtrans/webhook', notification, null)).status, 200);
    assert.equal((await f.call('/entitlement')).body.active, false);
    settle(f, order);
    f.payments.get(order.id).gross_amount = '10001.00';
    assert.equal((await f.call('/midtrans/webhook', notification, null)).status, 409);
    assert.equal((await f.call('/entitlement')).body.active, false);
});

test('two callbacks and a status poll apply exactly one paid extension per order', async () => {
    const f = fixture(), request = orderRequest();
    const order = (await f.call('/orders', request)).body;
    settle(f, order);
    const results = await Promise.all([
        f.call('/midtrans/webhook', f.hook(order.id), null),
        f.call('/midtrans/webhook', f.hook(order.id), null),
        f.call('/orders/' + order.id),
    ]);
    assert(results.every(x => x.status === 200));
    assert.equal((await f.call('/entitlement')).body.expiresAt, f.time() + 30 * DAY);
    assert.equal((await f.call('/orders', request)).body.state, 'PAID');
    f.advance(DAY);
    const next = (await f.call('/orders', orderRequest())).body;
    settle(f, next);
    await f.call('/orders/' + next.id);
    assert.equal((await f.call('/entitlement')).body.expiresAt, f.time() + 59 * DAY);
    assert.equal(f.charges(), 2);
});

test('expired order hides QR and does not grant Premium', async () => {
    const f = fixture();
    const order = (await f.call('/orders', orderRequest())).body;
    f.advance(3600001);
    Object.assign(f.payments.get(order.id), { transaction_status: 'expire', status_code: '202' });
    const expired = await f.call('/orders/' + order.id);
    assert.equal(expired.body.state, 'EXPIRED');
    assert.equal(expired.body.qrUrl, null);
    assert.deepEqual((await f.call('/entitlement')).body, { active: false, expiresAt: null });
    assert.notEqual((await f.call('/orders', orderRequest())).body.id, order.id);
});

test('voucher grants once, including across concurrent accounts; expired codes are rejected', async () => {
    const f = fixture(), code = 'DEMOABCD2345';
    await f.voucher(code, 7, f.time() + DAY);
    await f.voucher('OLDCODEA2345', 7, f.time() - 1);
    assert.equal((await f.call('/vouchers', { code: 'OLDCODEA2345' })).status, 409);
    const result = await Promise.all([f.call('/vouchers', { code }), f.call('/vouchers', { code }, 'other')]);
    assert.equal(result.filter(x => x.status === 200).length, 1);
    const owner = result[0].status === 200 ? 'owner' : 'other';
    assert.equal((await f.call('/vouchers', { code }, owner)).body.expiresAt, f.time() + 7 * DAY);
    assert.equal((await f.call('/entitlement', undefined, owner)).body.expiresAt, f.time() + 7 * DAY);
});

test('simultaneous requests reserve one order and repeated IDs retain the paid order', async () => {
    const f = fixture();
    const first = orderRequest();
    const second = orderRequest();
    const [left, right] = await Promise.all([f.call('/orders', first), f.call('/orders', second)]);
    assert.equal(left.status, 200);
    assert.equal(right.status, 200);
    assert.equal(left.body.id, right.body.id);
    settle(f, left.body);
    await f.call('/orders/' + left.body.id);
    assert.equal((await f.call('/orders', first)).body.state, 'PAID');
    assert.equal((await f.call('/orders', second)).body.state, 'PAID');
    assert.equal(f.charges(), 1);
});

test('HTTP function checks Auth Emulator tokens and Firestore rejects client writes', async t => {
    const auth = getAuth(app);
    const email = crypto.randomUUID() + '@example.test';
    const password = 'dummy-checkout-password';
    const user = await auth.createUser({ email, password, emailVerified: true });
    const authOrigin = 'http://' + process.env.FIREBASE_AUTH_EMULATOR_HOST;
    const login = await fetch(authOrigin + '/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, returnSecureToken: true }),
    });
    assert.equal(login.status, 200);
    const { idToken } = await login.json();
    process.env.FUNCTIONS_EMULATOR = 'true';
    process.env.CHECKOUT_SANDBOX_ENABLED = 'enabled';
    process.env.MIDTRANS_SANDBOX_SERVER_KEY = 'SB-Mid-server-fake';
    process.env.MIDTRANS_SANDBOX_SIGNATURE_SECRET = SECRET;
    const { checkoutSandbox } = await import('../index.mjs');
    const http = express();
    http.use(express.raw({ type: 'application/json', limit: '16kb' }));
    http.use((req, res) => {
        req.rawBody = req.body;
        return checkoutSandbox(req, res);
    });
    const server = http.listen(0, '127.0.0.1');
    t.after(() => new Promise(resolve => server.close(resolve)));
    await once(server, 'listening');
    const origin = 'http://127.0.0.1:' + server.address().port;
    const endpoint = origin + '/checkout/plans';
    assert.equal((await fetch(endpoint, { signal: AbortSignal.timeout(5000) })).status, 401);
    const authHeader = { Authorization: 'Bearer ' + idToken };
    const result = await fetch(endpoint, { headers: authHeader, signal: AbortSignal.timeout(5000) });
    assert.equal(result.status, 200);
    assert.equal((await result.json()).plans[0].amount, 10_000);
    const firestoreOrigin = 'http://' + process.env.FIRESTORE_EMULATOR_HOST;
    const write = await fetch(firestoreOrigin + '/v1/projects/' + EMULATOR_PROJECT +
        '/databases/(default)/documents/checkoutSandbox/v1/accounts/forbidden', {
        method: 'PATCH', headers: { ...authHeader, 'Content-Type': 'application/json' },
        signal: AbortSignal.timeout(5000),
        body: JSON.stringify({ fields: { expiresAt: { integerValue: '9999999999' } } }),
    });
    assert.equal(write.status, 403);
    const forged = await fetch(origin + '/checkout/midtrans/webhook', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        signal: AbortSignal.timeout(5000), body: JSON.stringify({ signature_key: 'fake' }),
    });
    assert.equal(forged.status, 401);
    await auth.updateUser(user.uid, { disabled: true });
    assert.equal((await fetch(endpoint, { headers: authHeader, signal: AbortSignal.timeout(5000) })).status, 401);
});

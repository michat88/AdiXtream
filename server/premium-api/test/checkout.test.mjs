import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import { createSandboxCheckout } from '../checkout/worker.mjs';

const schema = readFileSync(new URL('../checkout/schema.sql', import.meta.url), 'utf8');
const authSchema = readFileSync(new URL('../migrations/0001_premium.sql', import.meta.url), 'utf8');
const DAY = 86400000;
const SECRET = 'fake-signature-secret';
const GATEWAY = 'https://api.sandbox.midtrans.com';

function fixture(t) {
    const sqlite = new DatabaseSync(':memory:');
    sqlite.exec(authSchema);
    sqlite.exec(schema);
    t.after(() => sqlite.close());
    const db = {
        prepare(sql) {
            const stmt = sqlite.prepare(sql.replace(/\?\d+/g, '?'));
            return {
                bind(...values) {
                    return {
                        async run() { return stmt.run(...values); },
                        async first() { return stmt.get(...values) ?? null; },
                    };
                },
            };
        },
    };
    let now = Date.UTC(2026, 10, 24);
    let chargeCount = 0;
    let loseChargeResponse = false;
    const payments = new Map();
    const env = {
        CHECKOUT_SANDBOX_ENABLED: 'enabled', CHECKOUT_DB: db,
        FIREBASE_PROJECT_ID: 'adixtream-checkout-staging',
        MIDTRANS_SANDBOX_SIGNATURE_SECRET: SECRET,
        MIDTRANS_SANDBOX_SERVER_KEY: 'SB-Mid-server-fake',
    };
    const handler = createSandboxCheckout({
        clock: () => now,
        verifier: async (token, project) => {
            assert.equal(project, 'adixtream-checkout-staging');
            const uid = token.split('.')[0];
            return { sub: uid, email_verified: uid !== 'unverified', auth_time: 100 };
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
        env, payments, call, hook, sqlite,
        time: () => now,
        advance: (ms) => { now += ms; },
        charges: () => chargeCount,
        loseCharge: () => { loseChargeResponse = true; },
        voucher(code, days, validUntil) {
            sqlite.prepare('INSERT INTO checkout_vouchers(code_hash,days,valid_until) VALUES (?,?,?)')
                .run(checksum('sha256', code), days, validUntil);
        },
    };
}

const orderRequest = () => ({ plan: 'premium-30d', requestId: crypto.randomUUID() });
const settle = (f, order) => Object.assign(f.payments.get(order.id),
    { transaction_status: 'settlement', status_code: '200' });

test('sandbox config, authenticated owner and verified email are required', async t => {
    const f = fixture(t);
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

test('one open order resumes after a lost response, never posts another charge', async t => {
    const f = fixture(t), request = orderRequest();
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

test('forged notification or mismatched payment never grants entitlement', async t => {
    const f = fixture(t);
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

test('two callbacks and a status poll apply exactly one paid extension per order', async t => {
    const f = fixture(t), request = orderRequest();
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

test('expired order hides QR and does not grant Premium', async t => {
    const f = fixture(t);
    const order = (await f.call('/orders', orderRequest())).body;
    f.advance(3600001);
    Object.assign(f.payments.get(order.id), { transaction_status: 'expire', status_code: '202' });
    const expired = await f.call('/orders/' + order.id);
    assert.equal(expired.body.state, 'EXPIRED');
    assert.equal(expired.body.qrUrl, null);
    assert.deepEqual((await f.call('/entitlement')).body, { active: false, expiresAt: null });
    assert.notEqual((await f.call('/orders', orderRequest())).body.id, order.id);
});

test('voucher grants once, including across concurrent accounts; expired codes are rejected', async t => {
    const f = fixture(t), code = 'DEMOABCD2345';
    f.voucher(code, 7, f.time() + DAY);
    f.voucher('OLDCODEA2345', 7, f.time() - 1);
    assert.equal((await f.call('/vouchers', { code: 'OLDCODEA2345' })).status, 409);
    const result = await Promise.all([f.call('/vouchers', { code }), f.call('/vouchers', { code }, 'other')]);
    assert.equal(result.filter(x => x.status === 200).length, 1);
    const owner = result[0].status === 200 ? 'owner' : 'other';
    assert.equal((await f.call('/vouchers', { code }, owner)).body.expiresAt, f.time() + 7 * DAY);
    assert.equal((await f.call('/entitlement', undefined, owner)).body.expiresAt, f.time() + 7 * DAY);
});

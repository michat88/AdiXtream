import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { after, beforeEach, test } from 'node:test';
import { PGlite } from '@electric-sql/pglite';
import { createCheckoutStore } from '../functions/checkout-sandbox/store.mjs';
import { createSandboxCheckout } from '../functions/checkout-sandbox/checkout.mjs';
import { createFirebaseVerifier } from '../functions/checkout-sandbox/auth.mjs';

const postgres = new PGlite();
await postgres.exec('create role anon; create role authenticated; create role service_role bypassrls;');
const migration = readFileSync(new URL('../migrations/20261124145335_adixtream_checkout_sandbox.sql', import.meta.url), 'utf8');
await postgres.exec(migration);
beforeEach(() => postgres.exec('truncate checkout_private.accounts, checkout_private.vouchers, checkout_private.rate_limits cascade'));
after(() => postgres.close());
const DAY = 86400000;
const SECRET = 'fake-signature-secret';
const GATEWAY = 'https://api.sandbox.midtrans.com';

function fixture() {
    const databaseUrl = 'https://db.example.test';
    const db = createCheckoutStore(databaseUrl, 'sb_secret_dummy', async (url, options) => {
        assert.equal(url, databaseUrl + '/rest/v1/rpc/adixtream_checkout_sandbox');
        assert.equal(options.headers.apikey, 'sb_secret_dummy');
        const { operation, input } = JSON.parse(options.body);
        try {
            const result = await postgres.transaction(async tx => {
                await tx.exec('set local role service_role');
                return tx.query('select public.adixtream_checkout_sandbox($1, $2::jsonb) as value', [operation, JSON.stringify(input)]);
            });
            return Response.json(result.rows[0].value);
        } catch (error) {
            return Response.json({ code: error.code, message: error.message }, { status: 400 });
        }
    });
    let now = Date.UTC(2026, 10, 24);
    let chargeCount = 0;
    let loseChargeResponse = false;
    const payments = new Map();
    const env = {
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
        const res = await handler(new Request('https://checkout.invalid/functions/v1/checkout-sandbox/checkout' + path, {
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
            return postgres.query('insert into checkout_private.vouchers(code_hash, days, valid_until) values ($1, $2, $3)',
                [checksum('sha256', code), days, validUntil]);
        },
    };
}

const orderRequest = () => ({ plan: 'premium-30d', requestId: crypto.randomUUID() });
const settle = (f, order) => Object.assign(f.payments.get(order.id),
    { transaction_status: 'settlement', status_code: '200' });

test('sandbox key, authenticated owner and verified email are required', async () => {
    const f = fixture();
    assert.equal((await f.call('/plans', undefined, null)).status, 401);
    assert.equal((await f.call('/plans', undefined, 'unverified')).status, 403);
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


test('client roles cannot read ledgers or execute the privileged RPC', async () => {
    for (const role of ['anon', 'authenticated']) {
        const { rows: [acl] } = await postgres.query(
            "select has_schema_privilege($1, 'checkout_private', 'usage') as ledger, " +
            "has_function_privilege($1, 'public.adixtream_checkout_sandbox(text,jsonb)', 'execute') as rpc",
            [role]);
        assert.deepEqual(acl, { ledger: false, rpc: false });
    }
});

test('Firebase verifier requires a matching signed token and rejects revoked sessions', async () => {
    const projectId = 'adixtream-checkout-sandbox';
    const now = Date.UTC(2026, 10, 24);
    const seconds = now / 1000;
    const keys = await crypto.subtle.generateKey({
        name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]),
        hash: 'SHA-256',
    }, true, ['sign', 'verify']);
    const jwk = { ...(await crypto.subtle.exportKey('jwk', keys.publicKey)), kid: 'test' };
    let validSince = seconds - 120;
    let lookups = 0;
    const verifier = createFirebaseVerifier(projectId, 'fake', async (url, req) => {
        if (new URL(url).hostname === 'www.googleapis.com') return Response.json({ keys: [jwk] });
        assert.equal(new URL(url).hostname, 'identitytoolkit.googleapis.com');
        assert.equal(JSON.parse(req.body).idToken, token);
        lookups++;
        return Response.json({ users: [{ localId: 'owner', emailVerified: true, validSince: String(validSince) }] });
    }, () => now);
    const encode = json => Buffer.from(JSON.stringify(json)).toString('base64url');
    const header = encode({ alg: 'RS256', kid: 'test' });
    const payload = encode({ aud: projectId, iss: 'https://securetoken.google.com/' + projectId, sub: 'owner',
        email_verified: true, auth_time: seconds - 60, iat: seconds - 30, exp: seconds + 300 });
    const unsigned = header + '.' + payload;
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', keys.privateKey, Buffer.from(unsigned));
    const token = unsigned + '.' + Buffer.from(signature).toString('base64url');
    assert.deepEqual(await verifier(token), { uid: 'owner', email_verified: true });
    await assert.rejects(verifier(unsigned + '.AAAA'), { code: 'AUTH_INVALID' });
    validSince = seconds - 1;
    await assert.rejects(verifier(token), { code: 'AUTH_INVALID' });
    assert.equal(lookups, 2);
});

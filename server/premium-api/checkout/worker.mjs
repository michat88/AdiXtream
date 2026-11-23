import { ApiError, authenticate, checkRevocation, createTokenVerifier, fail, rateLimit } from '../src/auth.mjs';

const GATEWAY = 'https://api.sandbox.midtrans.com';
const PLAN = Object.freeze({ id: 'premium-30d', days: 30, amount: 10_000, currency: 'IDR' });
const QR_DURATION = 60;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ORDER_ID = new RegExp('^ADX-SB-' + UUID.source.slice(1));
const TRANSACTION_ID = /^[a-zA-Z0-9-]{8,72}$/;
const VOUCHER = /^[A-Z2-9]{12,40}$/;
const encoder = new TextEncoder();
const PAYMENT_STATES = new Map([
    ['pending', 'PENDING'], ['settlement', 'PAID'], ['expire', 'EXPIRED'],
    ['cancel', 'CANCELED'], ['deny', 'CANCELED'],
]);

function json(body, status = 200) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
    });
}

async function readJson(request) {
    if (request.headers.get('content-type')?.split(';')[0] !== 'application/json' || !request.body) {
        fail('REQUEST_INVALID', 400);
    }
    const reader = request.body.getReader();
    const bytes = new Uint8Array(16_384);
    let length = 0;
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            if (length + value.length > bytes.length) fail('PAYLOAD_TOO_LARGE', 413);
            bytes.set(value, length);
            length += value.length;
        }
        const body = JSON.parse(new TextDecoder().decode(bytes.subarray(0, length)));
        if (!body || typeof body !== 'object' || Array.isArray(body)) fail('REQUEST_INVALID', 400);
        return body;
    } catch (error) {
        if (error instanceof ApiError) throw error;
        fail('REQUEST_INVALID', 400);
    } finally {
        await reader.cancel().catch(() => {});
    }
}

async function hash(algorithm, text) {
    const bytes = new Uint8Array(await crypto.subtle.digest(algorithm, encoder.encode(text)));
    return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}

function gateway(env, fetchImpl) {
    const key = env.MIDTRANS_SANDBOX_SERVER_KEY;
    if (typeof key !== 'string' || !key.startsWith('SB-Mid-server-')) fail('SANDBOX_KEY_REQUIRED', 503);
    const headers = {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: 'Basic ' + btoa(key + ':'),
    };

    async function call(path, method, body) {
        const response = await fetchImpl(GATEWAY + path, {
            method,
            headers,
            body: body && JSON.stringify(body),
            redirect: 'error',
            signal: AbortSignal.timeout(8000),
        });
        if (method === 'GET' && response.status === 404) return null;
        if (!response.ok) fail('GATEWAY_UNAVAILABLE', 503);
        const result = await response.json();
        if (method === 'GET' && result.status_code === '404') return null;
        return result;
    }

    return {
        status: id => call('/v2/' + id + '/status', 'GET'),
        charge: order => call('/v2/charge', 'POST', {
            payment_type: 'qris',
            transaction_details: { order_id: order.id, gross_amount: order.amount },
            custom_expiry: {
                order_time: new Date(order.created_at + 7 * 3600000).toISOString().slice(0, 19).replace('T', ' ') + ' +0700',
                expiry_duration: QR_DURATION,
                unit: 'minute',
            },
        }),
    };
}

function orders(db) {
    return {
        byId: id => db.prepare('SELECT * FROM checkout_orders WHERE id = ?').bind(id).first(),
        byRequest: (uid, requestId) => db.prepare('SELECT * FROM checkout_orders WHERE uid = ? AND request_id = ?')
            .bind(uid, requestId).first(),
        open: uid => db.prepare("SELECT * FROM checkout_orders WHERE uid = ? AND state IN ('CREATING','PENDING')")
            .bind(uid).first(),
    };
}

async function entitlement(db, uid, now) {
    const row = await db.prepare('SELECT expires_at FROM checkout_entitlements WHERE uid = ?').bind(uid).first();
    return { active: (row?.expires_at ?? 0) > now, expiresAt: row?.expires_at ?? null };
}

function orderView(order, now) {
    return {
        id: order.id, state: order.state, plan: PLAN.id, amount: order.amount,
        currency: PLAN.currency, days: order.days, expiresAt: order.expires_at, serverTime: now,
        qrUrl: order.state === 'PENDING' && order.expires_at > now ? order.qr_url : null,
    };
}

function qrUrl(payment) {
    const actions = Array.isArray(payment.actions) ? payment.actions : [];
    const valid = /^https:\/\/api\.sandbox\.midtrans\.com\/v[24]\/qris\/[a-zA-Z0-9-]+\/qr-code$/;
    const action = actions.find(item => item?.method === 'GET' &&
        ['generate-qr-code', 'generate-qr-code-v2'].includes(item.name) && valid.test(item.url ?? ''));
    return action?.url ?? null;
}

async function applyStatus(db, order, payment, now) {
    if (!payment || payment.order_id !== order.id || payment.payment_type !== 'qris' ||
        (payment.currency != null && payment.currency !== PLAN.currency) ||
        ![String(order.amount), String(order.amount) + '.00'].includes(String(payment.gross_amount)) ||
        typeof payment.transaction_id !== 'string' || !TRANSACTION_ID.test(payment.transaction_id) ||
        (order.transaction_id && order.transaction_id !== payment.transaction_id)) {
        fail('GATEWAY_ORDER_MISMATCH', 409);
    }

    const state = PAYMENT_STATES.get(payment.transaction_status);
    if (!state || (state === 'PAID' && (String(payment.status_code) !== '200' ||
        (payment.fraud_status != null && payment.fraud_status !== 'accept')))) {
        fail('GATEWAY_STATUS_UNSUPPORTED', 409);
    }

    // Status responses can omit QR actions after a charge response was lost.
    const pendingQr = state === 'PENDING'
        ? qrUrl(payment) ?? order.qr_url ?? GATEWAY + '/v2/qris/' + payment.transaction_id + '/qr-code'
        : null;
    // A verified late settlement is accepted; pending or terminal events never undo a settled order.
    await db.prepare(`UPDATE checkout_orders
        SET state = ?, transaction_id = ?, qr_url = COALESCE(?, qr_url), paid_at = ?
        WHERE id = ? AND (transaction_id IS NULL OR transaction_id = ?) AND state <> 'PAID'
        AND (? = 'PAID' OR state IN ('CREATING','PENDING'))`)
        .bind(state, payment.transaction_id, pendingQr, state === 'PAID' ? now : null,
            order.id, payment.transaction_id, state).run();
    return orders(db).byId(order.id);
}

async function refresh(db, remote, order, now) {
    if (order.state === 'PAID') return order;
    const payment = await remote.status(order.id);
    if (payment) return applyStatus(db, order, payment, now);
    // An intent whose creation never reached Midtrans can be released after its QR deadline.
    if (order.state === 'CREATING' && order.expires_at <= now) {
        await db.prepare("UPDATE checkout_orders SET state = 'EXPIRED' WHERE id = ? AND state = 'CREATING'")
            .bind(order.id).run();
        return orders(db).byId(order.id);
    }
    return order;
}

async function startOrder(db, remote, uid, body, now) {
    if (body.plan !== PLAN.id || typeof body.requestId !== 'string' || !UUID.test(body.requestId)) {
        fail('REQUEST_INVALID', 400);
    }
    const lookup = orders(db);
    await db.prepare(`INSERT INTO checkout_orders(id, uid, request_id, amount, days, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`)
        .bind('ADX-SB-' + crypto.randomUUID(), uid, body.requestId, PLAN.amount, PLAN.days,
            now, now + QR_DURATION * 60000).run();
    const order = await lookup.byRequest(uid, body.requestId) ?? await lookup.open(uid);
    if (!order) fail('RETRY_ORDER', 409);
    if (order.state !== 'CREATING') {
        return order.state === 'PENDING' ? refresh(db, remote, order, now) : order;
    }

    const lease = await db.prepare(`UPDATE checkout_orders SET retry_at = ?
        WHERE id = ? AND state = 'CREATING' AND retry_at <= ? RETURNING id`)
        .bind(now + 20000, order.id, now).first();
    if (!lease) return lookup.byId(order.id);
    const existing = await refresh(db, remote, order, now);
    if (existing.state !== 'CREATING') return existing;
    return applyStatus(db, order, await remote.charge(order), now);
}

async function redeem(db, uid, rawCode, now) {
    const code = typeof rawCode === 'string' ? rawCode.trim().toUpperCase() : '';
    if (!VOUCHER.test(code)) fail('VOUCHER_INVALID', 400);
    const codeHash = await hash('SHA-256', code);
    const applied = await db.prepare(`UPDATE checkout_vouchers SET redeemed_uid = ?, redeemed_at = ?
        WHERE code_hash = ? AND redeemed_uid IS NULL AND valid_until > ? RETURNING code_hash`)
        .bind(uid, now, codeHash, now).first();
    if (!applied) {
        const replay = await db.prepare('SELECT code_hash FROM checkout_vouchers WHERE code_hash = ? AND redeemed_uid = ?')
            .bind(codeHash, uid).first();
        if (!replay) fail('VOUCHER_UNAVAILABLE', 409);
    }
    return entitlement(db, uid, now);
}

async function verifyNotification(body, secret) {
    if (typeof secret !== 'string' || !secret) fail('WEBHOOK_NOT_READY', 503);
    if (typeof body.order_id !== 'string' || !ORDER_ID.test(body.order_id) ||
        typeof body.status_code !== 'string' || !/^\d{3}$/.test(body.status_code) ||
        typeof body.gross_amount !== 'string' || !/^\d{1,12}(\.\d{2})?$/.test(body.gross_amount) ||
        typeof body.signature_key !== 'string' || !/^[0-9a-f]{128}$/.test(body.signature_key)) {
        fail('WEBHOOK_INVALID', 401);
    }
    const expected = await hash('SHA-512', body.order_id + body.status_code + body.gross_amount + secret);
    let difference = 0;
    for (let i = 0; i < expected.length; i++) {
        difference |= expected.charCodeAt(i) ^ body.signature_key.charCodeAt(i);
    }
    if (difference !== 0) fail('WEBHOOK_INVALID', 401);
}

export function createSandboxCheckout({ verifier = createTokenVerifier(), fetchImpl = fetch, clock = Date.now } = {}) {
    return async (request, env) => {
        try {
            if (env.CHECKOUT_SANDBOX_ENABLED !== 'enabled' || !env.CHECKOUT_DB ||
                !env.FIREBASE_PROJECT_ID || env.FIREBASE_PROJECT_ID === 'adixtream-premium') {
                fail('CHECKOUT_LOCKED', 503);
            }
            const db = env.CHECKOUT_DB.withSession?.('first-primary') ?? env.CHECKOUT_DB;
            const { pathname, search } = new URL(request.url);
            const now = clock();
            if (search || !['GET', 'POST'].includes(request.method)) fail('REQUEST_INVALID', 400);

            if (pathname === '/checkout/midtrans/webhook' && request.method === 'POST') {
                const body = await readJson(request);
                await verifyNotification(body, env.MIDTRANS_SANDBOX_SIGNATURE_SECRET);
                const order = await orders(db).byId(body.order_id);
                if (order && order.state !== 'PAID') {
                    const payment = await gateway(env, fetchImpl).status(order.id);
                    if (!payment) fail('GATEWAY_UNAVAILABLE', 503);
                    await applyStatus(db, order, payment, now);
                }
                return json({ received: true });
            }

            const principal = await authenticate(request, env, async (token, project) => {
                const claims = await verifier(token, project);
                if (claims.email_verified !== true) fail('EMAIL_VERIFICATION_REQUIRED', 403);
                return claims;
            });
            await checkRevocation(db, principal);
            await rateLimit({ TEST_DB: db }, principal.uid, now);
            const uid = principal.uid;

            if (pathname === '/checkout/plans' && request.method === 'GET') {
                return json({ sandbox: true, plans: [PLAN] });
            }
            if (pathname === '/checkout/entitlement' && request.method === 'GET') {
                return json(await entitlement(db, uid, now));
            }
            if (pathname === '/checkout/vouchers' && request.method === 'POST') {
                return json(await redeem(db, uid, (await readJson(request)).code, now));
            }
            if (pathname === '/checkout/orders' && request.method === 'POST') {
                const order = await startOrder(db, gateway(env, fetchImpl), uid, await readJson(request), now);
                return json(orderView(order, now));
            }
            if (pathname === '/checkout/orders/open' && request.method === 'GET') {
                const order = await orders(db).open(uid);
                return json(order ? orderView(await refresh(db, gateway(env, fetchImpl), order, now), now) : null);
            }
            const orderId = /^\/checkout\/orders\/([^/]+)$/.exec(pathname)?.[1];
            if (orderId && ORDER_ID.test(orderId) && request.method === 'GET') {
                const order = await orders(db).byId(orderId);
                if (!order || order.uid !== uid) fail('ORDER_NOT_FOUND', 404);
                return json(orderView(await refresh(db, gateway(env, fetchImpl), order, now), now));
            }
            fail('NOT_FOUND', 404);
        } catch (error) {
            if (error instanceof ApiError) return json({ error: error.code }, error.status);
            return json({ error: 'TEMPORARY_UNAVAILABLE' }, 503);
        }
    };
}

export default { fetch: createSandboxCheckout() };

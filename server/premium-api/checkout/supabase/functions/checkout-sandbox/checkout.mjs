import { ApiError, fail } from './errors.mjs';

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
    return db.applyStatus(order.id, {
        state, transaction_id: payment.transaction_id, qr_url: pendingQr,
    }, now);
}

async function refresh(db, remote, order, now) {
    if (order.state === 'PAID') return order;
    const payment = await remote.status(order.id);
    if (payment) return applyStatus(db, order, payment, now);
    // An intent whose creation never reached Midtrans can be released after its QR deadline.
    if (order.state === 'CREATING' && order.expires_at <= now) {
        return db.expireCreation(order.id);
    }
    return order;
}

async function startOrder(db, remote, uid, body, now) {
    if (body.plan !== PLAN.id || typeof body.requestId !== 'string' || !UUID.test(body.requestId)) {
        fail('REQUEST_INVALID', 400);
    }
    const order = await db.reserve({
        id: 'ADX-SB-' + crypto.randomUUID(), uid, request_id: body.requestId,
        amount: PLAN.amount, days: PLAN.days, state: 'CREATING', created_at: now,
        expires_at: now + QR_DURATION * 60000, retry_at: 0, transaction_id: null, qr_url: null, paid_at: null,
    });
    if (order.state !== 'CREATING') {
        return order.state === 'PENDING' ? refresh(db, remote, order, now) : order;
    }

    if (!await db.claimCharge(order.id, now)) return db.byId(order.id);
    const existing = await refresh(db, remote, order, now);
    if (existing.state !== 'CREATING') return existing;
    return applyStatus(db, order, await remote.charge(order), now);
}

async function redeem(db, uid, rawCode, now) {
    const code = typeof rawCode === 'string' ? rawCode.trim().toUpperCase() : '';
    if (!VOUCHER.test(code)) fail('VOUCHER_INVALID', 400);
    return db.redeem(uid, await hash('SHA-256', code), now);
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

export function createSandboxCheckout({ db, verifyIdToken, fetchImpl = fetch, clock = Date.now }) {
    return async (request, env) => {
        try {
            const { pathname: requestPath, search } = new URL(request.url);
            const pathname = requestPath.replace(/^\/(?:functions\/v1\/)?checkout-sandbox(?=\/|$)/, '');
            const now = clock();
            if (search || !['GET', 'POST'].includes(request.method)) fail('REQUEST_INVALID', 400);

            if (pathname === '/checkout/midtrans/webhook' && request.method === 'POST') {
                const body = await readJson(request);
                await verifyNotification(body, env.MIDTRANS_SANDBOX_SIGNATURE_SECRET);
                const order = await db.byId(body.order_id);
                if (order && order.state !== 'PAID') {
                    const payment = await gateway(env, fetchImpl).status(order.id);
                    if (!payment) fail('GATEWAY_UNAVAILABLE', 503);
                    await applyStatus(db, order, payment, now);
                }
                return json({ received: true });
            }

            const bearer = /^Bearer ([^\s]+)$/.exec(request.headers.get('authorization') ?? '');
            if (!bearer) fail('AUTH_REQUIRED', 401);
            let claims;
            try {
                claims = await verifyIdToken(bearer[1]);
            } catch (error) {
                if (error instanceof ApiError) throw error;
                fail('AUTH_INVALID', 401);
            }
            if (!claims?.uid) fail('AUTH_INVALID', 401);
            if (claims.email_verified !== true) fail('EMAIL_VERIFICATION_REQUIRED', 403);
            const uid = claims.uid;
            await db.rateLimit(uid, now);

            if (pathname === '/checkout/plans' && request.method === 'GET') {
                return json({ sandbox: true, plans: [PLAN] });
            }
            if (pathname === '/checkout/entitlement' && request.method === 'GET') {
                return json(await db.entitlement(uid, now));
            }
            if (pathname === '/checkout/vouchers' && request.method === 'POST') {
                return json(await redeem(db, uid, (await readJson(request)).code, now));
            }
            if (pathname === '/checkout/orders' && request.method === 'POST') {
                const order = await startOrder(db, gateway(env, fetchImpl), uid, await readJson(request), now);
                return json(orderView(order, now));
            }
            if (pathname === '/checkout/orders/open' && request.method === 'GET') {
                const order = await db.open(uid);
                return json(order ? orderView(await refresh(db, gateway(env, fetchImpl), order, now), now) : null);
            }
            const orderId = /^\/checkout\/orders\/([^/]+)$/.exec(pathname)?.[1];
            if (orderId && ORDER_ID.test(orderId) && request.method === 'GET') {
                const order = await db.byId(orderId);
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

import { createHash } from 'node:crypto';
import { fail } from './errors.mjs';

const DAY = 86_400_000;

export function createCheckoutStore(root) {
    const db = root.firestore;
    const users = root.collection('accounts');
    const orders = root.collection('orders');
    const vouchers = root.collection('vouchers');
    const payments = root.collection('payments');
    const rateLimits = root.collection('rateLimits');
    const account = uid => users.doc(createHash('sha256').update(uid).digest('hex'));

    return {
        async byId(id) {
            return (await orders.doc(id).get()).data() ?? null;
        },

        async open(uid) {
            const id = (await account(uid).get()).get('openOrder');
            if (!id) return null;
            const order = await this.byId(id);
            return order && ['CREATING', 'PENDING'].includes(order.state) ? order : null;
        },

        async reserve(order) {
            const user = account(order.uid);
            const request = user.collection('requests').doc(order.request_id);
            return db.runTransaction(async tx => {
                const [userSnapshot, requestSnapshot] = await tx.getAll(user, request);
                const existingId = requestSnapshot.get('orderId') ?? userSnapshot.get('openOrder');
                if (existingId) {
                    const existing = (await tx.get(orders.doc(existingId))).data();
                    if (!existing) fail('RETRY_ORDER', 409);
                    if (!requestSnapshot.exists) tx.create(request, { orderId: existingId });
                    return existing;
                }
                tx.create(orders.doc(order.id), order);
                tx.create(request, { orderId: order.id });
                tx.set(user, { openOrder: order.id }, { merge: true });
                return order;
            });
        },

        async claimCharge(id, now) {
            const ref = orders.doc(id);
            return db.runTransaction(async tx => {
                const order = (await tx.get(ref)).data();
                if (!order || order.state !== 'CREATING' || order.retry_at > now) return false;
                tx.update(ref, { retry_at: now + 20_000 });
                return true;
            });
        },

        async expireCreation(id) {
            const ref = orders.doc(id);
            return db.runTransaction(async tx => {
                const order = (await tx.get(ref)).data();
                if (order.state !== 'CREATING') return order;
                const user = account(order.uid);
                const snapshot = await tx.get(user);
                if (snapshot.get('openOrder') === id) tx.update(user, { openOrder: null });
                tx.update(ref, { state: 'EXPIRED' });
                return { ...order, state: 'EXPIRED' };
            });
        },

        async applyStatus(id, status, now) {
            const ref = orders.doc(id);
            const payment = payments.doc(status.transaction_id);
            return db.runTransaction(async tx => {
                const [orderSnapshot, paymentSnapshot] = await tx.getAll(ref, payment);
                const order = orderSnapshot.data();
                if ((order.transaction_id && order.transaction_id !== status.transaction_id) ||
                    (paymentSnapshot.exists && paymentSnapshot.get('orderId') !== id)) {
                    fail('GATEWAY_ORDER_MISMATCH', 409);
                }
                if (order.state === 'PAID' ||
                    (status.state !== 'PAID' && !['CREATING', 'PENDING'].includes(order.state))) return order;

                const user = account(order.uid);
                const terminal = status.state !== 'PENDING';
                const snapshot = terminal ? await tx.get(user) : null;
                const change = {
                    state: status.state,
                    transaction_id: status.transaction_id,
                    qr_url: status.qr_url ?? order.qr_url,
                    paid_at: status.state === 'PAID' ? now : null,
                };
                if (snapshot?.get('openOrder') === id) tx.update(user, { openOrder: null });
                if (status.state === 'PAID') {
                    const expiresAt = Math.max(snapshot.get('expiresAt') ?? 0, now) + order.days * DAY;
                    tx.set(user, { expiresAt }, { merge: true });
                }
                if (!paymentSnapshot.exists) tx.create(payment, { orderId: id });
                tx.update(ref, change);
                return { ...order, ...change };
            });
        },

        async entitlement(uid, now) {
            const expiresAt = (await account(uid).get()).get('expiresAt') ?? null;
            return { active: (expiresAt ?? 0) > now, expiresAt };
        },

        async redeem(uid, hash, now) {
            const ref = vouchers.doc(hash);
            const user = account(uid);
            return db.runTransaction(async tx => {
                const [voucherSnapshot, userSnapshot] = await tx.getAll(ref, user);
                const voucher = voucherSnapshot.data();
                const currentExpiry = userSnapshot.get('expiresAt') ?? null;
                if (voucher?.redeemed_uid === uid) {
                    return { active: (currentExpiry ?? 0) > now, expiresAt: currentExpiry };
                }
                if (!voucher || voucher.redeemed_uid || !Number.isSafeInteger(voucher.valid_until) ||
                    voucher.valid_until <= now || !Number.isInteger(voucher.days) ||
                    voucher.days < 1 || voucher.days > 365) fail('VOUCHER_UNAVAILABLE', 409);
                const expiresAt = Math.max(currentExpiry ?? 0, now) + voucher.days * DAY;
                tx.update(ref, { redeemed_uid: uid, redeemed_at: now });
                tx.set(user, { expiresAt }, { merge: true });
                return { active: true, expiresAt };
            });
        },

        async rateLimit(uid, now) {
            const bucket = Math.floor(now / 60_000);
            const ref = rateLimits.doc(account(uid).id);
            await db.runTransaction(async tx => {
                const previous = (await tx.get(ref)).data();
                const requests = previous?.bucket === bucket ? previous.requests + 1 : 1;
                if (requests > 60) fail('RATE_LIMIT', 429);
                tx.set(ref, { bucket, requests });
            });
        },
    };
}

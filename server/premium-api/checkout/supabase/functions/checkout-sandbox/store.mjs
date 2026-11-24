import { fail } from './errors.mjs';

const domainErrors = new Map([
    ['VOUCHER_UNAVAILABLE', 409],
    ['GATEWAY_ORDER_MISMATCH', 409],
    ['RATE_LIMIT', 429],
]);

export function createCheckoutStore(supabaseUrl, secret, fetchImpl = fetch) {
    async function rpc(operation, input) {
        const response = await fetchImpl(supabaseUrl + '/rest/v1/rpc/adixtream_checkout_sandbox', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', apikey: secret },
            body: JSON.stringify({ operation, input }),
            redirect: 'error',
            signal: AbortSignal.timeout(6000),
        });
        const result = await response.json();
        if (!response.ok) {
            const domainStatus = result?.code === 'P0001' ? domainErrors.get(result.message) : undefined;
            if (domainStatus) fail(result.message, domainStatus);
            fail('DATABASE_UNAVAILABLE', 503);
        }
        return result;
    }

    return {
        byId: id => rpc('by_id', { id }),
        open: uid => rpc('open', { uid }),
        reserve: order => rpc('reserve', order),
        claimCharge: (id, now) => rpc('claim_charge', { id, now }),
        expireCreation: id => rpc('expire_creation', { id }),
        applyStatus: (id, status, now) => rpc('apply_status', { id, ...status, now }),
        entitlement: (uid, now) => rpc('entitlement', { uid, now }),
        redeem: (uid, hash, now) => rpc('redeem', { uid, hash, now }),
        rateLimit: (uid, now) => rpc('rate_limit', { uid, now }),
    };
}

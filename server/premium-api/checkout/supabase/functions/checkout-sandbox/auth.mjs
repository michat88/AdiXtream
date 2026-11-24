import { fail } from './errors.mjs';

const KEY_URL = 'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function decodePart(part) {
    return Uint8Array.from(atob(part.replace(/-/g, '+').replace(/_/g, '/')), char => char.charCodeAt(0));
}

export function createFirebaseVerifier(projectId, apiKey, fetchImpl = fetch, clock = Date.now) {
    let signingKeys = new Map();
    let expiresAt = 0;

    async function verificationKey(kid) {
        if (clock() >= expiresAt) {
            const res = await fetchImpl(KEY_URL, { redirect: 'error', signal: AbortSignal.timeout(5000) });
            if (!res.ok) fail('AUTH_UNAVAILABLE', 503);
            const { keys } = await res.json();
            if (!Array.isArray(keys)) fail('AUTH_UNAVAILABLE', 503);
            signingKeys = new Map(keys.filter(key => key.kty === 'RSA').map(key => [key.kid, key]));
            const seconds = Number(/max-age=(\d+)/.exec(res.headers.get('cache-control') ?? '')?.[1] ?? 300);
            expiresAt = clock() + Math.min(seconds, 900) * 1000;
        }
        const jwk = signingKeys.get(kid);
        if (!jwk) fail('AUTH_INVALID', 401);
        return crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    }

    return async token => {
        if (typeof token !== 'string' || token.length > 8192 ||
            !/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token)) fail('AUTH_INVALID', 401);
        const [header, payload, signature] = token.split('.');
        const { alg, kid } = JSON.parse(decoder.decode(decodePart(header)));
        const claims = JSON.parse(decoder.decode(decodePart(payload)));
        const now = Math.floor(clock() / 1000);
        if (alg !== 'RS256' || typeof kid !== 'string' || claims.iss !== 'https://securetoken.google.com/' + projectId ||
            claims.aud !== projectId || typeof claims.sub !== 'string' || !claims.sub || claims.sub.length > 128 ||
            !Number.isInteger(claims.exp) || claims.exp <= now || !Number.isInteger(claims.iat) || claims.iat > now ||
            !Number.isInteger(claims.auth_time) || claims.auth_time <= 0 || claims.auth_time > claims.iat ||
            claims.firebase?.tenant != null) fail('AUTH_INVALID', 401);

        const key = await verificationKey(kid);
        const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, decodePart(signature),
            encoder.encode(header + '.' + payload));
        if (!valid) fail('AUTH_INVALID', 401);
        if (claims.email_verified !== true) fail('EMAIL_VERIFICATION_REQUIRED', 403);
        if (!apiKey) fail('AUTH_UNAVAILABLE', 503);

        const lookup = new URL('https://identitytoolkit.googleapis.com/v1/accounts:lookup');
        lookup.searchParams.set('key', apiKey);
        const res = await fetchImpl(lookup.toString(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ idToken: token }),
            redirect: 'error',
            signal: AbortSignal.timeout(5000),
        });
        if (!res.ok) {
            const { error } = await res.json();
            const invalid = ['USER_DISABLED', 'USER_NOT_FOUND', 'INVALID_ID_TOKEN', 'TOKEN_EXPIRED'];
            if (invalid.includes(error?.message)) fail('AUTH_INVALID', 401);
            fail('AUTH_UNAVAILABLE', 503);
        }
        const { users } = await res.json();
        const user = Array.isArray(users) && users.length === 1 ? users[0] : null;
        if (!user || user.localId !== claims.sub || user.disabled === true ||
            claims.auth_time < Number(user.validSince ?? 0)) fail('AUTH_INVALID', 401);
        if (user.emailVerified !== true) fail('EMAIL_VERIFICATION_REQUIRED', 403);
        return { uid: claims.sub, email_verified: true };
    };
}

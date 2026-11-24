import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getFirestore } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import { onRequest } from 'firebase-functions/v2/https';
import { createSandboxCheckout } from './checkout.mjs';
import { createCheckoutStore } from './store.mjs';

const app = initializeApp();
const serverKey = defineSecret('MIDTRANS_SANDBOX_SERVER_KEY');
const signatureSecret = defineSecret('MIDTRANS_SANDBOX_SIGNATURE_SECRET');
const emulated = process.env.FUNCTIONS_EMULATOR === 'true';

const checkout = createSandboxCheckout({
    db: createCheckoutStore(getFirestore(app).doc('checkoutSandbox/v1')),
    verifyIdToken: token => getAuth(app).verifyIdToken(token, true),
    fetchImpl: emulated ? () => { throw new Error('Sandbox network is disabled in the emulator'); } : fetch,
});

export const checkoutSandbox = onRequest({
    region: 'asia-southeast1',
    secrets: [serverKey, signatureSecret],
    timeoutSeconds: 30,
    maxInstances: 2,
}, async (req, res) => {
    if (emulated && (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST)) {
        res.status(503).json({ error: 'CHECKOUT_LOCKED' });
        return;
    }
    const url = new URL(req.url, 'http://checkout.invalid');
    const body = ['GET', 'HEAD'].includes(req.method) ? undefined : req.rawBody;
    const response = await checkout(new Request(url, { method: req.method, headers: req.headers, body }), {
        FIREBASE_PROJECT_ID: process.env.GCLOUD_PROJECT,
        CHECKOUT_SANDBOX_ENABLED: process.env.CHECKOUT_SANDBOX_ENABLED,
        MIDTRANS_SANDBOX_SERVER_KEY: serverKey.value(),
        MIDTRANS_SANDBOX_SIGNATURE_SECRET: signatureSecret.value(),
        emulated,
    });
    for (const [name, value] of response.headers) res.set(name, value);
    res.status(response.status).send(await response.text());
});

import { createFirebaseVerifier } from "./auth.mjs";
import { createSandboxCheckout } from "./checkout.mjs";
import { createCheckoutStore } from "./store.mjs";

const expectedUrl = "https://irompgoosrqjbmicyusa.supabase.co";
const env = {
  MIDTRANS_SANDBOX_SERVER_KEY: Deno.env.get("MIDTRANS_SANDBOX_SERVER_KEY"),
  MIDTRANS_SANDBOX_SIGNATURE_SECRET: Deno.env.get("MIDTRANS_SANDBOX_SIGNATURE_SECRET"),
};

function initialization() {
  if (
    Deno.env.get("SUPABASE_URL") !== expectedUrl ||
    !env.MIDTRANS_SANDBOX_SERVER_KEY?.startsWith("SB-Mid-server-") ||
    !env.MIDTRANS_SANDBOX_SIGNATURE_SECRET
  ) return null;

  const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
  if (typeof keys.default !== "string" || !keys.default.startsWith("sb_secret_")) return null;

  return createSandboxCheckout({
    db: createCheckoutStore(expectedUrl, keys.default),
    verifyIdToken: createFirebaseVerifier(
      "adixtream-checkout-sandbox",
      Deno.env.get("FIREBASE_SANDBOX_WEB_API_KEY"),
    ),
  });
}

const checkout = initialization();
Deno.serve((request) =>
  checkout
    ? checkout(request, env)
    : Response.json({ error: "CHECKOUT_LOCKED" }, { status: 503, headers: { "Cache-Control": "no-store" } })
);

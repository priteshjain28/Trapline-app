// deno test supabase/functions/_shared/

import { base64url, toBase64, toHex, verifyRazorpay, verifySharedSecret, verifyStandardWebhook } from "./crypto.ts";
import { serviceAccountJwt } from "./fcm.ts";
import { inviteSms, isNewer, normalisePhone, pushText, subscriptionChange } from "./logic.ts";

function assert(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new Error(msg);
}
function assertEquals<T>(a: T, b: T, msg = "") {
  if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${msg}: ${JSON.stringify(a)} !== ${JSON.stringify(b)}`);
}

const enc = new TextEncoder();
async function hmac(key: Uint8Array, data: string) {
  const k = await crypto.subtle.importKey("raw", key as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return crypto.subtle.sign("HMAC", k, enc.encode(data));
}

Deno.test("phone numbers normalise to +91 and reject non-mobiles", () => {
  assertEquals(normalisePhone("98765 43210"), "+919876543210");
  assertEquals(normalisePhone("+91 98765-43210"), "+919876543210");
  assertEquals(normalisePhone("919876543210"), "+919876543210");
  assertEquals(normalisePhone("09876543210"), "+919876543210");
  assertEquals(normalisePhone("5876543210"), null, "landline-style first digit");
  assertEquals(normalisePhone("12345"), null);
  assertEquals(normalisePhone(undefined), null);
});

Deno.test("Razorpay signature: accepts the right one, rejects tampering", async () => {
  const body = '{"event":"subscription.charged","created_at":1760000000}';
  const sig = toHex(await hmac(enc.encode("whsec_test"), body));
  assert(await verifyRazorpay(body, sig, "whsec_test"), "valid signature");
  assert(!(await verifyRazorpay(body + " ", sig, "whsec_test")), "tampered body");
  assert(!(await verifyRazorpay(body, sig, "other")), "wrong secret");
  assert(!(await verifyRazorpay(body, null, "whsec_test")), "missing header");
});

Deno.test("Standard Webhooks (Supabase hooks): signature, multiple keys, replay window", async () => {
  const keyBytes = crypto.getRandomValues(new Uint8Array(24));
  const secret = "v1,whsec_" + toBase64(keyBytes.buffer);
  const body = '{"user":{"phone":"919876543210"},"sms":{"otp":"123456"}}';
  const now = 1_760_000_000;
  const sig = toBase64(await hmac(keyBytes, `msg_1.${now}.${body}`));
  const h = { id: "msg_1", timestamp: String(now), signature: `v1,${sig}` };
  assert(await verifyStandardWebhook(body, h, secret, now), "valid");
  assert(await verifyStandardWebhook(body, { ...h, signature: `v1,AAAA v1,${sig}` }, secret, now), "any of several signatures");
  assert(!(await verifyStandardWebhook(body, h, secret, now + 301)), "older than 5 minutes");
  assert(!(await verifyStandardWebhook(body.replace("123456", "000000"), h, secret, now)), "tampered");
  assert(!(await verifyStandardWebhook(body, { ...h, id: null }, secret, now)), "missing id");
});

Deno.test("shared secret compare", () => {
  assert(verifySharedSecret("abc", "abc"), "same");
  assert(!verifySharedSecret("abd", "abc"), "different");
  assert(!verifySharedSecret(null, "abc"), "missing");
});

Deno.test("subscription events map to status and paid access", () => {
  assertEquals(subscriptionChange("subscription.charged"), { status: "active", paid: true });
  assertEquals(subscriptionChange("subscription.pending"), { status: "past_due", paid: true });
  assertEquals(subscriptionChange("subscription.halted"), { status: "halted", paid: false });
  assertEquals(subscriptionChange("subscription.cancelled"), { status: "cancelled", paid: false });
  assertEquals(subscriptionChange("payment.captured"), null, "unrelated events change nothing");
});

Deno.test("out-of-order events never move a subscription backwards", () => {
  assert(isNewer(100, null), "first event");
  assert(isNewer(100, 100), "same second");
  assert(!isNewer(99, 100), "older event ignored");
});

Deno.test("push text: known kinds only, plain words", () => {
  const nudge = pushText({ kind: "daily_nudge", payload: { project: "Green Park Tower B" } });
  assertEquals(nudge?.title, "Green Park Tower B");
  assert(!nudge!.body.includes("!"), "no exclamation marks");
  assertEquals(pushText({ kind: "something_else", payload: {} }), null);
});

Deno.test("invite SMS fits one segment", () => {
  const s = inviteSms("Anita Rao", "Sharma Constructions", "https://example.app/i");
  assert(s.length <= 160, "<= 160 chars");
  assert(s.includes("Sharma Constructions"), "names the org");
  assert(inviteSms("", "X", "l").startsWith("Your team"), "no inviter name");
});

Deno.test("FCM service-account JWT is a valid RS256 token", async () => {
  const kp = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"],
  );
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", kp.privateKey);
  const pem = `-----BEGIN PRIVATE KEY-----\n${toBase64(pkcs8).match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`;
  const jwt = await serviceAccountJwt({ project_id: "p", client_email: "svc@p.iam.gserviceaccount.com", private_key: pem }, 1_760_000_000);
  const [h, c, s] = jwt.split(".");
  const claims = JSON.parse(atob(c.replace(/-/g, "+").replace(/_/g, "/")));
  assertEquals(claims.iss, "svc@p.iam.gserviceaccount.com");
  assertEquals(claims.exp - claims.iat, 3600);
  const sig = Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "==".slice(0, (4 - s.length % 4) % 4)), (x) => x.charCodeAt(0));
  assert(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", kp.publicKey, sig as BufferSource, enc.encode(`${h}.${c}`)), "signature verifies");
  assertEquals(base64url("a"), "YQ");
});

// Signature checks for incoming webhooks. Web Crypto only (works in Deno and Supabase Edge).

const enc = new TextEncoder();

export function toHex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function toBase64(buf: ArrayBuffer): string {
  let s = "";
  for (const b of new Uint8Array(buf)) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromBase64(s: string): Uint8Array {
  const bin = atob(s);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export function base64url(input: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof input === "string" ? enc.encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function hmac(key: Uint8Array, data: string): Promise<ArrayBuffer> {
  const k = await crypto.subtle.importKey("raw", key as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return crypto.subtle.sign("HMAC", k, enc.encode(data));
}

/** Constant-time string comparison. */
export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Razorpay: X-Razorpay-Signature = hex(HMAC-SHA256(rawBody, webhookSecret)). */
export async function verifyRazorpay(rawBody: string, signature: string | null, secret: string): Promise<boolean> {
  if (!signature) return false;
  const expected = toHex(await hmac(enc.encode(secret), rawBody));
  return safeEqual(expected, signature);
}

/**
 * Standard Webhooks (used by Supabase Auth hooks): signature header holds space-separated "v1,<base64>" entries,
 * each HMAC-SHA256 of "<id>.<timestamp>.<body>" with the base64 secret after "v1,whsec_".
 * Rejects timestamps more than [toleranceSeconds] from now (replays).
 */
export async function verifyStandardWebhook(
  rawBody: string,
  headers: { id: string | null; timestamp: string | null; signature: string | null },
  secret: string,
  nowSeconds = Math.floor(Date.now() / 1000),
  toleranceSeconds = 300,
): Promise<boolean> {
  const { id, timestamp, signature } = headers;
  if (!id || !timestamp || !signature) return false;
  const ts = Number(timestamp);
  if (!Number.isFinite(ts) || Math.abs(nowSeconds - ts) > toleranceSeconds) return false;
  const keyB64 = secret.replace(/^v1,/, "").replace(/^whsec_/, "");
  const expected = toBase64(await hmac(fromBase64(keyB64), `${id}.${timestamp}.${rawBody}`));
  return signature.split(" ").some((part) => {
    const [version, sig] = part.split(",");
    return version === "v1" && sig !== undefined && safeEqual(sig, expected);
  });
}

/** For the scheduler and database webhooks: a shared secret in a header. */
export function verifySharedSecret(header: string | null, secret: string): boolean {
  return !!header && safeEqual(header, secret);
}

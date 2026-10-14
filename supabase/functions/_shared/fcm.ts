// Firebase Cloud Messaging HTTP v1 with a service account. Signs its own OAuth JWT (RS256) with Web Crypto.

import { base64url } from "./crypto.ts";

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string; // PEM, PKCS#8
}

function pemToDer(pem: string): Uint8Array {
  const b64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

/** The signed assertion Google's token endpoint swaps for an access token. Exported for tests. */
export async function serviceAccountJwt(sa: ServiceAccount, nowSeconds = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  };
  const unsigned = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key) as BufferSource, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(sig)}`;
}

let cached: { token: string; until: number } | null = null;

export async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.until > now + 60) return cached.token;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: await serviceAccountJwt(sa, now),
    }),
  });
  if (!res.ok) throw new Error(`google token ${res.status}`);
  const json = await res.json();
  cached = { token: json.access_token, until: now + Number(json.expires_in ?? 3600) };
  return cached.token;
}

export type SendResult = "sent" | "unregistered" | "failed";

export async function sendPush(sa: ServiceAccount, token: string, title: string, body: string, data: Record<string, string>): Promise<SendResult> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${await accessToken(sa)}`, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, notification: { title, body }, data, android: { priority: "high" } } }),
  });
  if (res.ok) return "sent";
  const text = await res.text();
  // the app was uninstalled or the token rotated: forget it
  if (res.status === 404 || text.includes("UNREGISTERED")) return "unregistered";
  return "failed";
}

// Environment, HTTP helpers, and clients for Supabase, MSG91 and Razorpay. Keys come from function secrets only.

import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";

export function env(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`missing secret ${name}`);
  return v;
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** Errors go back as a stable code the app maps to its own words; never internal detail. */
export function fail(code: string, status: number): Response {
  return json({ error: code }, status);
}

/** Service-role client: bypasses RLS. Use only after the caller has been checked. */
export function adminClient(): SupabaseClient {
  return createClient(env("SUPABASE_URL"), env("SUPABASE_SERVICE_ROLE_KEY"), { auth: { persistSession: false } });
}

/** Acts as the signed-in caller, so row level security applies. Null when there's no valid bearer token. */
export async function userClient(req: Request): Promise<{ client: SupabaseClient; userId: string } | null> {
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return null;
  const client = createClient(env("SUPABASE_URL"), env("SUPABASE_ANON_KEY"), {
    auth: { persistSession: false },
    global: { headers: { authorization: auth } },
  });
  const { data, error } = await client.auth.getUser(auth.slice(7));
  if (error || !data.user) return null;
  return { client, userId: data.user.id };
}

// ---------------------------------------------------------------- MSG91 (Indian SMS, DLT-registered templates)

/** Sends a DLT-approved MSG91 Flow template. Variables must match the template's ##var## names. */
export async function sendSmsTemplate(phoneE164: string, templateId: string, vars: Record<string, string>): Promise<boolean> {
  const res = await fetch("https://control.msg91.com/api/v5/flow", {
    method: "POST",
    headers: { authkey: env("MSG91_AUTH_KEY"), "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ template_id: templateId, short_url: "0", recipients: [{ mobiles: phoneE164.replace("+", ""), ...vars }] }),
  });
  return res.ok;
}

// ---------------------------------------------------------------- Razorpay

export async function razorpay(path: string, init: { method: string; body?: unknown }): Promise<Response> {
  const basic = btoa(`${env("RAZORPAY_KEY_ID")}:${env("RAZORPAY_KEY_SECRET")}`);
  return fetch(`https://api.razorpay.com/v1${path}`, {
    method: init.method,
    headers: { authorization: `Basic ${basic}`, "content-type": "application/json" },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
}

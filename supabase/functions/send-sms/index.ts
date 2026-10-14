// Supabase Auth "Send SMS" hook: Supabase makes the OTP, this delivers it through MSG91 (DLT-registered template).
// Configure in Dashboard > Authentication > Hooks, with SEND_SMS_HOOK_SECRET as the hook secret.

import { verifyStandardWebhook } from "../_shared/crypto.ts";
import { normalisePhone } from "../_shared/logic.ts";
import { adminClient, env, json, sendSmsTemplate } from "../_shared/services.ts";

// Auth hooks expect {} on success, or {error: {http_code, message}}; the message reaches the app.
const hookError = (http_code: number, message: string) => json({ error: { http_code, message } }, http_code);

Deno.serve(async (req) => {
  const raw = await req.text();
  const signed = await verifyStandardWebhook(raw, {
    id: req.headers.get("webhook-id"),
    timestamp: req.headers.get("webhook-timestamp"),
    signature: req.headers.get("webhook-signature"),
  }, env("SEND_SMS_HOOK_SECRET"));
  if (!signed) return hookError(401, "Unauthorised");

  let payload: { user?: { phone?: string }; sms?: { otp?: string } };
  try { payload = JSON.parse(raw); } catch { return hookError(400, "Bad request"); }
  const phone = normalisePhone(payload.user?.phone);
  const otp = payload.sms?.otp;
  if (!phone || !otp) return hookError(400, "Enter a 10-digit Indian mobile number");

  // on top of Supabase's own limits: 5 codes per number per hour
  const { data: allowed, error } = await adminClient().rpc("rate_limit_hit", { p_key: `sms:${phone}`, p_max: 5, p_window_seconds: 3600 });
  if (error) return hookError(500, "Couldn't send the code. Try again.");
  if (!allowed) return hookError(429, "Too many codes for this number. Try again in an hour.");

  const sent = await sendSmsTemplate(phone, env("MSG91_OTP_TEMPLATE_ID"), { otp });
  return sent ? json({}) : hookError(502, "Couldn't send the code. Try again.");
});

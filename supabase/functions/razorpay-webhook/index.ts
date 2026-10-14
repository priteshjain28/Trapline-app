// Razorpay subscription webhooks. Verifies the signature, ignores replays, never moves a subscription backwards,
// and keeps organisations.plan in step. Returns 5xx on failure so Razorpay retries.

import { verifyRazorpay } from "../_shared/crypto.ts";
import { isNewer, subscriptionChange } from "../_shared/logic.ts";
import { adminClient, env, fail, json } from "../_shared/services.ts";

Deno.serve(async (req) => {
  const raw = await req.text();
  if (!(await verifyRazorpay(raw, req.headers.get("x-razorpay-signature"), env("RAZORPAY_WEBHOOK_SECRET")))) {
    return fail("bad_signature", 401);
  }
  const eventId = req.headers.get("x-razorpay-event-id");
  if (!eventId) return fail("bad_request", 400);
  const event = JSON.parse(raw) as {
    event: string;
    created_at: number;
    payload?: { subscription?: { entity?: { id: string; current_end?: number | null; notes?: Record<string, string> } } };
  };

  const admin = adminClient();
  const { data: seen } = await admin.from("webhook_events").select("processed_at").eq("id", eventId).maybeSingle();
  if (seen?.processed_at) return json({ duplicate: true });

  const change = subscriptionChange(event.event);
  const entity = event.payload?.subscription?.entity;
  if (change && entity) {
    const { data: sub } = await admin.from("subscriptions").select("*").eq("provider_subscription_id", entity.id).maybeSingle();
    if (sub) {
      const eventAt = new Date(event.created_at * 1000);
      const lastSeen = sub.last_event_at ? Date.parse(sub.last_event_at) / 1000 : null;
      if (isNewer(event.created_at, lastSeen)) {
        const up = await admin.from("subscriptions").update({
          status: change.status,
          current_period_end: entity.current_end ? new Date(entity.current_end * 1000).toISOString() : sub.current_period_end,
          last_event_at: eventAt.toISOString(),
        }).eq("id", sub.id);
        const org = await admin.from("organisations").update({ plan: change.paid ? sub.plan : "free" }).eq("id", sub.org_id);
        if (up.error || org.error) return fail("server_error", 500);
      }
    }
    // no row: a subscription created outside the app. Stored below for a person to look at.
  }

  const { error } = await admin.from("webhook_events").upsert(
    { id: eventId, provider: "razorpay", payload: event, processed_at: new Date().toISOString() }, { onConflict: "id" },
  );
  if (error) return fail("server_error", 500);
  return json({ ok: true });
});

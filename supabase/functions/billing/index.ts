// POST {action: "subscribe" | "cancel" | "status", org_id, plan?} as an org owner or admin.
// Razorpay hosts the payment page (UPI AutoPay, cards, netbanking): no card forms here.
// Cancelling is one call and takes effect at the end of the paid period. The webhook updates the status.

import { PLANS, type Plan } from "../_shared/logic.ts";
import { adminClient, env, fail, json, razorpay, userClient } from "../_shared/services.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail("method_not_allowed", 405);
  const caller = await userClient(req);
  if (!caller) return fail("not_signed_in", 401);
  let body: { action?: string; org_id?: string; plan?: string };
  try { body = await req.json(); } catch { return fail("bad_request", 400); }
  const orgId = String(body.org_id ?? "");
  if (!/^[0-9a-f-]{36}$/.test(orgId)) return fail("bad_request", 400);

  // only owners and admins touch billing (checked as the caller, under RLS)
  const { data: membership } = await caller.client.from("org_members").select("role")
    .eq("org_id", orgId).eq("user_id", caller.userId).maybeSingle();
  if (!membership || !["owner", "admin"].includes(membership.role)) return fail("not_allowed", 403);

  const admin = adminClient();
  const { data: current } = await admin.from("subscriptions").select("*").eq("org_id", orgId).maybeSingle();

  switch (body.action) {
    case "status":
      return json({ subscription: current ?? null });

    case "subscribe": {
      const plan = body.plan as Plan;
      if (!PLANS.includes(plan)) return fail("bad_plan", 400);
      if (current && ["active", "past_due"].includes(current.status)) return fail("already_subscribed", 409);
      const res = await razorpay("/subscriptions", {
        method: "POST",
        body: { plan_id: env(`RAZORPAY_PLAN_${plan.toUpperCase()}`), total_count: 120, customer_notify: 1, notes: { org_id: orgId, plan } },
      });
      if (!res.ok) return fail("payment_provider_error", 502);
      const sub = await res.json();
      const { error } = await admin.from("subscriptions").upsert(
        { org_id: orgId, plan, provider: "razorpay", provider_subscription_id: sub.id, status: "created" },
        { onConflict: "org_id" },
      );
      if (error) return fail("server_error", 500);
      return json({ checkout_url: sub.short_url });
    }

    case "cancel": {
      if (!current || !["active", "past_due", "created"].includes(current.status)) return fail("no_subscription", 404);
      const res = await razorpay(`/subscriptions/${current.provider_subscription_id}/cancel`, {
        method: "POST", body: { cancel_at_cycle_end: current.status === "active" ? 1 : 0 },
      });
      if (!res.ok) return fail("payment_provider_error", 502);
      return json({ cancelled: true, paid_until: current.current_period_end });
    }

    default:
      return fail("bad_action", 400);
  }
});

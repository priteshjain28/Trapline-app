// Database webhook on notifications INSERT (Dashboard > Database > Webhooks), with header x-webhook-secret.
// Sends the push to every device of that user and forgets tokens FCM says are gone.

import { verifySharedSecret } from "../_shared/crypto.ts";
import { sendPush, type ServiceAccount } from "../_shared/fcm.ts";
import { type NotificationRow, pushText } from "../_shared/logic.ts";
import { adminClient, env, fail, json } from "../_shared/services.ts";

Deno.serve(async (req) => {
  if (!verifySharedSecret(req.headers.get("x-webhook-secret"), env("PUSH_WEBHOOK_SECRET"))) return fail("unauthorised", 401);
  const body = await req.json() as { type?: string; record?: NotificationRow & { id: string; user_id: string; project_id: string | null } };
  const n = body.record;
  if (body.type !== "INSERT" || !n) return json({ skipped: "not an insert" });
  const text = pushText(n);
  if (!text) return json({ skipped: "no push for this kind" });

  const admin = adminClient();
  const { data: devices } = await admin.from("devices").select("id, fcm_token").eq("user_id", n.user_id);
  const sa = JSON.parse(env("FCM_SERVICE_ACCOUNT")) as ServiceAccount;
  const data = { notification_id: n.id, kind: n.kind, project_id: n.project_id ?? "" };
  let sent = 0;
  for (const d of devices ?? []) {
    const r = await sendPush(sa, d.fcm_token, text.title, text.body, data);
    if (r === "sent") sent++;
    if (r === "unregistered") await admin.from("devices").delete().eq("id", d.id);
  }
  return json({ sent });
});

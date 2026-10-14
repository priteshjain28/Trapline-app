// Called nightly by pg_cron (header x-cron-secret): removes expired report PDFs from Storage, then their rows.

import { verifySharedSecret } from "../_shared/crypto.ts";
import { adminClient, env, fail, json } from "../_shared/services.ts";

Deno.serve(async (req) => {
  if (!verifySharedSecret(req.headers.get("x-cron-secret"), env("CRON_SECRET"))) return fail("unauthorised", 401);
  const admin = adminClient();
  const { data: expired, error } = await admin.from("reports").select("id, storage_path")
    .lt("expires_at", new Date().toISOString()).limit(500);
  if (error) return fail("server_error", 500);
  const paths = (expired ?? []).map((r) => r.storage_path).filter((p): p is string => !!p);
  if (paths.length) {
    const { error: e } = await admin.storage.from("reports").remove(paths);
    if (e) return fail("storage_error", 500);
  }
  const ids = (expired ?? []).map((r) => r.id);
  if (ids.length) await admin.from("reports").delete().in("id", ids);
  return json({ removed: ids.length });
});

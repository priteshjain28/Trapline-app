// POST as the signed-in user: deletes their account for real (Google Play and Apple both require this).
// Business records they logged stay with the organisation, author cleared.

import { adminClient, fail, json, userClient } from "../_shared/services.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail("method_not_allowed", 405);
  const caller = await userClient(req);
  if (!caller) return fail("not_signed_in", 401);

  const { error } = await caller.client.rpc("delete_my_account");
  if (error) {
    // the sole owner of an organisation that still has people in it must hand it over first
    if (error.message?.includes("owner_must_transfer")) return fail("owner_must_transfer", 409);
    return fail("server_error", 500);
  }
  const { error: authError } = await adminClient().auth.admin.deleteUser(caller.userId);
  if (authError) return fail("server_error", 500);
  return json({ deleted: true });
});

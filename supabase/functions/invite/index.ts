// POST {phone, org_id, project_id?, org_role?, project_role?} as a signed-in admin or project manager.
// Row level security decides whether the caller may invite; this adds the SMS and a rate limit.

import { inviteSms, normalisePhone } from "../_shared/logic.ts";
import { adminClient, env, fail, json, sendSmsTemplate, userClient } from "../_shared/services.ts";

const ORG_ROLES = ["admin", "member"];
const PROJECT_ROLES = ["manager", "engineer", "supervisor", "storekeeper", "accountant", "viewer"];

Deno.serve(async (req) => {
  if (req.method !== "POST") return fail("method_not_allowed", 405);
  const caller = await userClient(req);
  if (!caller) return fail("not_signed_in", 401);

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return fail("bad_request", 400); }
  const phone = normalisePhone(String(body.phone ?? ""));
  const orgRole = String(body.org_role ?? "member");
  const projectRole = body.project_role == null ? null : String(body.project_role);
  const orgId = String(body.org_id ?? "");
  const projectId = body.project_id == null ? null : String(body.project_id);
  if (!phone) return fail("bad_phone", 400);
  if (!ORG_ROLES.includes(orgRole) || (projectRole !== null && !PROJECT_ROLES.includes(projectRole))) return fail("bad_role", 400);
  if (!/^[0-9a-f-]{36}$/.test(orgId) || (projectId !== null && !/^[0-9a-f-]{36}$/.test(projectId))) return fail("bad_request", 400);

  const admin = adminClient();
  const { data: allowed } = await admin.rpc("rate_limit_hit", { p_key: `invite:${caller.userId}`, p_max: 30, p_window_seconds: 86400 });
  if (!allowed) return fail("rate_limited", 429);

  // as the caller: RLS refuses unless they are an org admin or that project's manager
  const { data: invite, error } = await caller.client.from("invites")
    .insert({ org_id: orgId, project_id: projectId, phone, org_role: orgRole, project_role: projectRole, invited_by: caller.userId })
    .select("id").single();
  if (error || !invite) return fail("not_allowed", 403);

  const [{ data: org }, { data: me }, { data: project }] = await Promise.all([
    caller.client.from("organisations").select("name").eq("id", orgId).single(),
    caller.client.from("profiles").select("name").eq("id", caller.userId).single(),
    projectId ? caller.client.from("projects").select("name").eq("id", projectId).single() : Promise.resolve({ data: null }),
  ]);

  // already using the app: a push too (the push function sends it)
  const { data: existing } = await admin.from("profiles").select("id").eq("phone", phone).maybeSingle();
  if (existing) {
    await admin.from("notifications").insert({
      user_id: existing.id, project_id: projectId, kind: "invite", payload: { project: project?.name ?? org?.name ?? "" },
    });
  }

  const text = inviteSms(me?.name ?? "", org?.name ?? "your team", env("APP_LINK"));
  const sms = await sendSmsTemplate(phone, env("MSG91_INVITE_TEMPLATE_ID"), {
    inviter: (me?.name ?? "").slice(0, 30), org: (org?.name ?? "").slice(0, 30), link: env("APP_LINK"),
  });
  return json({ invite_id: invite.id, sms_sent: sms, preview: text });
});

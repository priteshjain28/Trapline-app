// Pure rules the functions share. No I/O here, so it is all unit-tested (logic_test.ts).

/** "+91 98765-43210", "919876543210", "09876543210" -> "+919876543210". Null if not an Indian mobile. */
export function normalisePhone(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let digits = raw.replace(/\D/g, "");
  if (digits.length === 12 && digits.startsWith("91")) digits = digits.slice(2);
  if (digits.length === 11 && digits.startsWith("0")) digits = digits.slice(1);
  if (digits.length !== 10 || !/^[6-9]/.test(digits)) return null;
  return "+91" + digits;
}

export type Plan = "pro" | "pro_plus" | "advanced";
export const PLANS: readonly Plan[] = ["pro", "pro_plus", "advanced"];

export type SubscriptionStatus = "created" | "active" | "past_due" | "halted" | "cancelled" | "completed";

/**
 * Razorpay subscription webhook -> our status, and whether the org keeps paid features.
 * Only these events change anything; others are acknowledged and ignored.
 * https://razorpay.com/docs/webhooks/subscriptions/
 */
export function subscriptionChange(event: string): { status: SubscriptionStatus; paid: boolean } | null {
  switch (event) {
    case "subscription.authenticated":
      return { status: "created", paid: false };
    case "subscription.activated":
    case "subscription.charged":
    case "subscription.resumed":
      return { status: "active", paid: true };
    case "subscription.pending":
      // a charge failed; Razorpay retries. Keep features while it does.
      return { status: "past_due", paid: true };
    case "subscription.halted":
      return { status: "halted", paid: false };
    case "subscription.cancelled":
      return { status: "cancelled", paid: false };
    case "subscription.completed":
      return { status: "completed", paid: false };
    default:
      return null;
  }
}

/** Razorpay sends events at least once and not always in order: never move backwards in time. */
export function isNewer(eventCreatedAt: number, lastSeenAt: number | null): boolean {
  return lastSeenAt === null || eventCreatedAt >= lastSeenAt;
}

export interface NotificationRow {
  kind: string;
  payload: Record<string, unknown>;
}

/** Push text for a notification row. Plain words, no exclamation marks. Null = don't push. */
export function pushText(n: NotificationRow): { title: string; body: string } | null {
  const p = n.payload ?? {};
  switch (n.kind) {
    case "daily_nudge":
      return { title: String(p.project ?? "Your site"), body: "Nothing logged today yet. Add today's work and attendance before you leave." };
    case "task_overdue":
      return { title: "Task overdue", body: `${String(p.name ?? "A task")} was due ${String(p.due ?? "yesterday")}.` };
    case "invite":
      return { title: "You've been added to a site", body: `Open the app to see ${String(p.project ?? "it")}.` };
    default:
      return null;
  }
}

/** Invite SMS. Kept short: one SMS segment. */
export function inviteSms(inviter: string, orgName: string, appLink: string): string {
  const who = inviter.trim() || "Your team";
  return `${who} added you to ${orgName} on Site. Install and sign in with this number: ${appLink}`.slice(0, 160);
}

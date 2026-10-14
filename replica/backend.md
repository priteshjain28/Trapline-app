# Backend: site app

Supabase (Postgres, Auth, Storage, Edge Functions) in the Mumbai region, MSG91 for SMS, Razorpay for subscriptions,
Firebase Cloud Messaging for push. The Android app is offline-first: it reads and writes its own SQLite database and
syncs with Supabase in the background.

Nothing here uses the original app's servers, endpoints or OAuth clients.
**You create every account and key.** Claude never signed up for anything or handled a live key.

## What's built and how it's checked

| part | where | checked by |
| --- | --- | --- |
| Schema, row level security, triggers | `supabase/migrations/` (5 files) | `supabase/run-tests.sh`: smoke test + server-rule tests on Postgres 16, locally and in CI (`backend.yml`) |
| Server rules: progress roll-up, plan limits, account deletion, storage access, nudges | `20261014000100_server_rules.sql` | `tests/server_rules.sql` (two phones on one task, 4th project refused, uploads only into your own sites, sole owner can't vanish, ...) |
| Scheduled jobs | `20261014000200_jobs.sql` (pg_cron, pg_net) | runs only on Supabase; skipped locally |
| Demo data | `supabase/seed.sql` (invented people) | loaded in every test run |
| Edge Functions: `send-sms`, `invite`, `billing`, `razorpay-webhook`, `push`, `delete-account`, `cleanup` | `supabase/functions/` | 9 Deno unit tests on the shared logic (signatures, replay window, event mapping, FCM JWT); every function type-checks against supabase-js |
| Offline-first data layer | `site-app/core` (`SyncedBackend`, `SyncEngine`, `SupabaseHttp`, SQLDelight schema) | 29 JVM tests, including 8 sync scenarios with two phones and an in-memory server, and 6 HTTP client tests (token refresh, error sorting) |
| App wiring | `site-app/app` (`AppContainer`, `AndroidData.kt`) | compiled by CI (`site-app.yml`). Demo mode when no keys are set |

**Not tested against a real Supabase project.** That needs your project and keys. This container can't run the Supabase
stack (no Docker), so integration testing against the hosted project is the first job after setup (see "Your setup").

## Auth

- **Phone + 6-digit SMS code**, the way the original signs in. Supabase Auth makes the code. The `send-sms` hook delivers it
  through MSG91 with a DLT template, checks the Standard Webhooks signature, and allows 5 codes per number per hour on top of
  Supabase's own limits.
- **Sessions:** Supabase JWT plus refresh token, kept in app-private storage (`allowBackup=false`). The client refreshes a
  minute before expiry and once after any 401. A failed refresh signs you out and wipes the phone's copy of the data.
- **Roles:** org `owner`/`admin`/`member`, plus per-project `manager`/`engineer`/`supervisor`/`storekeeper`/`accountant`/`viewer`.
  One set of SQL helpers (`app.can_read`, `can_write`, `can_see_money`, `can_manage`) answers "can this user do this to this
  row", and every policy uses them.
- **First sign-in** asks for your name and company, which creates the organisation and makes you its owner.
  Invited people join their sites automatically (`accept_invites`).
- **Account deletion** (Profile > Delete my account) deletes for real: profile, memberships and login. Work they logged stays
  with the company, author cleared. The only owner of a company with other people in it must hand over first.
  Google Play requires in-app deletion.
- **Not here:** email/password and Google sign-in. The original doesn't use them, and phone is what site teams have.

## Database

- **Migrations** are checked in and applied with `supabase db push`. The same files run in the local tests.
- **Access rules:** RLS on every table, 87+ policies. Tests prove an outsider gets nothing and an engineer can't see money.
- **Seed:** `supabase/seed.sql` with invented people. `supabase db reset` loads it locally. Never run it on production.
- **Backups:** Supabase takes daily backups on paid plans. The free plan has none you can restore from, so use Pro before
  real customers (point-in-time recovery is an add-on). **Check:** Dashboard > Database > Backups shows recent backups.

## Payments (Razorpay, test mode)

- **Checkout:** Razorpay's hosted page handles UPI AutoPay, cards and netbanking. There are no card forms in the app.
  `billing` with `subscribe` returns the checkout link.
- **Cancel:** one call (`billing` with `cancel`). It ends at the close of the paid period, or immediately if never paid.
- **Webhook:** `razorpay-webhook` verifies `X-Razorpay-Signature` and records each event id so replays do nothing.
  It never applies an older event over a newer one, and maps these events onto your plan:
  - `subscription.activated`, `charged` and `resumed` make the plan active.
  - `subscription.pending` marks it past due but keeps access while Razorpay retries.
  - `subscription.halted`, `cancelled` and `completed` take the company back to the free plan.
- **Subscription status:** it lives in `subscriptions` and `organisations.plan`, written only by the webhook and the server.
  The app reads it and never trusts itself.
- **Free plan:** 3 active sites, enforced in the database and pre-checked on the phone. Change it in `app.plan_limit()`.
- **Not built yet:** the in-app plan and billing screen. The function is ready; the screen comes with S26/S35.

## Email, SMS and push

- **SMS:** MSG91 Flow templates, used for the OTP and for invites. India requires DLT registration before templates
  deliver; see below.
- **Email:** none sent yet. Razorpay emails payment receipts itself (`customer_notify`). If reports by email are wanted
  later, add Resend from your own domain.
- **Push:** a database webhook on `notifications` calls `push`, which uses FCM HTTP v1 with your service account and drops
  dead tokens. Notifications come from the daily 18:30 nudge for sites with nothing logged, overdue tasks at 06:00, and invites.
  **The app doesn't register for push yet.** That needs your Firebase project's `google-services.json`, so it waits for your
  Firebase setup; `devices` and the server side are ready.

## Jobs

| job | when (IST) | how |
| --- | --- | --- |
| daily nudge | 18:30 | pg_cron → `app.daily_nudge()` → notifications → push |
| overdue tasks | 06:00 | pg_cron → `app.overdue_task_alerts()` |
| expired reports | 02:00 | pg_cron + pg_net → `cleanup` function (Storage, then rows) |
| housekeeping | hourly | pg_cron: old rate-limit windows, read notifications > 60 days |
| sync | app start + every 15 min with network | WorkManager on the phone |

**Retries:** the outbox retries every change until the server accepts or refuses it. Refused changes are undone on the
phone and counted in the sync banner. Razorpay retries webhooks that fail. pg_cron runs are logged in `cron.job_run_details`.

## Integrations

| integration | official API | scopes / access | review before launch | limits |
| --- | --- | --- | --- | --- |
| MSG91 SMS | Flow API v5 | auth key, server only | **DLT**: register your business (entity) and each template with an operator (Jio, Airtel, Vi, BSNL portal). Allow 3–7 working days, so **start first** | per account plan |
| Razorpay | Subscriptions API v1 + webhooks | key id/secret, server only | KYC and website review to go live; test mode works at once | API rate limits per account |
| Firebase Cloud Messaging | HTTP v1 | service account with `firebase.messaging` only | none | generous; dead tokens removed |
| Supabase | REST, Auth, Storage, Functions | anon key in the app, service role key only in functions | none | plan quotas (storage, egress, function calls) |

No Google OAuth scopes are used, so there's no Google verification wait.

## Security checklist

- [x] **Secrets** only in env vars or function secrets. `.env*` is in `.gitignore`. Only the public anon key goes into the
  app build, and the service role key exists only inside functions.
- [x] **Input validated on the server:** database checks on every table (dates, amounts, enums, lengths, phone format) plus
  validation in each function. The app validates first for nicer errors. No zod, since there's no Node server.
- [x] **Authorisation on every read and write** through RLS. Tested with a second and third user (outsider sees nothing,
  engineer can't see or write money, can't write stock directly, can't upload into another site).
- [x] **Rate limits:** OTP SMS (5/hour/number, plus Supabase Auth limits) and invites (30/day/user). Configure Supabase's
  auth limits too, in Dashboard > Authentication > Rate Limits.
- [x] **Webhooks verify signatures:** Razorpay HMAC, Standard Webhooks for the SMS hook, shared secrets for the push webhook
  and cron. Constant-time compare.
- [x] **Uploads:** private buckets, a size limit per bucket (100 MB media, 20 MB PDFs), mime allow-lists, and paths scoped to
  a project the uploader can write. Files are served from Supabase Storage's own domain with the user's token.
- [x] **No user data in URLs or logs:** URLs carry ids only. Functions return error codes, never row data or messages from
  inside. The app never shows server text.
- [ ] **Dependencies audited:** run `gradle dependencyUpdates` / GitHub Dependabot. There is no npm project to `npm audit`.
  Edge functions pin `@supabase/supabase-js@2.45.4`.
- [ ] **Privacy policy** listing every processor: Supabase (hosting, India region), MSG91 (SMS), Razorpay (payments),
  Google Firebase (push). Written at `/replica-launch`, needed for the Play Store's data safety form.

## Your setup (in this order)

1. **DLT registration** (longest lead time): register your business as a principal entity and get two SMS templates
   approved: the OTP ("{#var#} is your code for Site. Valid 10 minutes.") and the invite. MSG91 helps with this.
2. **Supabase:** create a project in **Mumbai (ap-south-1)**. Then:
   - `supabase link`, then `supabase db push` (runs the 5 migrations)
   - enable the extensions `pg_cron` and `pg_net` (Dashboard > Database > Extensions), then re-run the jobs migration
     (or `supabase db push` again)
   - Vault secrets: `project_url` (your `https://<ref>.supabase.co`) and `cron_secret` (the same value as CRON_SECRET)
   - Authentication > Providers > Phone: enable it. Hooks > Send SMS: point it at the `send-sms` function and copy its
     secret into SEND_SMS_HOOK_SECRET
   - Database > Webhooks: on `notifications` INSERT, call the `push` function with header `x-webhook-secret: <PUSH_WEBHOOK_SECRET>`
3. **Razorpay** (test mode): create 3 plans (Pro, Pro+, Advanced) and put their ids in RAZORPAY_PLAN_*. Add a webhook to
   `https://<ref>.supabase.co/functions/v1/razorpay-webhook` with the `subscription.*` events and a secret.
4. **Firebase:** create a project, add the Android app (`app.site.demo`, or your id after `/replica-brand`), and download a
   service account key for FCM_SERVICE_ACCOUNT. Keep `google-services.json` for the push step in the app.
5. **Secrets:** fill `.env.local` from `.env.example`, then `supabase secrets set --env-file .env.local` and
   `supabase functions deploy`.
6. **Build against it:** `SUPABASE_URL=... SUPABASE_ANON_KEY=... gradle :app:assembleDebug`. Sign in with your number and
   run the core loop on two phones. That's the integration test this container couldn't run.

## Known gaps

- Push registration in the app (needs your Firebase config). Server side is done.
- Billing, invite and team screens. Their functions exist; the screens come with S35/S36 and plan pages.
- A report made on one phone draws other phones' photos as grey boxes until those photos have been opened there.
- Material, payables, expenses and issues have tables and access rules, but no sync or screens yet.

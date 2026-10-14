# Architecture: site app (a rebuild of Powerplay's core features)

Reads `replica/recon.md` (38 screens, 10 flows) and `replica/features.csv` (79 features).
Schema: `supabase/migrations/20261014000000_init.sql`, checked by `supabase/tests/smoke.sql` (see "Proving it").
The name is a placeholder until `/replica-brand`.

## Stack

The user's existing app (Trapline) is Kotlin + Jetpack Compose, built into an APK by GitHub Actions.
The clone stays on that stack for the phone and puts everything else on one managed backend.

| layer | choice | why |
| --- | --- | --- |
| Android app | Kotlin + Jetpack Compose, min SDK 24 | The stack already in this repo and its CI. Native camera and background work. Runs on the low-end phones site teams carry. |
| local data / offline | Room + WorkManager | Every screen reads the local database first. Writes go to an outbox that WorkManager pushes when there is signal. |
| backend | Supabase (Mumbai region) | Postgres, auth, storage, realtime and functions in one managed service. Row level security keeps access rules in one place. The Mumbai region keeps latency low and data in India. |
| database | Postgres 15 on Supabase | One database. Stock, payables and expenses all reconcile in SQL. |
| API | PostgREST (auto from the schema) + a few RPCs and Edge Functions | Most screens are CRUD on tables RLS already protects. Custom code only where a transaction or a secret is needed. |
| auth | Supabase phone OTP, SMS through MSG91 | The original signs in by phone (recon S28). MSG91 handles Indian DLT templates. Twilio Verify is the swap. |
| payments | Razorpay Subscriptions | Indian SaaS: UPI AutoPay, cards, netbanking, GST invoices. Stripe doesn't fit domestic Indian subscriptions. |
| push | Firebase Cloud Messaging | The standard on Android. Free. |
| email | Resend | Only receipts and report links. Most sharing goes through the Android share sheet (WhatsApp). |
| files | Supabase Storage: buckets `project-media`, `project-drive`, `reports` | Same RLS model as the tables. Resumable (TUS) uploads for photos on weak networks. |
| jobs | Supabase Cron (pg_cron) + Edge Functions | Nightly and hourly jobs with no extra service. |
| PDF reports | A small worker on Fly.io (Node + headless Chromium, HTML → PDF) | Chromium shapes Devanagari, Tamil and the other Indic scripts correctly. Most PDF libraries don't. |
| web dashboard (phase 3) | Next.js + TypeScript on Vercel, same Supabase | For owners at a desk. Not needed for the core loop. |
| Android libraries | supabase-kt (Ktor), kotlinx.serialization, Hilt, CameraX, Coil, Room, WorkManager | All official or de facto standard. |

Repo layout (created during `/replica-build`): `site-app/` (Android), `supabase/` (migrations, tests, functions),
`report-worker/` (PDF), with a new GitHub Actions workflow for `site-app/`. Trapline's `trapline.zip` and
`build.yml` stay untouched.

## Schema

Tables: **51** (+ 2 views). Access rules: **Postgres row level security**, 87 policies, all built on helpers in schema `app`.
Full SQL: `supabase/migrations/20261014000000_init.sql`.

| area | tables |
| --- | --- |
| tenancy | organisations, profiles, org_members, invites, projects, project_members, project_settings, doc_counters, subscriptions, webhook_events |
| work | task_categories, tasks, task_updates, issues, comments, activity_events, work_orders, work_order_lines, subcontractors |
| media and files | albums, media, drive_files, reports, chat_messages, notifications, devices |
| procurement | vendors, materials, indents, indent_lines, purchase_orders, po_lines, grns, grn_lines |
| inventory | transfer_requests, transfer_request_lines, transfer_orders, transfer_order_lines, material_issues, material_issue_lines, material_returns, material_return_lines, stock_adjustments, **stock_movements** |
| labour and money | workers, attendance, payables, payments, expenses |
| sync and audit | tombstones, audit_log |
| views | `stock_balances` (stock per project, owner and material), `expense_lines` (the six S26 buckets) |

The decisions that matter:

- **Stock is a ledger.** Clients never write a stock number. They write documents (GRN, issue, return, transfer,
  adjustment), and triggers turn each document line into rows in `stock_movements`. Stock is `sum(qty)`.
  Editing or deleting a document rewrites its movements, so corrections just work. Offline phones can't conflict
  on a stock count because there is none to conflict on. Two owners per project: the project's own inventory, and each
  subcontractor's (recon S15). Issuing to a subcontractor moves stock from one to the other. Issuing to a task consumes it.
- **Transfers move on receipt.** Stock leaves the sending site when the transfer is created and arrives when the receiving
  site marks it received. While it is in transit it is in neither site.
- **Money is paise (`bigint`), quantities are `numeric(14,3)`.** No floats anywhere.
- **Every rupee counted once.** Material, subcontractor and attendance spend come from `payables`. Petty spend,
  miscellaneous and assets come from `expenses`. A partial unique index allows one live payable per PO and per GRN.
- **Wages come from attendance.** `preview_attendance_payables` prices days × daily wage (or hours × hourly wage) plus
  overtime, using the project's shift length (S24), and skips days already in a live payable.
- **One attendance record per worker per day** (`unique (project_id, worker_id, work_date)`). When two phones mark the same
  worker offline, the records merge on sync instead of doubling the wage bill.
- **Corrections are allowed and audited.** Users of the original can't delete wrong attendance or GRN entries (recon src 11).
  Here they can, and `audit_log` keeps the old row for attendance, GRNs, issues, adjustments, payables, payments, expenses and workers.
- **Human numbers per project** (task #12, GRN #4) are assigned by a trigger when the row reaches the server.
  Offline rows show "pending".
- **The feed can't be forged.** `activity_events` is written by triggers only.
- **Dates are validated in the database** (project, task, period and report ranges), so the raw backend error the
  original shows on S21 can't happen here. The app still validates first and shows plain language.
- **Roles.** Org `owner`/`admin` (the original's "Organization Management Team") act as manager on every project.
  Project roles are `manager`, `engineer`, `supervisor`, `storekeeper`, `accountant` and `viewer`.
  - **Money** (POs, payables, payments, expenses, work orders and their rates) is visible to manager and accountant only.
  - **Viewers** read only.
  - **Catalogues** (materials, vendors, subcontractors, categories) are shared org-wide.
- **Deletion:** deleting an organisation cascades everything. Deleting a user cascades their profile and memberships.
  Their authored rows keep the data, with the author set to null.

## API

PostgREST paths are `/rest/v1/<table>`. "RLS" means the row level security above decides who can call it.
Every write from the app is an upsert by client-generated `id`
(`POST ...?on_conflict=id` + `Prefer: resolution=merge-duplicates`), so a retried request can't duplicate.

### F10 Sign in, team and settings

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| POST /auth/v1/otp | send OTP by SMS | anyone | phone | — | S01 |
| POST /auth/v1/verify | verify OTP, get a session | anyone | phone, code | JWT + refresh token | S01 |
| PATCH /rest/v1/profiles?id=eq.me | name, email, language | self | fields | profile | S28 |
| POST /rest/v1/rpc/create_organisation | new org, caller becomes owner | signed in | name | org id | S29 |
| PATCH /rest/v1/organisations | rename, logo | org admin | name, logo_path | org | S29 |
| POST /rest/v1/rpc/accept_invites | join every pending invite for my phone | signed in | — | count | S01 |
| POST /functions/v1/invite | create invite + send SMS link | org admin, project manager | phone, role, project | invite | S36 |
| GET /rest/v1/project_members | members of a project | RLS read | project_id | rows + profiles | S36 |
| PATCH/DELETE /rest/v1/project_members | change role, remove | manager | id, role | row | S36 |
| GET/PATCH /rest/v1/project_settings | remarks toggle, shift hours | read: member, write: manager | project_id | row | S24 |
| POST /functions/v1/billing/subscribe | start a Razorpay subscription | org owner/admin | plan | checkout url | upgrade |

### F02 Projects and reports

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| GET /rest/v1/projects | my projects (search by name) | RLS | `name=ilike.*q*` | rows | S04 |
| POST /rest/v1/projects | create project (+ settings, + me as manager) | org admin | name, dates, image | row | S03 |
| PATCH /rest/v1/projects | edit site profile, status | manager | fields | row | S35 |
| POST /rest/v1/reports | queue a report | non-viewer | type, from/to, parts, language | row (queued) | S06 |
| GET /rest/v1/reports | generated reports, newest first | member | project_id | rows | S07 |
| GET /storage/v1/object/sign/reports/... | signed download URL | member | path | URL (1 h) | S07 share |

### F01, F07 Tasks, progress and issues

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| GET /rest/v1/tasks | list, search by number or name, filter chips | member | status, due_date, work_order_id | rows | S08 |
| POST /rest/v1/tasks | create task | non-viewer | name, category, qty, unit, dates, assignee | row (+ number) | S10 |
| POST /rest/v1/task_updates | log progress + remarks | non-viewer | task_id, work_date, qty/pct, remarks | row | S09 |
| PATCH /rest/v1/tasks | progress rollup, status | non-viewer | done_qty, progress_pct, status | row | S09 |
| GET/POST /rest/v1/issues | list (Open, Closed, All), raise | member, non-viewer | title, due, assignee, task | row | S31 |
| PATCH /rest/v1/issues | close or reopen | non-viewer | status, closed_at | row | S31 |
| GET/POST /rest/v1/comments | thread on a task or issue | member | body | row | S27 |
| GET /rest/v1/activity_events | feed | member | project_id, before | rows | S27 |

### F09 Photos and files

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| POST /storage/v1/upload/resumable | TUS upload of a compressed photo or video | non-viewer | file → `project-media/<project>/<id>.jpg` | path | S32 |
| POST /rest/v1/media | register the upload, link it to an update, issue, GRN or expense | non-viewer | id, path, taken_at, links | row | S30 |
| GET /rest/v1/media | gallery, by album | member | project_id, album_id | rows | S30 |
| POST /rest/v1/albums | new album | non-viewer | name | row | S30 |
| GET/POST /rest/v1/drive_files (+ storage `project-drive`) | files and drawings | member / non-viewer | folder, file | rows | S34 |

### F03, F04 Material

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| GET/POST /rest/v1/materials | catalogue, add material | org member | name, spec, uom | row | S15, S16 |
| GET /rest/v1/stock_balances | stock per material, project or subcontractor inventory | member | project_id, subcontractor_id | rows | S15 |
| POST /rest/v1/stock_adjustments | opening stock, consumption, correction | non-viewer | material, signed qty, kind | row | S16 |
| POST /rest/v1/indents + indent_lines | raise an indent | non-viewer | lines, needed_by | rows | S11, S12 |
| PATCH /rest/v1/indents | approve or reject | manager | status | row | S12 |
| POST /rest/v1/purchase_orders + po_lines | PO from an indent or new | manager, accountant | vendor, lines, rates, tax | rows | S13 |
| POST /functions/v1/po-pdf | PO as a shareable PDF | manager, accountant | po_id | signed URL | S13 |
| POST /rest/v1/grns + grn_lines | goods received (with or without a PO), challan photo | non-viewer | lines, received_on | rows → stock up | S14 |
| POST /rest/v1/material_issues + lines | issue to a task or subcontractor | non-viewer | lines, task or sub | rows → stock moves | S19 |
| POST /rest/v1/material_returns + lines | return to stock | non-viewer | lines, from | rows → stock moves | S20 |
| POST /rest/v1/transfer_requests + lines | ask another site for stock | non-viewer | source project, lines | rows | S18 |
| POST /rest/v1/transfer_orders + lines | send stock to another site | non-viewer (sender) | to project, lines | rows → stock leaves | S17 |
| PATCH /rest/v1/transfer_orders | mark received | non-viewer (receiver) | status, received_on | row → stock arrives | S17 |
| GET/POST /rest/v1/vendors, /subcontractors | parties | org member | name, phone, gstin | row | S13, S15 |

### F05, F06 Labour and money

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| GET/POST /rest/v1/workers | add or edit worker, wage | non-viewer | name, skill, wage, contractor | row | S22 |
| GET /rest/v1/attendance | a day's register (date stepper) | member | project_id, work_date | rows | S21 |
| POST /rest/v1/attendance?on_conflict=project_id,worker_id,work_date | mark or change a worker's day | non-viewer | status, hours, OT, remarks | row | S21 |
| POST /rest/v1/rpc/preview_attendance_payables | wages due for a period | manager, accountant | project, from, to | per worker | S23 |
| POST /rest/v1/payables | record a payable (attendance, PO, GRN, work order, custom) | manager, accountant | kind, party, period, amount | row | S23 |
| POST /rest/v1/payments | pay all or part (status updates itself) | manager, accountant | payable, amount, mode | row | S23 |
| GET /rest/v1/expense_lines | six buckets + records | manager, accountant | project_id, date range | rows | S26 |
| POST /rest/v1/expenses | petty spend, misc, assets (+ bill photo) | manager, accountant | category, amount, date | row | S26 |
| POST /rest/v1/work_orders + lines | subcontractor work order | manager, accountant | sub, items, rates | rows | phase 3 |

### F08 Sync, chat and notifications

| method path | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| GET /rest/v1/<table>?project_id=in.(…)&updated_at=gt.<cursor> | pull changes since the last sync, per table | RLS | cursor | rows | all |
| GET /rest/v1/tombstones?deleted_at=gt.<cursor> | pull deletions | RLS | cursor | ids | all |
| GET/POST /rest/v1/chat_messages + Realtime channel | chat (org-wide or per project) | member | body | row / live events | S33 |
| POST /rest/v1/devices | register FCM token | self | token | row | — |
| GET/PATCH /rest/v1/notifications | inbox, mark read | self | — | rows | bell |

Routes: **58** (rows above, counting a GET/POST pair as one).

**Webhooks in:** Razorpay (`/functions/v1/razorpay-webhook`): signature-checked; the event id goes into
`webhook_events` first, so a replay does nothing. MSG91 delivery reports (optional).
**Webhooks out:** none. Database webhooks on `activity_events`, `notifications` and `reports` call Edge Functions internally.

**Jobs:**

| job | schedule | does |
| --- | --- | --- |
| render-report | on insert into `reports` (database webhook), plus a sweep every 5 min for `queued` older than 2 min | worker renders the HTML → PDF in the report's language, uploads to `reports/`, sets `ready` |
| push-fanout | on insert into `activity_events` and `notifications` | FCM to the right members (assignee, managers) |
| daily-nudge | 18:30 IST | projects with no update today: one push to managers. Replaces the original's support-raised "inactivity" issues (S31). |
| delayed-tasks | 06:00 IST | notifications for tasks past their due date and not completed |
| expire-reports | 02:00 IST | delete report files past `expires_at` (90 days; the original keeps 15) |
| storage-orphans | Sundays 03:00 IST | delete uploaded files with no `media`/`drive_files` row after 24 h |
| subscription-sync | hourly | reconcile `subscriptions` with Razorpay in case a webhook was missed |

Only official, public APIs with the user's own keys (Supabase, MSG91, Razorpay, FCM, Resend).
Never Powerplay's endpoints.

## Offline sync (the core of the app)

- **Writes:** the app writes to Room and to an `outbox` table in the same transaction. WorkManager drains the outbox in order
  (headers before lines) whenever there is a network. Every row has a client-generated UUID, so retries are safe upserts.
- **Reads:** per table, pull `updated_at > cursor − 2 min`. The overlap covers transactions that commit out of order.
  Rows are upserted locally, then tombstones are applied.
- **Conflicts:** the last write wins per row. Attendance merges on (project, worker, day). Stock can't conflict because it is derived.
  Numbers (task #, GRN #) arrive with the pulled row.
- **Photos:** compressed on the phone (long edge 1600 px, JPEG ~80%), uploaded with TUS (resumes after a dropped
  connection), and the `media` row is written only after the upload finishes.
- **Realtime:** only for chat and the open project's feed. Everything else syncs on app open, every 15 minutes in
  the background, and on pull-to-refresh.

## The parts that bite

- **Time zones:** a site day is a `date` in the project's time zone (`Asia/Kolkata` by default). Never derive it from UTC
  on the server: attendance marked at 00:30 IST belongs to that day. The phone sends the date.
- **Idempotency:** client UUIDs on every row; Razorpay events stored by event id before processing; report jobs keyed by report id.
- **Races:** two phones mark the same worker → the natural key merges them. Two managers record the same PO payable →
  the partial unique index rejects the second. Stock can go negative when offline phones disagree.
  That is shown as a red badge with "check this", not blocked. Blocking a GRN that happened on site would be worse.
- **Rate limits:** Supabase auth limits OTP requests per phone (and MSG91 charges per SMS). Add a 30-second resend
  timer and a captcha only if abuse appears.
- **SMS in India:** OTP SMS needs DLT registration (entity + template) with the telecom operators before launch. It takes
  days, so start it early.
- **File sizes:** photos ≤ ~500 KB after compression. Videos are capped at 100 MB and 60 s. Drive files are capped at 100 MB.
  Free-plan storage quota per org enforced in the upload function.
- **Search:** tasks by number or name via `ilike` + `pg_trgm` index if lists get long. Search on the phone is a local query in Room.
- **Realtime:** chat only (Supabase Realtime with RLS). No live cursors.
- **Offline:** see above. Sign in and accepting an invite need a network. Everything after that works without one.
- **Low-end phones:** Compose lists with stable keys, Coil thumbnails, no full-size images in memory, and an APK size budget.
- **Reports in Indic scripts:** render with Chromium and bundled Noto Sans fonts per script. Test every language with real
  data before calling a language done.
- **Multi-tenancy:** every table carries `org_id` or `project_id`, and every policy goes through `app.*` helpers.
  The smoke test proves an outsider sees nothing.
- **Money visibility:** engineers see stock and attendance but not rates, payables or expenses. Worker wage rates are on `workers`,
  which engineers can read. If owners want wages hidden from engineers, move the rates to a money-only table.
- **Deletion (DPDP Act 2023, India's data protection law):** account deletion removes the profile and memberships and keeps
  authored business records with the author set to null. An org owner can delete the whole organisation.
- **Free vs paid:** the plan lives on `organisations.plan`. Limits (projects, members, storage, report languages) are checked
  in the app and in the Edge Functions that create things, not in RLS.

## Proving it

The migration has been run and tested on Postgres 16:

```
psql -d <scratch db> -f supabase/tests/local_auth_stub.sql \
                     -f supabase/migrations/20261014000000_init.sql \
                     -f supabase/tests/smoke.sql        # prints "smoke: ok"
```

The smoke test creates an owner, an invited engineer and an outsider. It then checks:

- **Numbering:** per-project numbers.
- **Feed:** the events get written, and a client can't forge one.
- **Stock:** GRN, issue to a subcontractor, return, GRN correction, deleted issue, transfer in transit and received.
- **Attendance:** two phones marking the same worker and day merge into one record.
- **Wages:** 3.5 days × ₹800 + 2 h OT × ₹100 = ₹3,000, and days already paid are not offered again.
- **Payments:** a part payment, then full payment.
- **Expenses:** the totals.
- **Corrections:** audit log and tombstones.
- **Access:** the engineer can't see or write money and can't write stock directly. The outsider sees nothing.

`local_auth_stub.sql` only fakes Supabase's `auth` schema and roles for local runs. It is never applied to Supabase.

## Build order

1. **Vertical slice: the core loop, ugly.**
   - **Screens:** S01 sign in, S29 org, S04 projects, S03 create project, S05 shell, S08 tasks, S10 create task,
     S09 progress + photo (S32 camera), S21/S22 attendance, S06 a report with Task Updates + Photos + Attendance, rendered and shared.
   - **Tables:** organisations, profiles, org_members, projects, project_members, project_settings, tasks, task_updates,
     media, workers, attendance, reports, activity_events.
   - **Routes:** auth OTP, create_organisation, projects, tasks, task_updates, storage upload, media, workers, attendance,
     reports + render-report job.
   - **Proves:** OTP, RLS, offline outbox and pull, photo upload on a bad network, and the PDF worker with Hindi.
2. **Must-haves** (33 in features.csv), by area:
   - **Team:** invites + accept_invites, members, roles (S36), profile (S28).
   - **Material:** materials, stock_adjustments, stock_balances (S15, S16), Site GRN (S14), issue to task (S19).
   - **Feed and gallery:** S27, S30. Shift setting S24. Labour on site count.
   - **Platform:** the offline banner, plain-language errors, Hindi.
3. **Should-haves**, then could-haves:
   - **Procurement:** indents → approve → PO + PDF → deliveries → GRN against PO (S11–S14).
   - **Inventory:** transfers and requests (S17, S18), returns (S20).
   - **Money:** payables preview + record + payments (S23), expenses (S26), issues (S31), summary report, report presets, albums.
   - **Then:** work orders and subcontractor inventory, drive (S34), chat (S33), Razorpay plans, web dashboard, six more languages, iOS.
4. **Fixes from `/replica-entrepreneur`**, once it has run. The recon already queues four:
   - corrections with an audit trail
   - reports kept 90 days
   - plain-language errors
   - nudges instead of support-raised issues

Next step: `/replica-design`. Take the "still unseen" screenshots first (see `recon.md`).

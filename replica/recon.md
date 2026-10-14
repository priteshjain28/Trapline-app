# Recon map: Powerplay Site Management App (Android, plus iOS and a web dashboard)

Scope: everything the public sources describe. That is projects and tasks, daily progress
reports with photos, labour attendance and payroll, materials (request, purchase
order, GRN, stock, transfers), petty cash and expenses, subcontractor billing,
documents, quality checks, roles and reports. It is phased in "Size" below, because
the whole thing is XL.
For: a product to sell to small and medium Indian builders and contractors, the same market as Powerplay.
Date: 2026-10-14

## How this map was made, and its limits

Public sources only, read through web search. This container's network policy
blocks direct fetches of play.google.com, apps.apple.com, getpowerplay.in and
capterra.com. As a result:

- **No screenshots yet.** `replica/screens/` is empty. Screens, routes and states
  below are **inferred** from feature descriptions and how apps in this category
  usually work. They were not observed. Every screen row says so.
- **No account walkthrough.** Nobody drove a real Powerplay account. If you have
  your own account, check Powerplay's terms first. Many SaaS terms forbid using an
  account to build a competing product. If yours do, stay on public sources only.
- **Next pass:** open the Play Store listing on your phone, save its screenshots into
  `replica/screens/` as `S03-projects.png` and so on, and re-run `/replica-recon`
  to replace the inferred rows with observed ones.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | Play Store listing | https://play.google.com/store/apps/details?id=in.powerplay.android.fieldapp | "#1 Construction Management App for small & medium construction businesses & contractors". Lists daily log, photos, tasks, labour and materials, and "Generate Daily Progress Reports in just a few minutes". Read via search snippets only. |
| 2 | App Store listing | https://apps.apple.com/in/app/powerplay-manage-projects/id1552606148 | Developer Coffer Internet Services Pvt Ltd. Free, no in-app purchases (selling is B2B). Labour attendance "across office & site teams". Material "requests and movement". |
| 3 | Marketing site, home | https://www.getpowerplay.in/ | Modules: Project/Work Mgmt, material stock on site, POs and GRNs, labour, petty cash. |
| 4 | Labour & payroll page | https://www.getpowerplay.in/features/labour-payroll-management/ | Real-time attendance, overtime from work hours, integrated payroll, task allocation. |
| 5 | Labour page | https://www.getpowerplay.in/labour-management/ | Attendance, overtime, wages, advances. |
| 6 | Project/work page | https://www.getpowerplay.in/features/project-work-management/ | Progress tracking, DPRs, timelines. |
| 7 | Owner page | https://www.getpowerplay.in/owner/ | Multi-project dashboard. Vendor payments, wages and expenses in real time. Admin-defined permissions. |
| 8 | Generic landing | https://www.getpowerplay.in/generic/ | 8 languages: English, Hindi, Tamil, Odia, Telugu, Bengali, Malayalam, Kannada. Daily and weekly reports "in just a click". |
| 9 | Capterra pricing | https://www.capterra.com/p/268357/Powerplay/pricing/ | Pro ₹71,999/yr, Pro+ ₹1,19,999/yr, Advanced custom quote. Directory data, unverified. |
| 10 | Capterra India reviews | https://www.capterra.in/software/1036292/powerplay | Reviewers mention GRN, indents and purchase formats. Site-office communication praised. |
| 11 | SoftwareAdvice reviews | https://www.softwareadvice.co.uk/software/390766/powerplay | 4.5/5 (8 reviews). Complaint: "delete option is not available in attendance & grn report". |
| 12 | Software Finder | https://softwarefinder.com/construction/powerplay | Lists POs, subcontractor management, document management, quality control. |
| 13 | Onsite comparison | https://onsiteteams.com/resources/feature-comparisons/powerplay-vs-onsite/ | Competitor-written. Says BOQ is weak, billing and cost tracking exist, retention isn't detailed, expense tracking exists. |
| 14 | SiteSetu comparison | https://sitesetu.app/blog/powerplay-alternative-india | Competitor-written. Calls offline-first a gap to look for. Powerplay's offline support is unverified. |

## Core loop

The site engineer logs today's work on a phone (progress, photos, labour present,
material received or used), and the owner sees every site in one report without
calling anyone.

## Screens

All rows are **inferred** (see the limits above). The states listed are the ones to
build, not ones that were observed.

| ID | screen | route / how to reach | purpose | key components | states to build |
| --- | --- | --- | --- | --- | --- |
| S01 | Language picker | first launch | choose 1 of 8 languages | language list | default |
| S02 | Sign in | after S01 | phone number + OTP (guess: the Indian SMB norm) | phone input, OTP input | empty, sending, wrong OTP, expired |
| S03 | Create company / join | first sign in | create a company, or accept an invite | form, invite card | empty, invited |
| S04 | Projects list (home) | after sign in | all projects the user can see | project cards (progress %, today's labour, alerts), FAB | empty, filled, loading, offline |
| S05 | Create / edit project | S04 FAB | name, location, start/end dates, budget | form, date picker, map pin | empty, validation error |
| S06 | Project dashboard | tap a project | today at a glance: progress, labour, materials, cash | stat tiles, tab bar (Tasks, Labour, Material, Money, More) | filled, no data today |
| S07 | Members & roles | S06 > More | invite people, set role and project access | member list, role picker | empty, pending invite, permission denied |
| S08 | Tasks list | S06 Tasks tab | work items with status and deadline | filter chips, task rows, progress bars | empty, filled, overdue |
| S09 | Task detail | S08 row | progress %, quantity done vs planned, photos, comments, assignee | progress slider, photo grid, comment thread | not started, in progress, done, blocked |
| S10 | Add progress update | S09 / S06 quick action | log work done today with photos | quantity input, camera, note | draft, uploading, offline queued, failed |
| S11 | Daily progress report | S06 > Reports | auto-built DPR for a date: work, labour, material, photos, issues | date picker, sections, Share/PDF | generating, ready, empty day |
| S12 | Attendance register | S06 Labour tab | mark present/absent/half-day/OT per worker for a date | worker rows, status toggles, bulk "all present" | empty, filled, locked (past date) |
| S13 | Worker / labour contractor | S12 > add | worker name, skill, daily wage, contractor | form | empty, validation error |
| S14 | Payroll & advances | S06 Labour > Payroll | wages due per worker or contractor for a period, minus advances | period picker, totals, Pay / Add advance | nothing due, due, paid |
| S15 | Site stock | S06 Material tab | materials on site: in, used, balance | material rows, low-stock flag | empty, filled, low stock |
| S16 | Material request (indent) | S15 > Request | ask for materials with qty and needed-by date | item picker, qty, date | draft, submitted |
| S17 | Requests & approvals | S15 > Requests | approve, reject or convert requests to POs | status tabs, approve/reject | pending, approved, rejected, empty |
| S18 | Purchase order | S17 > Create PO | PO to a vendor with lines, rates, tax; shared as a PDF | vendor picker, line items, totals, PDF | draft, sent, partly received, closed |
| S19 | GRN (material received) | S18 / S15 > Receive | record delivery against a PO, with challan photo | qty received vs ordered, photo | full, partial, excess |
| S20 | Material issue / transfer | S15 > Use / Transfer | log consumption, or move stock to another site | qty, destination project | done, insufficient stock |
| S21 | Vendors | More > Vendors | supplier list with what's owed | vendor rows, balance | empty, filled |
| S22 | Petty cash & expenses | S06 Money tab | site cash given and spent, by category, with bill photos | balance card, expense rows, add form | empty, filled, negative balance |
| S23 | Subcontractor work orders & bills | Money > Subcontractors | work order scope and rates; bills against measured work | WO form, bill lines, retention/advance | draft, approved, paid |
| S24 | Documents & drawings | More > Documents | folders of drawings and files, with permissions | folder tree, file viewer, upload | empty, uploading, no access |
| S25 | Quality checklists | More > Quality | inspection checklists against tasks, with pass/fail and photos | checklist, pass/fail, photo | open, passed, failed |
| S26 | Reports hub | More > Reports | DPR, attendance, material, expense and labour reports; PDF/Excel export | report list, filters, export | generating, ready |
| S27 | Activity & notifications | bell icon | approvals waiting, updates from site | feed rows | empty, unread |
| S28 | Settings | profile | company, language, units, expense categories, material catalogue | lists, toggles | default |
| S29 | Owner web dashboard | web | every project at once: progress, spend, labour, alerts | table, charts, filters | empty, filled |

## Flows

```
F01 Site engineer logs the day
    S04 -> S06 -> S10 (progress + photos) -> S12 (attendance) -> S19/S20 (material in/used) -> S11 (DPR share)
    happy path clicks: ~12, target to beat: 8
    edge: no network on site, photo upload fails halfway, worker added mid-day, back-dated entry

F02 Owner checks all sites
    S29 (or S04) -> S06 -> S11
    happy path clicks: 2-3
    edge: no update from a site today, too many projects to scan

F03 Material request to delivery
    S16 -> S17 (approve) -> S18 (PO, share PDF) -> S19 (GRN, partial) -> S15 (stock updates)
    happy path clicks: ~10
    edge: partial delivery, wrong item, price changed, request rejected, PO cancelled

F04 Mark attendance and pay labour
    S12 (mark day) -> ... -> S14 (period wages - advances) -> mark paid
    happy path clicks: 3 per day, 3 per payout
    edge: half day, overtime hours, worker moves sites, advance bigger than wages, past day edited

F05 Spend petty cash
    S22 -> add expense (amount, category, bill photo) -> balance updates
    happy path clicks: 4
    edge: cash goes negative, expense without a bill, wrong project

F06 Subcontractor bill
    S23 work order -> measure work done -> bill -> approve -> pay
    edge: retention held back, advance recovery, a disputed quantity

F07 Plan and track tasks
    S08 -> create task (qty, unit, dates, assignee) -> S09 updates -> overdue alert
    edge: task blocked, dates slip, task reassigned

F08 Invite the team
    S07 -> phone number + role + projects -> invitee signs in (S02) -> lands in S04 with only their projects
    edge: invite to a number already in another company, role downgrade, user removed

F09 Quality inspection
    S25 -> pick checklist for a task -> pass/fail items with photos -> failed items raise a task
    edge: re-inspection, inspector without permission

F10 Share and export reports
    S26 -> choose report + date range -> PDF/Excel -> share via the system share sheet (WhatsApp, email)
    edge: very large date range, report in a regional language
```

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| Button | primary, secondary, text, danger, FAB | default, pressed, disabled, loading | all |
| Text / number input | text, phone, OTP, quantity+unit, currency (₹, Indian digit grouping) | empty, focused, error, disabled | S02, S05, S10, S13, S16, S18, S22 |
| Date picker | single, range | default, disabled past/future | S05, S11, S14, S16, S26 |
| Project card | — | progress, alert badge | S04 |
| Stat tile | count, money, % | normal, warning | S06, S29 |
| List row | task, worker, material, expense, vendor, member | default, selected, swipe actions | most |
| Status chip | draft, pending, approved, rejected, partial, done, overdue | — | S08, S17, S18, S19, S23 |
| Attendance toggle | P / A / ½ / OT | per state | S12 |
| Progress bar / slider | read-only, editable | 0–100% | S04, S08, S09, S10 |
| Photo grid + camera | capture, gallery, viewer | uploading, failed, queued offline | S09, S10, S19, S22, S25 |
| Line-item editor | PO line, bill line, request line | add, edit, remove | S16, S18, S23 |
| Bottom tab bar | project tabs | active | S06 |
| Filter chips | status, date, assignee | on/off | S08, S17, S26 |
| Comment thread | — | empty, sending | S09 |
| Empty state | per screen | — | all lists |
| Offline banner / sync badge | — | offline, syncing, failed | global |
| PDF preview + share | — | generating, ready | S11, S18, S26 |
| Role picker | — | — | S07 |
| Toast / snackbar | success, error, undo | — | global |

## Inferred data model

```
Company      id, name, plan, language_default, created_at
             evidence: B2B pricing per company (src 9), admin-defined permissions (src 7)
             confidence: high
User         id, phone, name, language
             evidence: phone-first market (guess), 8 languages (src 8)
             confidence: medium
Membership   company_id, user_id, role (owner | admin | site_engineer | purchase | accounts | viewer), project_ids[]
             evidence: "access permissions defined by the project admin" (src 7)
             confidence: medium. Role names are a guess.
Project      id, company_id, name, location, start_date, end_date, budget, status
             evidence: multi-project dashboard (src 3, 7)
             confidence: high
Task         id, project_id, name, unit, planned_qty, done_qty, start, due, assignee_id, status, parent_id
             evidence: task management, timelines (src 1, 6)
             confidence: high for the entity, guess for quantity-based progress
ProgressUpdate id, task_id, user_id, date, qty, note, photo_ids[]
             evidence: daily log + photos (src 1)
             confidence: high
Photo        id, project_id, url, taken_at, lat, lng, uploaded_by
             confidence: high for the entity, guess for geotagging
DailyReport  project_id, date, generated_pdf_url (derived from the day's records)
             evidence: "DPR in a few minutes" (src 1)
             confidence: high
Worker       id, company_id, name, skill, daily_wage, ot_rate, contractor_id?
LabourContractor id, company_id, name, phone
AttendanceEntry worker_id, project_id, date, status (P | A | HALF), ot_hours
WagePayment  worker_id | contractor_id, period, amount, paid_at
Advance      worker_id, amount, date, recovered_amount
             evidence: attendance, overtime, wages, advances (src 4, 5)
             confidence: high for attendance and OT, medium for payment modelling
Material     id, company_id, name, unit, category
Stock        project_id, material_id, qty (derived from in - out ± transfers)
MaterialRequest id, project_id, requested_by, needed_by, status, lines[material_id, qty]
Vendor       id, company_id, name, phone, gstin
PurchaseOrder id, vendor_id, project_id, request_id?, status, lines[material_id, qty, rate, tax], total
GRN          id, po_id?, project_id, received_at, challan_photo_id, lines[material_id, qty]
MaterialIssue id, project_id, material_id, qty, task_id?, date
Transfer     id, from_project_id, to_project_id, lines[material_id, qty]
             evidence: requests, POs, GRNs, indents, stock, movement (src 2, 3, 10)
             confidence: high for request/PO/GRN, medium for issue/transfer
PettyCashLedger project_id, entries[type in|out, amount, category_id, note, bill_photo_id, date]
ExpenseCategory id, company_id, name
             evidence: petty cash module with categories (src 3)
             confidence: high
WorkOrder    id, project_id, subcontractor_id, lines[item, unit, rate, qty], retention_pct
SubconBill   id, work_order_id, lines[item, qty_done], deductions, status
             evidence: subcontractor mgmt and billing (src 12, 13)
             confidence: medium. Retention is a guess.
Document     id, project_id, folder, name, url, access_roles[]
             evidence: document mgmt, granular access (src 7, 12)
             confidence: medium
Checklist    id, company_id, name, items[]
Inspection   id, checklist_id, task_id, results[item, pass, photo_id], inspector_id
             evidence: "quality control" (src 12)
             confidence: guess
Comment, Notification, ActivityLog
             confidence: guess (standard)
```

Relationships: Company 1-n Project, Company 1-n Membership n-1 User, Project 1-n Task 1-n ProgressUpdate,
Project 1-n AttendanceEntry n-1 Worker n-1 LabourContractor, Project 1-n MaterialRequest 1-n PurchaseOrder 1-n GRN,
Vendor 1-n PurchaseOrder, Project 1-n Stock, Project 1-1 PettyCashLedger, Project 1-n WorkOrder 1-n SubconBill,
Project 1-n Document, Task 1-n Inspection.

## Feature matrix

See `features.csv`. Must: 30, should: 20, could: 11, skip: 4.

## Out of scope (cannot or should not be cloned)

- The Powerplay name, logo, colours, illustrations, marketing copy and screenshots. `/replica-brand` gives you your own.
- Their customer base, reviews, ratings and case studies.
- Their translations. All 8 languages must be translated from scratch, by you or a translator.
- Any partner integrations or deals (none were confirmed publicly).
- Their private APIs or app code. Nothing was decompiled and nothing should be.

## Size

Screens 29, flows 10, entities ~25.

Hard parts:
1. **Offline-first sync with photos.** Sites have bad network. Entries and photos must queue locally and sync without duplicates or lost edits.
2. **Money correctness.** Payroll (half days, OT, advances), PO/GRN partials, subcontractor retention and petty cash all have to reconcile to the rupee.
3. **Roles and permissions across companies and projects**, plus approval chains (request → PO).
4. **PDF reports in 8 languages** (Indic scripts in PDFs need the right fonts and shaping).
5. **Android performance** on low-end phones.

Size as asked ("everything"): **XL. Rescope it.** Build it in phases, each one sellable:

- **Phase 1 (M–L):** S01–S12, S15–S16, S19–S20, S22, S11. Projects, tasks, progress with photos, attendance, simple material in/out, petty cash and DPR, in English + Hindi, offline-first. This is the core loop.
- **Phase 2 (M):** request → approval → PO → GRN, vendors, payroll and advances, reports hub, roles.
- **Phase 3 (M–L):** subcontractor billing, documents, quality checklists, owner web dashboard, the other 6 languages.

No promise of a perfect clone. The inferred screens need checking against real screenshots before `/replica-design`.

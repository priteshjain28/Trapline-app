# Recon map: Powerplay Site Management App (Android, plus iOS and a web dashboard)

Scope: everything. That covers projects and tasks, reports, materials (indent, PO and deliveries,
GRN, stock, transfers, issue, returns), labour attendance and payables, expenses, issues,
feed and chat, gallery and drive, members and settings. It is phased in "Size" below.
For: a product to sell to small and medium Indian builders and contractors, the same market as Powerplay.
Date: 2026-10-14 (pass 2: 26 screenshots from the user's own account)

## How this map was made

- **Pass 1:** public sources through web search. The container's network blocks the store and vendor sites.
- **Pass 2:** 26 screenshots the user took of **their own Powerplay account** (free plan, one
  near-empty project). They are in `replica/screens/`, named by screen ID. Screens marked
  **observed** come from them. Screens marked **inferred** have not been seen yet.
- **Terms:** the screenshots come from the user's own account. Check Powerplay's terms of
  service for a clause against using the product to build a competitor. If there is one,
  stop using the account and work from public sources only.
- **Still unseen** (the next screenshots to take): sign in, create project, task create and
  detail, indent form, PO form, GRN form, add worker and marking attendance with workers present,
  payable review, a generated report, chat, drive, site profile, members, labour shift, issue
  detail, expense entry. Screens with real data in them help most.

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 0 | **User's own account, 26 screenshots** | `replica/screens/` | Free plan, organisation "My Organisation", project "palak". Taken 2026-10-13. |
| 1 | Play Store listing | https://play.google.com/store/apps/details?id=in.powerplay.android.fieldapp | "#1 Construction Management App for small & medium construction businesses & contractors". DPR "in just a few minutes". |
| 2 | App Store listing | https://apps.apple.com/in/app/powerplay-manage-projects/id1552606148 | Coffer Internet Services Pvt Ltd. Free download. |
| 3 | Marketing site | https://www.getpowerplay.in/ | Modules: project/work, material, POs and GRNs, labour, petty cash. |
| 4 | Labour & payroll page | https://www.getpowerplay.in/features/labour-payroll-management/ | Attendance, overtime, payroll. |
| 5 | Labour page | https://www.getpowerplay.in/labour-management/ | Wages, advances. |
| 6 | Project/work page | https://www.getpowerplay.in/features/project-work-management/ | Progress, DPRs, timelines. |
| 7 | Owner page | https://www.getpowerplay.in/owner/ | Multi-project view, admin-defined permissions. |
| 8 | Generic landing | https://www.getpowerplay.in/generic/ | 8 languages. |
| 9 | Capterra pricing | https://www.capterra.com/p/268357/Powerplay/pricing/ | Pro ₹71,999/yr, Pro+ ₹1,19,999/yr, Advanced custom. The screenshots also confirm a **Free** plan. |
| 10 | Capterra India reviews | https://www.capterra.in/software/1036292/powerplay | GRN, indents. |
| 11 | SoftwareAdvice reviews | https://www.softwareadvice.co.uk/software/390766/powerplay | "delete option is not available in attendance & grn report". |
| 12 | Software Finder | https://softwarefinder.com/construction/powerplay | POs, subcontractor and document management. |
| 13 | Onsite comparison | https://onsiteteams.com/resources/feature-comparisons/powerplay-vs-onsite/ | Competitor-written. |
| 14 | SiteSetu comparison | https://sitesetu.app/blog/powerplay-alternative-india | Competitor-written. Offline support unverified. |

## Core loop

The site team logs the day on a phone: task progress, photos, labour attendance, and material
in and out. The owner builds one report for any date range and shares it, without
calling anyone.

## App structure (observed)

```
Org home (S04): org switcher + plan badge, chat, profile
  └─ Project (S05 shell): header "Org / Project ⇕" switcher, chat, Help
       bottom tabs: Report | Task | Material | Attendance | More
         Report     S06 report builder, S07 generated reports
         Task       S08 task list
         Material   segmented: Procurement [Indent | Deliveries | Site GRN]
                               Inventory   [Material list | Transfer Order | Transfer Requests | Material Issue | Material Returns]
         Attendance tabs: Labour Attendance | Payables
         More       Financials: Expenses
                    Others: Issues, Feed, Report, Gallery, Drive, Camera
                    Settings: Site Profile, Labour Shift, Members (n)
```

## Screens

| ID | screen | route / how to reach | purpose | key components | states | seen |
| --- | --- | --- | --- | --- | --- | --- |
| S01 | Sign in | launch | phone + OTP (the profile shows a verified +91 phone) | phone input, OTP | empty, sending, wrong OTP | inferred |
| S02 | Language picker | first launch | 1 of 8 languages | list | default | inferred (src 8) |
| S03 | Create project | S04 "Create New" | name, image, dates, status | form, date range | empty, error | inferred |
| S04 | Projects list (org home) | after sign in | all projects in the organisation | navy header: org avatar + name + plan badge "(Free)", chat icon, user avatar; "Projects" + "⊕ Create New"; search; project card: image placeholder, name, status pill "Active", progress ring "0%", "Start Date - End Date", ⋮ menu | filled (1 project); card without dates or image | **observed** |
| S05 | Project shell | tap a project | container for the 5 tabs | ✕ close, "My Organisation" over "palak ⇕" (project switcher), chat icon, "Help" outline button, bottom nav of 5 tabs | — | **observed** |
| S06 | Report builder (Report tab) | Report tab | build a report for a date range | "Generate a new Report"; type cards: **Detailed Report** (day-wise logs) / **Summary Report** (NEW badge); duration radios Today / 7 days / 15 days / Specific Date; From/To date fields; collapsible "Parts in the Report" checklist (below); sticky "Create Report" | default; a part disabled ("My Team: Not yet set up on this project") | **observed** |
| S07 | Generated reports | More > Report | list of past reports | info banner: "You will be able to access generated reports for up to 15 days from their creation date"; empty state + "Create Report" | empty | **observed** |
| S08 | Task list | Task tab | tasks for the project | "Search by id, name"; "List View ▾" view switcher; filter button; chips **In Progress / Task with Work order / Delayed / Task with Open Issues**; empty state "Get organised by creating and updating your 1st task!"; FAB + | empty | **observed** |
| S09 | Task detail / update progress | S08 row, Feed "Update Task Progress" | progress, photos, remarks; task category in brackets, e.g. "brick work (General)" | — | — | inferred |
| S10 | Create task | S08 FAB | name, category, qty/unit, dates, assignee, work order link | — | — | inferred |
| S11 | Indent form | S12 "New Indent" | request materials | — | — | inferred |
| S12 | Indents | Material > Procurement > Indent | list of material requests | warehouse illustration, "You don't have any Indents", "+ New Indent" | empty | **observed** |
| S13 | Deliveries | Procurement > Deliveries | purchase orders and their upcoming deliveries | "You don't have any upcoming deliveries", "+ Create Purchase Order" | empty | **observed** |
| S14 | Site GRN | Procurement > Site GRN | goods received on site | illustration, "Get Started! Create your first GRN", "+ Create First GRN" | empty | **observed** |
| S15 | Material list (stock) | Material > Inventory > Material list | stock on site | search, filter; coach-mark "Switch between project and subcontractor inventories"; "In: Project Inventory ⇕"; row: name "cement", spec/brand "acc", "UOM: pk", "In stock: 5 pk", "+ Update"; FAB "+ Add Material" | filled | **observed** |
| S16 | Update stock / add material | S15 "Update" / "Add Material" | add or adjust stock | — | — | inferred |
| S17 | Transfer orders | Inventory > Transfer Order | stock sent to another project | wheelbarrow illustration, "No transfer Orders found", "+ New Transfer" | empty | **observed** |
| S18 | Transfer requests | Inventory > Transfer Requests | ask another project for stock | "No transfer Request found", "+ Request a transfer" | empty | **observed** |
| S19 | Material issue | Inventory > Material Issue | issue material to a task or subcontractor | "You haven't issued any material for tasks", "+ Issue Material" | empty | **observed** |
| S20 | Material returns | Inventory > Material Returns | material back into stock | "No Record found", "+ Create Material Return" | empty | **observed** |
| S21 | Labour attendance | Attendance > Labour Attendance | mark the day's attendance per worker | search; date stepper "‹ Mon, 12 Oct 2026 ›"; "Labour on site: 0" outline button; filter; FAB "+ Add Worker" | **error seen: raw backend message `"end_date" must be greater than ref:start_date` + "Tap to retry"** | **observed** |
| S22 | Add worker | S21 FAB | name, type/skill, wage, contractor | — | — | inferred |
| S23 | Payables | Attendance > Payables | wages due from attendance | "₹ Total Due amount ₹0 ›" bar; search; date range "12 Sep - 12 Oct 2026"; "Filters"; "No Entries"; FAB "+ Review & Record Payable" | empty | **observed** |
| S24 | Attendance settings | More > Labour Shift | attendance options | toggle **Work Updates**: "Add remarks on each attendance record"; **Labour hourly shift** "Record hourly attendance or shifts" = "8 hours" › ; toast "✓ Update Successful" | default, saved | **observed** |
| S25 | More menu | More tab | everything else | icon grid in 3 groups: **Financials**: Expenses. **Others**: Issues, Feed, Report, Gallery, Drive, Camera. **Settings**: Site Profile, Labour Shift, Members (1) | — | **observed** |
| S26 | Expenses | More > Expenses | where the project's money went | Filter; banner "Learn what's included in expenses ›"; donut "Total Expense ₹0"; 6 colour-keyed tiles: **Material, Subcontractor, Attendance, Petty Spend, Miscellaneous, Assets**; "Records" list | empty | **observed** |
| S27 | Feed | More > Feed | project activity stream | cards: actor avatar + "X raised an issue / created a task", relative time ("3 years ago"), body, action button ("Give Reply" filled, "Update Task Progress" outline), comment count; system lines ("Pritesh Jain joined as Manager", "Pritesh Jain created palak") | filled | **observed** |
| S28 | Profile | org home avatar | user profile | big initials avatar; rows: Name, Role "Organization Management Team", Email "Add email id +", Phone number ✓ verified; ⋮ menu | filled, no email | **observed** |
| S29 | Organisation settings | org header | org name and logo | logo upload circle with + badge, "Organisation Name*", "Save" | filled | **observed** |
| S30 | Gallery | More > Gallery | project photos | tabs **Media / Albums**; "No Photos. Photos clicked from camera will appear here"; FAB upload (Media), FAB new album (Albums) | empty | **observed** |
| S31 | Issues | More > Issues | problems on site to track and close | search, filter; tabs Open (1) / Closed / All (1); row: text, Due Date, Assignee, Status pill "Open"; FAB "+ Create Issue". **Seen: an issue auto-raised by "Powerplay Support" for an inactive project.** | filled | **observed** |
| S32 | Camera | More > Camera | in-app capture straight into the project | ✕, flash toggle, photo/video mode pill, gallery picker, shutter, flip camera | live | **observed** |
| S33 | Chat | header chat icon (org and project) | team messaging | — | — | inferred |
| S34 | Drive | More > Drive | project files and drawings | — | — | inferred |
| S35 | Site profile | More > Site Profile | project details (address, dates, image) | — | — | inferred |
| S36 | Members | More > Members (n) | invite people, set roles | roles seen: "Manager", "Organization Management Team" | — | inferred |
| S37 | Help | header "Help" | help and support | — | — | inferred |
| S38 | Owner web dashboard | web | all projects at once | — | — | inferred (src 7) |

### Report builder parts (S06, observed)

```
[x] Task Updates     task progress, attendance & remarks
[x] Photos           all photos
[x] Issues           all issues
[x] Inventory        used, added & in stock materials
[x] Material         used, added & in stock materials
      [x] Indent  [x] GRN  [x] Material Issue  [x] Transfer Order  [x] Purchase Order  [x] Consumptions
[x] Attendance       attendance and payable records
      [x] Attendance Record (locked on)  [x] Attendance Payable (payables overlapping the duration)
[ ] My Team          disabled: "Not yet set up on this project"
[x] Payables
      [x] Purchase Order  [x] GRN  [x] Attendance  [x] Work Order  [x] Custom
```

## Flows

```
F01 Site engineer logs the day
    S04 -> S05 -> S08 -> S09 (progress + photos via S32) -> S21 (attendance, remarks) -> S14/S19 (GRN / issue)
    happy path clicks: unknown until S09/S21 are seen with data; target ≤ 8
    edge: no network, photo upload fails, back-dated entry, attendance API error (seen in S21)

F02 Owner gets a report
    S04 -> S05 (Report tab = S06) -> pick type + duration -> Create Report -> S07 -> share
    happy path clicks: 3 with defaults (all parts ticked, Today)
    edge: a part not set up (My Team), long range, report expires after 15 days

F03 Material: indent to stock
    S12 New Indent -> (approve?) -> S13 Create Purchase Order -> delivery -> S14 GRN -> S15 stock up
    edge: partial delivery, GRN without PO ("Site GRN"), delete a wrong GRN (users ask for it, src 11)

F04 Material: move and use
    S15 -> S19 issue to task / subcontractor -> S20 return leftovers
    S18 request transfer -> other project's S17 transfer order -> stock moves
    edge: issue more than in stock, project vs subcontractor inventory (S15 switch)

F05 Attendance and payables
    S21 Add Worker (S22) -> mark day -> hourly/shift per S24 -> S23 Review & Record Payable -> total due
    edge: hourly vs 8-hour shift, remarks per record, payable periods overlapping the range

F06 Expenses
    S26 totals by Material / Subcontractor / Attendance / Petty Spend / Misc / Assets -> Records
    edge: totals that include POs, GRNs, payables and work orders. Which records count where? (banner explains)

F07 Issues
    S31 Create Issue (due date, assignee) -> Feed card -> reply -> close
    edge: auto-raised inactivity issue, issue linked to a task (S08 chip "Task with Open Issues")

F08 Feed and chat
    S27 cards with inline actions (Give Reply, Update Task Progress), comment counts; S33 chat

F09 Photos
    S32 camera (photo/video) -> S30 Media; albums to group them; upload from the phone

F10 Team and settings
    S36 invite member + role -> they see the project; S35 site profile; S24 attendance settings; S29 org; S28 profile
```

## Components (observed unless noted)

| component | variants | states | used on |
| --- | --- | --- | --- |
| Primary button | full-width ("Create Report", "Save"); pill FAB with icon + label ("+ Add Worker", "+ Add Material", "+ Create Issue", "+ Review & Record Payable"); square FAB "+"; round FAB icon (upload, new album) | default | most |
| Outline button | "Help", "Labour on site: n", "Update Task Progress", "Filters" | default | S05, S21, S27, S23 |
| Text link with + | "⊕ Create New", "+ Update", "Add email id +" | — | S04, S15, S28 |
| App header (org) | navy bar: org avatar initials, org name + plan badge, chat icon, user avatar | — | S04 |
| App header (project) | ✕, org name over project name with ⇕ switcher, chat icon, Help | — | S05 tabs |
| App header (sub-page) | ← back, small project name over the page title, optional right action (Filter) | — | S24, S26, S27, S31 |
| Bottom nav | 5 items, icon + label, filled icon when active | active/inactive | S05 |
| Segmented control | 2 options (Procurement / Inventory) | selected | Material |
| Tab strip | scrollable, underline indicator | selected | Material, Attendance, Gallery, Issues (with counts) |
| Search field | with leading icon; placeholder varies ("Search by id, name") | empty | S04, S08, S15, S21, S23, S31 |
| Filter button | icon-only square; "Filter"/"Filters" labelled | — | S08, S15, S21, S23, S26, S31 |
| Filter chips | horizontal scroll, outline | on/off | S08 |
| Date stepper | ‹ date › | — | S21 |
| Date field | label with red *, date + calendar icon | — | S06 |
| Date range button | calendar icon + "12 Sep - 12 Oct 2026" | — | S23 |
| Radio group | horizontal, scrolls off-screen | — | S06 |
| Selectable card | title + description, optional "NEW" badge | selected (tinted + blue border) | S06 |
| Checkbox tree | parent with description, indented children with guide line | checked, disabled-checked, disabled-unchecked with reason | S06 |
| Toggle | — | on | S24 |
| Value pill | "8 hours" | — | S24 |
| Status pill | Active / Open (amber tint) | — | S04, S31 |
| Progress ring | small ring + % | 0% | S04 |
| Project card | image, name, status, progress, date range, ⋮ | — | S04 |
| Stock row | name, spec, UOM, in-stock, "+ Update" | — | S15 |
| Stat tile | coloured left border, label + ₹ amount | — | S26 |
| Donut total | ring with label + amount | empty grey | S26 |
| Feed card | avatar, actor + verb, time, body, action button, comment count | — | S27 |
| Feed system line | centred grey text | — | S27 |
| Info banner | light blue, info icon, text, optional › or ✕ | dismissible | S07, S15, S26 |
| Coach-mark | banner with pointer and ✕ | — | S15 |
| Empty state | illustration (warehouse, wheelbarrow, worker, folder, magnifier) + line + primary button | — | most lists |
| Error state | ! icon, message, "Tap to retry" | — | S21 |
| Toast | green, ✓ "Update Successful" | success | S24 |
| Avatar | initials on tinted square/circle (navy, peach) | — | S04, S27, S28 |
| Camera | shutter, flip, flash, gallery, photo/video | — | S32 |

Make your own illustrations and icons. Never reuse theirs (see Out of scope).

## Inferred data model

```
Organisation  id, name, logo_url, plan (free | pro | pro_plus | advanced)
              evidence: S04 "(Free)", S29; src 9
              confidence: high
User          id, name, phone (verified), email?
              evidence: S28
              confidence: high
Membership    org_id | project_id, user_id, role
              evidence: S28 "Organization Management Team"; S27 "joined as Manager"; S25 Members (1)
              confidence: high for org-level vs project-level roles. The full role list is unknown.
Project       id, org_id, name, image_url, status (active | ...), start_date?, end_date?, progress_pct
              evidence: S04 card (dates optional: "Start Date - End Date" placeholder)
              confidence: high
ProjectSettings project_id, attendance_remarks (bool), shift_hours (int, default 8), hourly_mode
              evidence: S24
              confidence: high
Task          id (searchable), project_id, name, category ("General"), status (in_progress | delayed | ...),
              work_order_id?, progress, dates
              evidence: S08 search "by id, name", chips; S27 "brick work (General)"
              confidence: medium
TaskUpdate    task_id, user_id, date, progress, remarks, photo_ids
              evidence: S06 "Task Updates: task progress, attendance & remarks"; S27 "Update Task Progress"
              confidence: medium
Issue         id, project_id, text, due_date?, assignee_id?, status (open | closed), task_id?, raised_by
              evidence: S31, S08 chip "Task with Open Issues", S27
              confidence: high
Comment       parent (issue | task | feed item), user_id, text
              evidence: S27 comment counts, "Give Reply"
              confidence: high
ActivityEvent project_id, actor_id, verb, object, created_at
              evidence: S27
              confidence: high
Media         id, project_id, album_id?, type (photo | video), url, taken_at
Album         id, project_id, name
              evidence: S30, S32 video mode
              confidence: high
DriveFile     id, project_id, folder, name, url
              evidence: S25 Drive
              confidence: medium
Material      id, org_id, name, spec/brand ("acc"), uom ("pk")
Inventory     project_id, owner (project | subcontractor_id), material_id, in_stock
              evidence: S15 incl. "project and subcontractor inventories"
              confidence: high
Indent        id, project_id, lines[material_id, qty], status
PurchaseOrder id, project_id, vendor_id, lines[...], expected delivery, status
GRN           id, project_id, po_id?, lines[...]
TransferOrder id, from_project_id, to_project_id, lines[...]
TransferRequest id, requesting_project_id, source_project_id, lines[...], status
MaterialIssue id, project_id, task_id | subcontractor_id, lines[...]
MaterialReturn id, project_id, from (task | subcontractor), lines[...]
Consumption   project_id, material_id, qty, date
              evidence: S12–S20, S06 Material parts
              confidence: high for the entities, guess for the fields
Vendor        id, org_id, name, phone, gstin
              evidence: POs need a vendor
              confidence: guess
Worker        id, project_id, name, type/skill, wage (daily | hourly), contractor?
AttendanceRecord worker_id, date, status | hours, remarks?
Payable       id, project_id, kind (attendance | purchase_order | grn | work_order | custom),
              party, period_start, period_end, amount, status
              evidence: S21, S23, S24, S06 Payables parts
              confidence: high for the entities. The payable kinds come straight from S06.
WorkOrder     id, project_id, subcontractor_id, lines, amount
              evidence: S06 Payables > Work Order, S08 chip "Task with Work order"
              confidence: medium
Expense       derived + manual: category (material | subcontractor | attendance | petty_spend | misc | assets), amount, date, record_ref
              evidence: S26
              confidence: high for categories; medium for which are derived vs entered
Report        id, project_id, type (detailed | summary), from, to, parts[], file_url, expires_at (+15 days)
              evidence: S06, S07
              confidence: high
ChatThread / Message  org- and project-level
              evidence: chat icon in S04 and S05
              confidence: medium
```

Relationships: Organisation 1-n Project. Organisation and Project 1-n Membership n-1 User. Project 1-n Task 1-n TaskUpdate.
Task 0-1 WorkOrder. Task 1-n Issue. Project 1-n Inventory per owner (project or subcontractor).
Project 1-n Indent → PurchaseOrder → GRN. Project 1-n Worker 1-n AttendanceRecord → Payable.
Project 1-n Media n-0..1 Album. Project 1-n Report. Expenses roll up from GRN/PO, payables, work orders and petty spend.

## Feature matrix

See `features.csv`. Must: 33, should: 28, could: 14, skip: 4.

## What the screenshots show that we can beat

- **Raw backend errors reach users.** S21 shows `"end_date" must be greater than ref:start_date` on an
  empty attendance screen. Validate dates and show plain-language messages.
- **Reports disappear after 15 days** (S07). Keep them longer.
- **No delete for wrong attendance or GRN entries** (src 11). Allow corrections with an audit trail.
- **Empty days generate "inactivity" issues from vendor support** (S31, S27). Ship a gentler reminder instead.

## Out of scope (cannot or should not be cloned)

- Powerplay's name, logo, navy/blue palette as a brand, **illustrations** (warehouse, wheelbarrow, worker,
  folder), icons, and copy ("Get organised by creating and updating your 1st task!" etc.). Write your own text.
- The screenshots themselves. They are reference only and never ship.
- Their customers, reviews, case studies and translations.
- "Powerplay Support" actions inside customer projects. That is their support team, not a feature.
- Partner integrations or deals (none confirmed).

## Size

Screens 38 (26 observed), flows 10, entities ~30.

Hard parts:
1. **Material ledger.** Indent, PO, delivery, GRN, issue, return, transfer, consumption, per project
   *and* per subcontractor inventory, all reconciling to stock.
2. **Payables and expenses.** Attendance (daily or hourly shifts), POs, GRNs, work orders and custom
   payables, rolled into six expense categories without double counting.
3. **Report builder.** Any date range, any combination of 15+ parts, detailed vs summary, rendered to a shareable file.
4. **Offline-first capture** (camera photo/video, attendance, GRN) with sync.
5. **Roles at org and project level**, plus chat and feed.

Size as asked ("everything"): **XL. Rescope it.** Phases, each sellable:

- **Phase 1 (M–L):** S01–S05, S08–S10, S21–S22, S24, S15–S16, S14, S19, S30, S32, S27, S06–S07 with Task, Photos,
  Attendance and Inventory parts. That is projects, tasks with photo updates, attendance, simple stock,
  feed and a daily report.
- **Phase 2 (M):** indent → PO/deliveries → GRN, transfers and returns, payables, expenses, issues, members and roles.
- **Phase 3 (M–L):** work orders and subcontractor inventory, summary report, chat, drive, albums, web dashboard, more languages.

No promise of a perfect clone. Take the "still unseen" screenshots before `/replica-design`.

# Test plan: site app

Build: `claude/replica-skill-install-updaro`. Date: 2026-10-14. Env: demo data layer (seed), Robolectric phone
448×997dp at 3×, fixed clock Tue 13 Oct 2026 09:30 IST. Server and sync cases run on Postgres 16 and the in-memory server.

Where each case runs:

| tag | what | where |
| --- | --- | --- |
| **ui** | Compose UI test driving the real app by labels and accessibility names | `site-app/app/src/test/java/app/site/flows/` |
| **core** | JVM test of data layer, sync, rules | `site-app/core/src/test/` |
| **db** | SQL test on Postgres 16 | `supabase/tests/` |
| **fn** | Deno test of Edge Function logic | `supabase/functions/_shared/logic_test.ts` |
| **manual** | needs a real phone, a real Supabase project or a person's eyes | checklist at the end |

Every ui case also runs the **accessibility audit** on the screen it ends on (every tappable thing named and at least 48dp)
and fails on any exception. Never tested: the original app's servers.

## Results (2026-10-14)

**Second round** (screens built since: material, photos, team, company, issues, money, camera): 36 cases, 32
covered by UI tests, the rest by core and db tests. First run (Site app run 25, 2d140a1): 89 app tests, 2 failed, both
real bugs: BUG-009 (S2) and BUG-010 (S3). Both fixed with the failing test kept.

**First round:**

50 cases, 50 run: 49 pass, 1 partly (F02-H1: the PDF step is skipped under Robolectric and moved to the manual
checklist). 7 bugs found, 7 fixed: 1 S2, 6 S3. Automated: 46 UI tests (Site app CI, green at 58ad75d),
31 core tests, the database tests and 9 Deno tests (Backend CI). The manual checklist below is still open: it needs a
phone and your Supabase project.

## F10 Sign in, team and settings

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F10-H1 | happy | phone → Send code → 123456 → Verify → name → Continue | projects list with Green Park Tower B | ui `f10_H1` || pass after BUG-004, 005 |
| F10-H2 | happy | Profile → rename → Save → Sign out | name saved; back to sign in | ui `f10_H2` || pass |
| F10-H3 | happy | More → Attendance settings → longer shift | 8 h → 8.5 h | ui `f10_H3` || pass |
| F10-H4 | happy | New project → name → Create | lands inside the new project | ui `f10_H4` || pass after BUG-004 |
| F10-E1 | edge: short number | 9 digits | Send code disabled | ui `f10_E1` || pass |
| F10-E2 | edge: number formats | "98765 43210", "+91 98765-43210", "09876543210" | all become +919876543210 | core `ValidateTest.phone`, fn `phone numbers normalise` || pass |
| F10-E3 | edge: expired session | access token expired / refresh fails | refreshed silently / signed out and phone wiped | core `SupabaseHttpTest` ×3 || pass |
| F10-E4 | edge: new user without company | first sign-in | asked for name and company; company created, user is owner | core `signInCreatesOrg...` || pass |
| F10-N1 | negative: wrong code | 000000 | "That code didn't match…", still on code step | ui `f10_N1`, core || pass |
| F10-N2 | negative: empty project name | blank / spaces | Create disabled | ui `f10_N2` || pass after BUG-004 |
| F10-N3 | negative: plan limit | 4th active site on free plan | refused with plan message (server and demo) | db `server_rules`, core `demoEnforces…`, core `refusedChanges…` || pass after BUG-001 |
| F10-N4 | negative: second user's data | outsider queries | sees no projects, tasks, people, files | db `smoke`, `server_rules` || pass |
| F10-N5 | negative: delete account as sole owner with members | delete | refused with explanation; nothing deleted | db `server_rules` || pass |
| F10-N6 | negative: SMS flood | 6th code in an hour | refused | db `rate_limit`, fn || pass |

## F01 Site engineer logs the day

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F01-H1 | happy | Tasks → #2 Brickwork → 25 → Save update | "Update saved"; 350 of 600 m2; 58% | ui `f01_H1` || pass |
| F01-E1 | edge: percent over 100 | 150 on a percent task | error, Save disabled | ui `f01_E1` || pass |
| F01-E2 | edge: emoji and Indian scripts | remarks "Café ठीक है ✅" | saved and shown as typed | ui `f01_E2` || pass |
| F01-E3 | edge: offline | log work with no network | saved on phone, uploads later, number assigned by server | core `worksOfflineThenSyncs` || pass |
| F01-E4 | edge: two phones, same task | both log quantity | quantities add up on both phones | core `twoPhonesShareASite`, db `server_rules` || pass |
| F01-E5 | edge: server refuses | RLS refuses the update | change undone on phone, counted in the banner | core `refusedChanges…` || pass |
| F01-N1 | negative: empty update | nothing entered | Save disabled | ui `f01_N1` || pass |

## F02 Owner gets a report

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F02-H1 | happy | Report tab → Create report | reports list, PDF made, Open available | ui `f02_H1` || pass up to the reports list; PDF skipped under Robolectric → manual |
| F02-H2 | happy | summary with wages | totals and wages correct | core `reportContentFromSeed` || pass |
| F02-E1 | edge: nothing included | untick Work done and Labour | Create disabled | ui `f02_E1` || pass after BUG-003 |
| F02-E2 | edge: custom period | Pick dates | From and To fields | ui `f02_E2` || pass |
| F02-E3 | edge: report made on another phone | open on phone B | PDF downloads and opens | core `reportsUploadAndDownload…` || pass |
| F02-E4 | edge: end before start | to < from | refused with plain message | core `reportsQueue…` || pass |

## F04 Attendance and wages

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F04-H1 | happy | tap P for Suresh | On site 1 of 5 | ui `f04_H1` || pass after BUG-004, 005 |
| F04-H2 | happy | A for Imran → Mark rest present | On site 4 of 5; button then disabled | ui `f04_H2` || pass |
| F04-H3 | happy | Wages tab | last 7 days per worker, not zero | ui `f04_H3`, core `WagesTest` || pass |
| F04-H4 | happy | Add worker → name, ₹650 | appears in register | ui `f04_H4` || pass after BUG-004 |
| F04-E1 | edge: future day | next day on today | disabled; previous day works | ui `f04_E1`, core || pass |
| F04-E2 | edge: overtime + note | sheet → 2 h, note | row shows OT 2 h and the note | ui `f04_E2` || pass |
| F04-E3 | edge: two phones, same worker same day | both mark | one record, later wins, ids converge, clear propagates | core `twoPhonesShareASite`, db `smoke` || pass |
| F04-E4 | edge: pending local mark vs newer server row | pull while upload pending | local mark kept, uploads later | core `aPendingLocalChange…` (mutation-tested) || pass |
| F04-E5 | edge: wages maths | 3.5 days × ₹800 + 2 h × ₹100 | ₹3,000 | core `WagesTest`, db `smoke` || pass |
| F04-N1 | negative: overtime 20 h | sheet | error, Save disabled | ui `f04_E3` || pass |
| F04-N2 | negative: wage "six fifty" | add worker | error, disabled | ui `f04_N1` || pass after BUG-004 |

## F07 Plan and track tasks

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F07-H1 | happy | Add task with 100 m3 | opens #5 with 0 of 100 m3 | ui `f07_H1` || pass after BUG-004 |
| F07-E1 | edge: search | "#2", then "zzz", Clear filters | only #2; "No tasks match"; all back | ui `f07_E1` || pass after BUG-002 |
| F07-E2 | edge: Delayed filter | chip | only overdue tasks | ui `f07_E2` || pass after BUG-004, 005 |
| F07-E3 | edge: long name | 58 characters | saved and listed | ui `f07_E3` || pass after BUG-004 |
| F07-E4 | edge: back button | task → back | returns to Tasks tab, not Report | ui `f07_E4` || pass |
| F07-E5 | edge: server progress rule | qty and % tasks, deleted update | same numbers on phone and server | core `taskStatusFollows…`, db `server_rules` || pass |
| F07-N1 | negative: no name, negative quantity | | Add disabled; error shown | ui `f07_N1` || pass after BUG-004 |

## F08 Feed

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F08-H1 | happy | More → Activity → Open task | feed lists progress; task opens | ui `f08_H1` || pass after BUG-005 |
| F08-E1 | edge: events in the future | seed events | none dated after now | core `seedFeedIsNeverInTheFuture` || pass |
| F08-N1 | negative: forged feed event | client inserts into activity_events | refused | db `smoke` || pass |

## Second round (2026-10-14): the screens built since

Case ids in this round follow the test names (M material, G photos, T team, C company, I issues, P payables and
expenses, CAM camera); the heading gives the recon flow. The first round's F04 (attendance) and F07 (tasks) predate the
recon numbering: they are recon F05 and part of F01.

### F03 / F04 Material: delivery to stock, issue to a task

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| M-H1 | happy | Material tab → TMT bar | stock per material; history shows delivery #2 | ui `m_H1` | pass |
| M-H2 | happy | Add material → Binding wire, kg, 25 | "25 kg on site" | ui `m_H2` | pass |
| M-H3 | happy | Buying → Received → Record delivery → Cement 50, challan | delivery #3; cement 135 → 185 | ui `m_H3` | pass |
| M-H4 | happy | Issued → Issue material → task #2, cement 35 | issue #3 for #2; cement 135 → 100 | ui `m_H4` | pass |
| M-E1 | edge: issue more than in stock | 500 bags | "more than the site holds", disabled | ui `m_E1`, core `Stock` | pass |
| M-E2 | edge: physical count | Update → Counted 130 | 130 on site; history "Count corrected −5" | ui `m_E2` | pass |
| M-E3 | edge: add something already on site | Add material → pick Cement · OPC 53 → 140 | 140 on site (a count), not 275 | ui `m_E3` | failed (BUG-009); pass after fix |
| M-E4 | edge: Hindi and emoji | "सरिया 8mm ✅", kg, 40 | listed as typed | ui `m_E4` | pass |
| M-E5 | edge: offline, two phones | add material and deliveries offline on two phones | same material id, stock adds up | core `materialMovesAcrossPhones` | pass |
| M-E6 | edge: delivery dated tomorrow | receive with a future date | refused (IN_FUTURE) | core `MaterialTest.refusals` | pass |
| M-N1 | negative: empty delivery | no material, then qty 0 | Save disabled, "above zero" | ui `m_N1` | pass |

### F05 / F06 Payables and expenses

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| P-H1 | happy | Attendance → Wages → Record as payable → Pay | 5 payables; period then covered; one paid | ui `p_H1`, core `MoneyTest` | pass |
| P-H2 | happy | More → Expenses → Add spend ₹750 → pay crane | total ₹19,250 → ₹20,000; crane paid | ui `p_H2` | pass |
| P-E1 | edge: record twice | Record as payable again | nothing doubles; button off | ui `p_H1`, core | pass |
| P-N1 | negative: overpay | ₹9,000 on ₹8,000 due | "more than what's still due" | ui `p_N1`, core | pass |
| P-N2 | negative: permission | plain member opens Wages, Report | money hidden; no Wages or Payments part in the report | ui `p_N2` | failed (BUG-010); pass after fix |
| P-N3 | negative: zero spend | amount 0 | Save disabled | ui `p_N3` | pass |
| P-N4 | negative: second user's money | outsider / supervisor reads payables | nothing (RLS `can_see_money`) | db `smoke` ("B cannot see money"), core `moneyIsHiddenFromSiteStaff` | pass |

### F07 Issues

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| I-H1 | happy | More → Issues; Closed; All | counts and lists per status | ui `i_H1` | pass |
| I-H2 | happy | Raise issue on task #2 for Ravi → comment → Mark fixed | issue #3, comment shown, Reopen offered | ui `i_H2` | pass |
| I-E1 | edge: reopen | Closed → #2 → Reopen → back | Open (2) | ui `i_E1` | pass |
| I-E2 | edge: Hindi + emoji comment, empty comment | | Send off when empty; text shown as typed | ui `i_E2` | pass |
| I-E3 | edge: offline raise | raise and comment with no network, then sync | saved on the phone; numbered by the server | core `issuesAndCommentsSync` | pass |
| I-N1 | negative: no title | | Raise disabled | ui `i_N1` | pass |

### F09 Photos

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| G-H1 | happy | More → Photos → Albums → New album | album opens, empty | ui `g_H1` | pass |
| G-H2 | happy | photo → viewer → Move to album | album shows 1 photo | ui `g_H2` | pass |
| G-N1 | negative: blank album name | spaces only | Create disabled | ui `g_N1` | pass |
| CAM-N1 | negative: no camera permission | Take photo | asks, offers phone camera app, closes | ui `cam_N1` | pass |

### F10 Team and company

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| T-H1 | happy | More → Team | owner, engineer, supervisor with roles | ui `t_H1` | pass |
| T-H2 | happy | Invite 98111 22222 as storekeeper | pending invite listed | ui `t_H2` | pass |
| T-H3 | happy | Ravi → Manager → Remove | role changes; removed | ui `t_H3` | pass |
| T-N1 | negative: already a member | invite Ravi's number | "already on this site" | ui `t_N1` | pass |
| T-N2 | negative: not a mobile number | 12345 67890 | phone error | ui `t_N2` | pass |
| T-N3 | negative: permission | plain member opens Team | explained; no Invite | ui `t_N3` | pass |
| C-H1 | happy + negative | email "anita at example", then a good one | refused, then saved lower-case | ui `c_H1` | pass |
| C-H2 | happy | rename the company | saved | ui `c_H2` | pass |

## Layout edges

| case | steps | expected | auto | result |
| --- | --- | --- | --- | --- |
| L-1 | 360dp-wide phone, attendance | rows and toggles fit | ui `narrowPhone` + screenshot || pass; screenshot showed BUG-006 |
| L-2 | 200% font, projects and tasks | text wraps, nothing overlaps | ui `largeText` + screenshot (manual look) || pass; screenshots showed BUG-007 |

## Manual checklist (real phone + your Supabase project)

- [ ] SMS code arrives through MSG91 (DLT template approved) and signs in
- [ ] Camera: Take photo → photo on the update → visible on a second phone after sync
- [ ] In-app camera (S32): several shots in a row, flash, front lens; Done hands them to the update
- [ ] Team invite on a real server: the invited phone signs in and sees only that site
- [ ] Airplane mode: log work and attendance, reconnect, both reach the second phone
- [ ] Report PDF opens in a PDF viewer and shares to WhatsApp with Hindi text intact
- [ ] Razorpay test checkout (test UPI / card), webhook flips plan to Pro; cancel → back to Free at period end
- [ ] Push: daily nudge arrives at 18:30 IST (after Firebase is set up)
- [ ] TalkBack: sign in and log a day's work with the screen reader only
- [ ] Delete account from Profile on a real account; login no longer works

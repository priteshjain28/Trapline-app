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

## F10 Sign in, team and settings

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F10-H1 | happy | phone → Send code → 123456 → Verify → name → Continue | projects list with Green Park Tower B | ui `f10_H1` | |
| F10-H2 | happy | Profile → rename → Save → Sign out | name saved; back to sign in | ui `f10_H2` | |
| F10-H3 | happy | More → Attendance settings → longer shift | 8 h → 8.5 h | ui `f10_H3` | |
| F10-H4 | happy | New project → name → Create | lands inside the new project | ui `f10_H4` | |
| F10-E1 | edge: short number | 9 digits | Send code disabled | ui `f10_E1` | |
| F10-E2 | edge: number formats | "98765 43210", "+91 98765-43210", "09876543210" | all become +919876543210 | core `ValidateTest.phone`, fn `phone numbers normalise` | |
| F10-E3 | edge: expired session | access token expired / refresh fails | refreshed silently / signed out and phone wiped | core `SupabaseHttpTest` ×3 | |
| F10-E4 | edge: new user without company | first sign-in | asked for name and company; company created, user is owner | core `signInCreatesOrg...` | |
| F10-N1 | negative: wrong code | 000000 | "That code didn't match…", still on code step | ui `f10_N1`, core | |
| F10-N2 | negative: empty project name | blank / spaces | Create disabled | ui `f10_N2` | |
| F10-N3 | negative: plan limit | 4th active site on free plan | refused with plan message (server and demo) | db `server_rules`, core `demoEnforces…`, core `refusedChanges…` | |
| F10-N4 | negative: second user's data | outsider queries | sees no projects, tasks, people, files | db `smoke`, `server_rules` | |
| F10-N5 | negative: delete account as sole owner with members | delete | refused with explanation; nothing deleted | db `server_rules` | |
| F10-N6 | negative: SMS flood | 6th code in an hour | refused | db `rate_limit`, fn | |

## F01 Site engineer logs the day

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F01-H1 | happy | Tasks → #2 Brickwork → 25 → Save update | "Update saved"; 350 of 600 m2; 58% | ui `f01_H1` | |
| F01-E1 | edge: percent over 100 | 150 on a percent task | error, Save disabled | ui `f01_E1` | |
| F01-E2 | edge: emoji and Indian scripts | remarks "Café ठीक है ✅" | saved and shown as typed | ui `f01_E2` | |
| F01-E3 | edge: offline | log work with no network | saved on phone, uploads later, number assigned by server | core `worksOfflineThenSyncs` | |
| F01-E4 | edge: two phones, same task | both log quantity | quantities add up on both phones | core `twoPhonesShareASite`, db `server_rules` | |
| F01-E5 | edge: server refuses | RLS refuses the update | change undone on phone, counted in the banner | core `refusedChanges…` | |
| F01-N1 | negative: empty update | nothing entered | Save disabled | ui `f01_N1` | |

## F02 Owner gets a report

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F02-H1 | happy | Report tab → Create report | reports list, PDF made, Open available | ui `f02_H1` | |
| F02-H2 | happy | summary with wages | totals and wages correct | core `reportContentFromSeed` | |
| F02-E1 | edge: nothing included | untick Work done and Labour | Create disabled | ui `f02_E1` | |
| F02-E2 | edge: custom period | Pick dates | From and To fields | ui `f02_E2` | |
| F02-E3 | edge: report made on another phone | open on phone B | PDF downloads and opens | core `reportsUploadAndDownload…` | |
| F02-E4 | edge: end before start | to < from | refused with plain message | core `reportsQueue…` | |

## F04 Attendance and wages

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F04-H1 | happy | tap P for Suresh | On site 1 of 5 | ui `f04_H1` | |
| F04-H2 | happy | A for Imran → Mark rest present | On site 4 of 5; button then disabled | ui `f04_H2` | |
| F04-H3 | happy | Wages tab | last 7 days per worker, not zero | ui `f04_H3`, core `WagesTest` | |
| F04-H4 | happy | Add worker → name, ₹650 | appears in register | ui `f04_H4` | |
| F04-E1 | edge: future day | next day on today | disabled; previous day works | ui `f04_E1`, core | |
| F04-E2 | edge: overtime + note | sheet → 2 h, note | row shows OT 2 h and the note | ui `f04_E2` | |
| F04-E3 | edge: two phones, same worker same day | both mark | one record, later wins, ids converge, clear propagates | core `twoPhonesShareASite`, db `smoke` | |
| F04-E4 | edge: pending local mark vs newer server row | pull while upload pending | local mark kept, uploads later | core `aPendingLocalChange…` (mutation-tested) | |
| F04-E5 | edge: wages maths | 3.5 days × ₹800 + 2 h × ₹100 | ₹3,000 | core `WagesTest`, db `smoke` | |
| F04-N1 | negative: overtime 20 h | sheet | error, Save disabled | ui `f04_E3` | |
| F04-N2 | negative: wage "six fifty" | add worker | error, disabled | ui `f04_N1` | |

## F07 Plan and track tasks

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F07-H1 | happy | Add task with 100 m3 | opens #5 with 0 of 100 m3 | ui `f07_H1` | |
| F07-E1 | edge: search | "#2", then "zzz", Clear filters | only #2; "No tasks match"; all back | ui `f07_E1` | |
| F07-E2 | edge: Delayed filter | chip | only overdue tasks | ui `f07_E2` | |
| F07-E3 | edge: long name | 58 characters | saved and listed | ui `f07_E3` | |
| F07-E4 | edge: back button | task → back | returns to Tasks tab, not Report | ui `f07_E4` | |
| F07-E5 | edge: server progress rule | qty and % tasks, deleted update | same numbers on phone and server | core `taskStatusFollows…`, db `server_rules` | |
| F07-N1 | negative: no name, negative quantity | | Add disabled; error shown | ui `f07_N1` | |

## F08 Feed

| case | type | steps | expected | auto | result |
| --- | --- | --- | --- | --- | --- |
| F08-H1 | happy | More → Activity → Open task | feed lists progress; task opens | ui `f08_H1` | |
| F08-E1 | edge: events in the future | seed events | none dated after now | core `seedFeedIsNeverInTheFuture` | |
| F08-N1 | negative: forged feed event | client inserts into activity_events | refused | db `smoke` | |

## F03, F05, F06, F09 (partly built)

F03 material, F05 payables recording, F06 expenses and F09 gallery/albums have server tables and access rules (tested in
db `smoke`: stock ledger, transfers, payables, expenses) but **no screens yet**. Their UI cases are written when the
screens are built.

## Layout edges

| case | steps | expected | auto | result |
| --- | --- | --- | --- | --- |
| L-1 | 360dp-wide phone, attendance | rows and toggles fit | ui `narrowPhone` + screenshot | |
| L-2 | 200% font, projects and tasks | text wraps, nothing overlaps | ui `largeText` + screenshot (manual look) | |

## Manual checklist (real phone + your Supabase project)

- [ ] SMS code arrives through MSG91 (DLT template approved) and signs in
- [ ] Camera: Take photo → photo on the update → visible on a second phone after sync
- [ ] Airplane mode: log work and attendance, reconnect, both reach the second phone
- [ ] Report PDF opens in a PDF viewer and shares to WhatsApp with Hindi text intact
- [ ] Razorpay test checkout (test UPI / card), webhook flips plan to Pro; cancel → back to Free at period end
- [ ] Push: daily nudge arrives at 18:30 IST (after Firebase is set up)
- [ ] TalkBack: sign in and log a day's work with the screen reader only
- [ ] Delete account from Profile on a real account; login no longer works

# Bugs: site app

Found by `/replica-test` on 2026-10-14. Device for ui cases: Robolectric phone 448×997dp at 3× (Android 14), demo
data layer, fixed clock Tue 13 Oct 2026 09:30 IST. Only reproduced bugs are listed; suspicions are under "To check".

| | open | fixed |
| --- | --- | --- |
| S1 | 0 | 0 |
| S2 | 0 | 1 |
| S3 | 0 | 4 |
| S4 | 0 | 0 |

### BUG-001: demo lets a free company add a 4th active site; the server refuses it

- Severity: S3
- Flow / case: F10 / F10-N3
- Screen: S03
- Build: dce7134  Device: JVM (core test)

Steps
1. Demo company on the Free plan with 3 active projects
2. New project → name → Create project

Expected: refused with the plan message, as the server does (`app.enforce_plan_limits`)
Actual: the 4th project is created; the same action fails once the app runs on Supabase
Evidence: `CoreTest.demoEnforcesTheFreePlanLikeTheServer` (failed before the fix)
Suspected cause: FakeBackend had no plan limit
Status: fixed in 492bf1b (demo enforces 3 active sites on Free; demo company is on Pro so the demo can still add sites)

### BUG-002: searching "#2" also lists tasks with "2" in their name

- Severity: S3
- Flow / case: F07 / F07-E1
- Screen: S08
- Build: 92301f6  Device: Robolectric phone

Steps
1. Green Park Tower B → Tasks
2. Search "#2"

Expected: only #2 Brickwork level 3
Actual: #3 Plaster level 2 is listed too
Evidence: `TaskFlows.f07_E1_searchByNumberAndNoMatch` (assertFalse at FlowTests.kt:120)
Suspected cause: the leading # was stripped, then the rest matched names as well as numbers
Status: fixed in 9a29a8f ("#n" matches the number only; plain text still matches names and numbers)

### BUG-003: Create report stays enabled with nothing to include

- Severity: S3
- Flow / case: F02 / F02-E1
- Screen: S06
- Build: 92301f6  Device: Robolectric phone

Steps
1. Green Park Tower B (opens on Report)
2. Untick Work done, untick Labour

Expected: Create report disabled
Actual: enabled; tapping it makes an empty report
Evidence: `ReportAndFeedFlows.f02_E1_nothingToIncludeCantCreate` (FlowTests.kt:299)
Suspected cause: Photos is greyed out when Work done is off but still counted as ticked
Status: fixed in 9a29a8f

### BUG-004: New project, Add task and Add worker buttons have no name for screen readers

- Severity: S2 (TalkBack users can't create a project, a task or a worker; no workaround for them)
- Flow / case: F10 / F10-H4, F07 / F07-H1, F04 / F04-H4
- Screen: S04, S08, S21
- Build: 92301f6  Device: Robolectric phone

Steps
1. Turn on TalkBack (tests: query the accessibility tree)
2. Projects list → focus the floating "New project" button

Expected: announced as "New project, button"
Actual: unnamed button; the audit reports "unnamed tappable element" at the floating button on S04, S08 and S21,
and tests can't find a button called "Add task" / "New project" / "Add worker"
Evidence: `f10_H1`, `f10_H4`, `f10_N2`, `f07_H1`, `f07_E2`, `f07_E3`, `f07_N1`, `f04_H1`, `f04_H4`, `f04_N1`
(CI run 38588217249)
Suspected cause: the extended floating button's label isn't merged into its semantics
Status: fixed in 08f19be (the button carries its label as its name)

### BUG-005: touch targets under 48dp

- Severity: S3 (hard to hit with a gloved or dusty hand on site; a second try works)
- Flow / case: all flows, accessibility audit
- Screen: S04, S05 (all project tabs), S21, S27
- Build: 92301f6  Device: Robolectric phone

Steps
1. Open each screen; measure every tappable element

Expected: at least 48×48dp
Actual: profile avatar 32×32, Help 58×36, Open task (feed) 36 tall, Register / Wages sub-tabs 43 tall,
attendance date 320×19, project switcher 274×42
Evidence: audit output in `f10_H1`, `f04_H1`, `f02_H1`, `f07_E2`, `f08_H1` (CI run 38588217249)
Status: fixed in 08f19be (same look, 48dp tappable area)

## To check (not reproduced)

- Photo remove button on a pending update is 32dp (TaskDetailScreen `PhotoStrip`). Needs a test that picks a photo.
- Profile shows the role "Owner" for every user; members who aren't owners would see the wrong role. Needs a
  member account (real backend).
- Dates use the phone's time zone, not the site's. A site engineer travelling outside India would log against the
  wrong day near midnight.
- `f02_H1` renders the PDF with Android's PdfDocument under Robolectric; confirm on a real phone (manual checklist).

# Parity: site app vs Powerplay

Date: 2026-10-14. Build: `claude/replica-skill-install-updaro` at 899ef27. Tools: `.claude/skills/replica-diff/parity.py`,
`imgdiff.py`, and `replica/diff_screens.py` (crops the reference phone's status and gesture bars, then diffs).

## Scores

| | score | |
| --- | --- | --- |
| **Overall** | **45.8 / 100** | features and layout combined by `parity.py` |
| Features | 50.9 | 75 features counted; **must-haves 20 of 33** |
| Layout | 25.5 | 10 screens, structure only (colour ignored) |

**Verdict: not shippable.** 13 must-haves are missing. Bugs aren't blocking it: none are open at any severity
(`replica/bugs.md`).

## Features by area, weakest first

| area | score | features |
| --- | --- | --- |
| material | 0.0 | 13 |
| money | 0.0 | 4 |
| issues | 0.0 | 2 |
| subcontractors | 0.0 | 2 |
| documents | 0.0 | 1 |
| chat | 0.0 | 1 |
| photos | 31.8 | 5 |
| feed | 50.0 | 3 |
| team | 50.0 | 1 |
| billing | 50.0 | 1 |
| platform | 62.5 | 8 |
| onboarding | 66.7 | 3 |
| projects | 75.0 | 5 |
| labour | 77.3 | 9 |
| tasks | 80.6 | 8 |
| reports | 88.6 | 9 |

What's built is close: reports, tasks and labour score 77–89. What isn't built is the gap: material is 13
features at zero, 5 of them must-haves.

## Must-haves not done (13), in build order

1. Material: add material and update stock (S15)
2. Material: catalogue with spec and unit (S15)
3. Material: issue to a task (S19)
4. Material: site GRN, material received (S14)
5. Material: site stock list with in-stock quantity (S15)
6. Photos: gallery of project photos (S30)
7. Onboarding: organisation edit with logo (S29), partial
8. Onboarding: profile email (S28), partial
9. Photos: in-app camera (S32), partial (the phone's camera app works today)
10. Projects: project image on the card (S04), partial
11. Reports: material, issues and payables parts (S06), partial: they wait for those screens
12. Tasks: managed category list, partial (free text today)
13. Team: members screen with invite and roles (S36), partial: the server side is built

The should/could list is in `python3 .claude/skills/replica-diff/parity.py replica/features.csv`.

## Layout, per screen

The reference screenshots are from your account on a 1344×2992 phone. They are cropped to the app area
(status bar and gesture bar removed), and the clone is recorded at the same 448×903dp viewport.

| screen | layout | verdict | biggest difference | state |
| --- | --- | --- | --- | --- |
| S04 projects | 16.6 | different | top centre | same state |
| S06 report builder | 35.1 | different | middle centre | same state |
| S07 reports (empty) | 14.1 | different | middle centre | same state |
| S08 tasks (empty) | 17.5 | different | top centre | same state |
| S12 material | 24.4 | different | middle centre | clone shows a placeholder: material is not built |
| S21 attendance | 24.8 | different | top centre | reference shows its own server error; clone shows the register |
| S24 attendance settings | 35.9 | different | bottom centre | reference has a toast at the bottom |
| S25 more | 50.9 | partly | middle left | same state |
| S27 feed | 18.1 | different | middle centre | different data: reference has 1 post, clone has the seed feed |
| S28 profile | 17.6 | different | top centre | same state |

Diff images: `replica/diffs/<screen>.png`.

**How to read these.** The tool compares where edges fall on a grid, so it punishes the same block placed 100px
lower as much as a missing block. Calibration on these screenshots:

- a screen against itself: 100
- two unrelated screens of the original (Reports vs Tasks): 19.4
- two unrelated screens of the clone: 10.1

So anything under about 20 is no closer than two different screens. Checked by eye, the clone has the **same
blocks in the same order** on every pair: app bar with back arrow and overline, banner, list or empty state, primary
action, bottom nav. Where the clone differs:

- **Vertical placement and density.** The original centres empty states under a large illustration (S07, S08);
  the clone puts a small icon near the top. The clone's rows are denser, so content ends higher (S04, S28).
- **Header.** The clone's sub-page app bar has two lines, the project name over the title. The original's S07
  has one line.
- **Attendance.** The original has search and a "Labour on site" button beside the date. The clone has a
  full-width date stepper, then "On site: n of 5" and "Mark rest present".

None of these stop a Powerplay user from finding things: the hierarchy, tab order (Report, Task(s), Material,
Attendance, More) and action names match. Layout parity is not a target in itself. The look is their trade dress,
and `/replica-brand` changes it. If switchers should feel at home sooner, the cheap win is centring empty states.

## Behaviour differences

The original's side comes from your screenshots and public sources, not a scripted walk of their app.

| flow | original does | clone does | fix or keep |
| --- | --- | --- | --- |
| F02 report | 3 taps with defaults (Report tab → Create Report → open) | same 3 taps | keep |
| F02 report | reports deleted after 15 days (S07 banner) | kept 90 days (`reports.expires_at`, nightly cleanup) | keep: users complain |
| F05 attendance | an empty day can show a raw server error: `"end_date" must be greater than ref:start_date` (S21) | dates validated; plain messages ("Enter between 0 and 16 hours") | keep |
| F05 attendance | per-worker marking (bulk marking not seen in the screenshots) | each worker, or "Mark rest present" for everyone unmarked | keep; check the original by hand |
| F05 attendance | no correcting a wrong entry (users ask, src 11) | change or clear a mark; the server keeps an audit log | keep |
| F05 payables | review and record payables for a period (S23) | 7-day wages preview only; recording not built | fix (should) |
| F01 log work | progress and photos; in-app camera with video (S32) | quantity or percent, remarks in any script, photos via the phone's camera app; works offline and syncs later | fix camera (must) |
| F07 issues | inactivity issues raised by their support team (S31) | none yet; a gentle daily push nudge at 18:30 IST instead | keep the nudge; build issues (should) |
| F10 sign in | phone OTP | phone OTP (MSG91, DLT templates needed) | keep |
| F10 plan | Free plan, paid upgrade | 3 active sites on Free, enforced on the server and in the demo; Razorpay subscription; no plan screen yet | fix (could) |
| Accessibility | not measured | every tappable thing named and at least 48dp, checked on every tested screen | keep |
| Memory between visits | not checked | offline-first local database with sync; the demo build resets on restart | keep |

## Top five to build next

1. **Material stock: list, catalogue, add and update** (S15). 3 must-haves, and every other material screen
   builds on it. The tables and the stock ledger trigger already exist on the server.
2. **Site GRN and issue to a task** (S14, S19). 2 must-haves that close the daily loop of material in and material used.
3. **Photo gallery** (S30), plus putting the in-app camera on the same screen (S32). 2 must-haves.
4. **Team screen: invite, roles, members** (S36). The must-have is half done: the invite function and access
   rules exist, only the screen is missing.
5. **Organisation edit with logo, profile email, project image** (S29, S28, S04). 3 small partial must-haves.

Those five would cover 11 of the 13 missing must-haves. The other two (report parts and the managed category
list) follow from material and a settings list.

Next: `/replica-build` for the gaps above. `/replica-entrepreneur` comes after the must-haves are in.

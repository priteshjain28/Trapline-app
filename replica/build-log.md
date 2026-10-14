# Build log: site app

Code: `site-app/` (Kotlin + Jetpack Compose). `:core` is pure Kotlin (models, data layer, rules, 15 tests).
`:app` is the Android app, built by `.github/workflows/site-app.yml`, which also records screenshots into
`replica/clone-screens/`. The data layer is an in-memory fake (`FakeBackend`) behind the same interfaces
Room + Supabase will implement, so `/replica-backend` changes `AppContainer` and no screen code.

## Screens

| ID | date | state | missing | harder than expected |
| --- | --- | --- | --- | --- |
| S01 sign in | 2026-10-14 | partial | Real OTP (fake: code 123456), language picker (S02) | – |
| S03 new project | 2026-10-14 | done | Project image | – |
| S04 projects | 2026-10-14 | done | Card overflow menu (archive, edit); changing the cover photo later | – |
| S05 project shell | 2026-10-14 | done | Messages and Help open placeholders | – |
| S06 report builder | 2026-10-14 | done | Parts for material, issues and payables (their screens come first) | The original's period radios run off-screen; ours wrap as chips |
| S07 reports | 2026-10-14 | done | – | Rendering moved on-device (PdfDocument) as a stand-in for the server worker |
| S08 tasks | 2026-10-14 | done | "List view" switch (other views were never seen) | – |
| S09 task detail + update | 2026-10-14 | done | Comments, editing or deleting an update | Photo URIs from the gallery can lose permission, so picks are copied into app storage |
| S10 new task | 2026-10-14 | done | Assignee picker (needs members, S36) | – |
| S14 received at site | 2026-10-14 | done | Deliveries against purchase orders (S13), photos of the challan | Each delivery is a header plus lines; the outbox sends the header first so the server's line rules pass |
| S15 on site | 2026-10-14 | done | Subcontractor inventory switch | Stock is never stored: computed from receipts, issues and corrections (same rule as the server ledger) so it is right offline |
| S16 material history | 2026-10-14 | done | Correcting or deleting an entry | – |
| S19 issued | 2026-10-14 | done | Issue to a subcontractor | Over-issue is checked on the phone; two phones issuing offline can still overdraw (see notes) |
| S12, S13, S17, S18, S20 material | 2026-10-14 | stub | Requests, deliveries against orders, transfers, returns | – |
| S29 company | 2026-10-14 | done | – | Needed a new storage bucket (org-assets) and path rules |
| S30 photos and albums | 2026-10-14 | done | Video, deleting a photo | Albums typed on two phones merge (id from the name) |
| S36 people on a site | 2026-10-14 | done | Company-level admins screen; leaving a site you were removed from still shows its cached data until sign-out | Inviting needs a connection: the server sends the SMS |
| S21 attendance register | 2026-10-14 | done | Hourly marking (hours per day) | – |
| S22 add worker | 2026-10-14 | done | Labour contractor link | – |
| S23 payables | 2026-10-14 | partial | Recording payables and payments; only a 7-day wages preview | – |
| S24 attendance settings | 2026-10-14 | done | – | – |
| S25 more | 2026-10-14 | done | 9 of its 12 items open placeholders | – |
| S27 feed | 2026-10-14 | done | Replies and comments | – |
| S28 profile | 2026-10-14 | done | – | Showed 'Owner' for everyone; now the real company role |
| S02, S11, S26, S31–S35, S37, S38 | – | not started | – | – |

Built: 15 screens (12 done, 3 partial) + 1 stub tab, out of 38 in the recon.
Must-haves: 18 of 33 done (`parity.py`: 47/100 overall). CI green on 2026-10-14: 15 core tests, debug APK,
15 screenshots in `replica/clone-screens/` (1344 px wide, same as the reference).

## Decisions and notes

- **Fonts:** the system sans for now. The design folder has Noto Sans only as web fonts (woff2), which Android can't
  load from `res/font`. Android already renders Devanagari with Noto. TTFs come with the brand.
- **Camera:** "Take photo" uses the phone's camera app (no camera permission needed). The original's in-app camera with
  video (S32) is still to do.
- **Offline:** the fake layer is in memory, so data resets when the app restarts. The real offline layer
  (Room + outbox) is `/replica-backend` work. Until then, the "offline" must-have stays at `no`.
- **compileSdk 34:** Robolectric 4.13 (screenshot tests) supports up to 34. Google Play needs target 35 for new apps,
  so raise both before `/replica-deploy`.
- **Hindi:** strings are English only for now. All copy is in `strings.xml`, so `values-hi/` is a translation job,
  not a code change.

## Backend (2026-10-14)

`/replica-backend`: Supabase schema + 4 follow-up migrations, 7 Edge Functions, and an offline-first data layer
(SQLDelight + outbox + sync) behind the same repository interfaces. With `SUPABASE_URL`/`SUPABASE_ANON_KEY` set at
build time the app uses it; without them it stays the demo. CI green: `site-app.yml` (29 core tests, APK, screenshots)
and `backend.yml` (database tests on Postgres 16, Deno tests and type-check). Not yet run against a real Supabase
project, which needs the user's keys. See `replica/backend.md`. Parity 50.9 (20 of 33 must-haves).

## Gaps build (2026-10-14)

From `/replica-diff`'s top five. Built: material (S14, S15, S16, S19), photos and albums (S30), people (S36), company (S29),
profile email, site cover photo. Each has its data layer on the demo and on the offline-first synced layer (local schema
v2 to v5, each with a migration for installed builds), core tests (48 pass, including two-phone sync for material,
albums and invites), flow tests with the accessibility audit, and screenshots. Parity 50.9 → 66.9; must-haves 20 → 30 of 33.

Harder than expected, said plainly:

- **Material on several phones.** Stock is a sum, so two phones each issuing the last 50 bags offline both succeed, and
  the site shows -50 after sync. The server records what happened rather than refusing work already done on site; the
  stock list shows negative stock in red so someone recounts. A server-side warning is a follow-up.
- **Catalogue duplicates.** "Cement" and "cement " would be two materials, so ids come from the normalised name, spec
  and unit; offline adds on two phones merge into one row.
- **Inviting is online only.** The SMS comes from the server, so the invite sheet says so when offline.

Also built after the top five: a Material part in reports, and a managed list of task categories (S10 chips). Must-haves
31 of 33. Left: the in-app camera (S32; the phone's camera app works today) and the report parts that need screens not
built yet (issues, payables). Cosmetic, seen in screenshots: on S14 the date field is a little shorter than the challan
field beside it.

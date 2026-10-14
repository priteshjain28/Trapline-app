# Components: site app

Built from the recon component list (`replica/recon.md`, pass 2). Tokens come from `tokens.json`. Kotlin names are in
`compose/` and CSS names in `tokens.css`. Every screen uses these components and roles, never raw colours or sizes.

## Ground rules

- **Platform:** Android, Jetpack Compose. Every tappable thing is at least 48×48dp (`size.touch-min`) and has a TalkBack label.
- **Icons:** Lucide (ISC), 24dp, 2px stroke, in `icons/`. Convert them to Android vector drawables at build time.
  Never the original's icons. Icon-only buttons always have a `contentDescription`.
- **Illustrations:** none of theirs. An empty state is a Lucide icon in a 72dp `accent-tint` circle, a one-line title,
  one line of help, and one primary action. Bespoke illustrations can come with the brand.
- **Copy:** every string below is ours. All copy lives in `strings.xml` (English + Hindi first) and is never hard-coded.
  Use sentence case, plain words, no exclamation marks.
- **Numbers:** ₹ amounts use Indian digit grouping (₹1,19,999), quantities show their unit ("5 bags"), and dates read
  "Mon, 12 Oct". Numbers use tabular figures in lists.
- **Errors:** never show a server message. Map every failure to plain text ("Couldn't load attendance. Check your
  connection and try again.") plus a retry button. The original leaks raw validation text (recon S21).
- **Offline:** any screen can show the offline banner. Anything not yet synced shows a small clock badge until it uploads.
- **Dark mode:** supported from day one through `DarkSiteColors`. Both palettes pass AA (`gen_tokens.py --check`).

---

## Structure

```
AppBarOrg (S04)
  variants  org home only
  layout    64dp + status bar; bg header; org avatar 32dp (initials, radius md) · org name base/600 on-header ·
            plan badge xs on-header-muted ("Free") · spacer · Chat icon button · user avatar 32dp
  states    default, offline (offline banner slides in below)
  a11y      org name is a heading; avatar button "Your profile"; chat button "Messages"
  used on   S04

AppBarProject (S05)
  layout    64dp; bg bg; close (x) icon button · column [org name xs text-muted / project name base/600 + chevrons-up-down] ·
            spacer · Chat icon button · Help text button (outline, sm)
  behaviour tapping the project name opens ProjectSwitcher (bottom sheet with search)
  a11y      project name button "Switch project, current: palak"
  used on   S05 tabs (S06, S08, S12–S21, S23, S25)

AppBarSub (S24, S26, S27, S31)
  layout    64dp; back arrow · column [project name xs text-muted / page title lg] · optional trailing action (TextButton or icon)
  used on   S07, S24, S26, S27, S28, S29, S30, S31

BottomNav (S05)
  items     Report (file-text) · Tasks (clipboard-check) · Material (package) · Attendance (user-check) · More (menu)
  layout    72dp; top border; icon 24 + label xs; active = accent icon (filled container 32×64dp accent-tint) + accent label
  states    default, active, badge (count dot, danger) for pending approvals
  a11y      NavigationBar semantics; "Tasks, tab 2 of 5, selected"
  used on   S05

Tabs (scrollable)
  layout    48dp; label sm/600; active text accent + 3dp indicator accent (radius sm top); inactive text-muted; bottom border
  options   counts in labels: "Open (1)"
  a11y      TabRow semantics; swipe between panes
  used on   Material (Indent, Deliveries, GRN / Stock, Transfers out, Transfer requests, Issued, Returns),
            Attendance (Register, Payables), Gallery (Photos, Albums), Issues (Open, Closed, All)

SegmentedControl
  layout    40dp; track surface-track radius md, 4dp padding; selected segment surface-raised + border, text base/600 text;
            unselected text-muted
  a11y      selectable group; role = Tab
  used on   Material (Buying / Stock)
```

## Actions

```
Button
  variants  primary (bg accent, text on-accent) · secondary (bg surface-raised, 1dp border-input, text text) ·
            tint (bg accent-tint, text on-accent-tint) · text (no bg, text accent) · danger (bg danger, text on-accent)
  sizes     sm 36dp · md 48dp · lg 56dp (full-width sticky footer: "Create report", "Save")
  states    default · pressed (accent-hover / 8% overlay) · focus-visible (2dp ring focus, 2dp offset) · disabled (38% opacity,
            no ripple) · loading (spinner replaces leading icon, label kept and announced "Creating report")
  tokens    radius md, type base/600 (sm: sm/600), icon 20dp leading
  a11y      Button role; disabled buttons say why in supporting text nearby, not only by greying out
  used on   everywhere

Fab
  variants  extended (icon + label: "Add worker", "Add material", "New issue", "Record payment") · icon (plus; cloud-upload;
            folder-plus)
  layout    56dp tall, radius lg, bg accent, text on-accent, shadow pop; 16dp from the right and bottom edges, above BottomNav
  behaviour collapses to icon-only while scrolling down, expands on scroll up
  a11y      extended label is the content description; icon FABs need one ("Upload photos")
  used on   S08, S15, S21, S23, S30, S31

IconButton
  layout    48dp target, 24dp icon, text colour text (on header: on-header)
  states    default, pressed (circle overlay), disabled
  used on   app bars, list rows (ellipsis-vertical), camera

LinkAction ("+ Update", "Add email")
  layout    text button sm/600 accent with leading plus 20dp; 48dp touch target even when visually small
  used on   S15 rows, S28
```

## Inputs

```
TextField
  variants  text · phone (+91 prefix, numeric keypad) · otp (6 boxes, auto-fill from SMS) · number with unit suffix
            ("bags") · money (₹ prefix, Indian grouping, stores paise) · multiline (remarks)
  layout    48dp; radius md; 1dp border-input; label above in sm/600 text-muted, required marked with " *" in danger
            plus "(required)" for screen readers; helper text xs text-muted below
  states    empty (placeholder text-subtle) · focused (2dp focus border) · filled · error (danger border + message with
            circle-alert, announced) · disabled (surface bg, text-muted) · read-only
  a11y      label is the accessible name; errors are announced with LiveRegion
  used on   S01, S03, S10, S11, S13, S14, S16, S22, S26, S29, S31

SearchField
  layout    48dp; leading search icon; placeholder "Search tasks by number or name"; clear (x) when filled
  states    empty, typing (results filter on each keystroke, locally), no results (inline empty state)
  used on   S04, S08, S15, S21, S23, S31

DateField / DateRangeField
  layout    TextField with trailing calendar icon; opens the Material date picker; ranges show "12 Sep – 12 Oct 2026"
  rules     a range can't end before it starts. Validate on pick and say so in plain words.
  used on   S06, S10, S23

DateStepper
  layout    outline 48dp: chevron-left · "Mon, 12 Oct 2026" (base/600) · chevron-right; next is disabled on today
  a11y      buttons "Previous day" / "Next day"; date text opens the picker
  used on   S21

Select / BottomSheetPicker
  layout    looks like a TextField with chevron-down; opens a bottom sheet list with search for > 7 options
  used on   material, vendor, worker, assignee, category, unit

RadioGroup (horizontal chips on narrow screens)
  rule      if options don't fit, wrap or switch to chips. Never let an option run off-screen (the original's
            "Specific Da…" does, recon S06).
  used on   S06 duration (Today, Last 7 days, Last 15 days, Custom)

ChoiceCard
  layout    full-width card, radius md, 1dp border; title base/600 + description sm text-muted; optional "New" Badge
  states    unselected · selected (bg accent-tint, 2dp accent border, check icon) · focus
  a11y      radio semantics inside a group
  used on   S06 report type

CheckboxTree
  layout    parent row: checkbox + title base + description sm text-muted. Children are indented 32dp with a 1dp border guide.
  states    checked · unchecked · indeterminate (some children) · locked-on (disabled checked, with the reason) ·
            unavailable (disabled unchecked + reason "Add team members first")
  a11y      tri-state checkbox semantics; reason text read after the label
  used on   S06 "What to include"

Switch
  layout    52×32dp track; on = accent track + on-accent thumb; row has title base + description sm text-muted
  a11y      the whole row toggles; state announced "On"/"Off"
  used on   S24

ValueChip (setting value)
  layout    pill, accent-tint bg, on-accent-tint text sm/600, then chevron-right
  used on   S24 shift length
```

## Display

```
ProjectCard
  layout    radius md, 1dp border, 16dp padding. Row 1: 56dp image (radius md; placeholder = image-plus on accent-tint) ·
            name base/600 · StatusBadge. Divider. Row 2: ProgressRing + "32%" · calendar icon + date range
            ("Add dates" link when empty) · overflow menu
  states    default · no image · no dates · archived (text-muted, badge "Archived") · syncing
  a11y      one button for the whole card ("palak, active, 32% done, 1 Oct to 31 Mar"); the overflow menu stays separate
  used on   S04

ListRow
  variants  task · stock (name, spec, unit, in-stock, LinkAction) · worker (name, skill, attendance toggle) · issue (title,
            due, assignee, StatusBadge) · member · setting (title, description, trailing control) · document (number, party, date, amount)
  layout    min 56dp; 16dp side padding; dividers 1dp border between rows (not around)
  states    default · pressed · selected · not synced (clock badge) · error (sync failed: circle-alert + "Tap to retry")
  used on   most lists

StatusBadge
  variants  neutral (surface-track / text-muted) · info (info-tint / info) · success (success-tint / success) ·
            warning (warning-tint / warning) · danger (danger-tint / danger)
  layout    radius sm, 4×8dp padding, xs/600
  mapping   Active=success, On hold=warning, Open=warning, Closed=neutral, Delayed=danger, Draft=neutral,
            Approved=success, Partly received=info, Paid=success, Due=warning
  rule      colour never alone; the word is always there
  used on   S04, S08, S12–S14, S23, S31

Badge ("New", counts)
  layout    radius sm, success-tint/success, xs/600, sentence case ("New")
  used on   S06

ProgressRing
  layout    20dp, 3dp stroke, track surface-track, value accent; label sm text beside it ("32%")
  a11y      progress semantics with the value
  used on   S04, S08

StatTile (expense category)
  layout    radius md, surface bg, 4dp left bar in the category's series colour; label sm text-muted; amount lg text
            (tabular); 2 columns, 16dp gap
  rule      the label and amount carry the meaning; the colour bar only links the tile to the chart
  used on   S26

StackedBar (expense split; replaces the original's ring)
  layout    one 16dp bar, full width, radius sm on the outer ends, 2dp surface gaps between segments; segments in
            series order. Above it: "Total spent" sm text-muted + ₹ total xl. Below: StatTiles act as the legend.
  states    empty (single surface-track bar + "Nothing spent in this period") · loading (shimmer)
  a11y      the bar is decorative; TalkBack reads the tiles ("Material, ₹42,000, 38%")
  used on   S26

FeedCard
  layout    surface-raised card on surface background; avatar 40dp · "Ravi created a task" (base, actor 600) · time xs
            text-muted ("2 h ago") · body sm · optional action Button (secondary md) · comments count (message-square + n)
  variants  card (actions/comments) · system line (centred sm text-muted, no card: "Ravi joined as manager")
  used on   S27

Avatar
  layout    initials, 600 weight; sizes 32/40/96; bg from a fixed set of 6 tints, picked by user id (not random each time)
  a11y      decorative when the name is printed beside it
  used on   S04, S27, S28, S36

InfoBanner
  variants  info (info-tint / info) · warning · danger; optional trailing chevron (link) or x (dismiss, remembered)
  layout    16dp padding, info icon 20dp, sm text
  used on   S07 ("Reports are kept for 90 days"), S26 ("What counts as an expense")

CoachMark
  layout    InfoBanner variant with a 8dp pointer toward the control it explains; dismiss x; shown once per user
  used on   S15 (stock owner switch)

EmptyState
  layout    centred: 72dp accent-tint circle + 32dp Lucide icon (accent) · title base/600 · help sm text-muted ·
            primary Button md
  copy      Tasks: "No tasks yet" / "Add the work planned on this site to track progress day by day." / "Add task"
            Indents: "No material requests" / "Ask the office for materials when the site runs low." / "Request material"
            Deliveries: "No deliveries on the way" / "Purchase orders you send appear here until they arrive." / "New purchase order"
            GRN: "Nothing received yet" / "Record each delivery when it reaches the site." / "Record delivery"
            Transfers out: "No transfers sent" / "Send spare material to another site." / "Send material"
            Transfer requests: "No requests" / "Ask another site for material it can spare." / "Ask a site"
            Issued: "Nothing issued yet" / "Record material handed to a task or a subcontractor." / "Issue material"
            Returns: "No returns" / "Record material that comes back to the store." / "Record return"
            Payables: "Nothing due" / "Work out wages from attendance and record what you owe." / "Work out wages"
            Gallery: "No photos yet" / "Photos taken in the app show up here." / "Take photo"
            Reports: "No reports yet" / "Build a report for any day or range and share it." / "Create report"
            Attendance (no workers): "No workers on this site" / "Add workers once, then mark them present each day." / "Add worker"
  used on   every list

ErrorState
  layout    EmptyState with circle-alert in danger-tint; plain-language title + "Try again" secondary button
  used on   every list (replaces raw server errors)

OfflineBanner
  layout    32dp strip under the app bar, surface-track bg, wifi-off icon + "Offline – changes will upload when you're back online"
  used on   global

Toast / Snackbar
  variants  success (success-solid / on-success-solid, check) · neutral (inverse: bg text / text bg, so it stands out in both themes; optional underlined "Undo") · error (danger bg, on-accent text in light; danger-tint bg, danger text in dark)
  layout    radius md, 48dp min, bottom above nav; 4 s; one at a time
  a11y      polite live region
  used on   S24 ("Saved"), deletes ("Attendance removed · Undo")
```

## Overlays and media

```
BottomSheet
  layout    radius lg on top, drag handle, title lg, content, sticky actions
  used on   pickers, project switcher, filters, confirm delete

Dialog (confirm)
  layout    title lg, body sm, actions text + primary/danger; never stacked
  used on   delete (with what will be lost), discard draft

FilterSheet
  layout    BottomSheet with groups of chips and date range; "Clear" and "Show 12 results"
  used on   S08, S15, S21, S23, S26, S31

FilterChips (quick filters)
  layout    32dp chips (48dp touch target), radius md, 1dp border, xs/600; selected = accent-tint + accent border + check icon
  states    off, on, disabled
  used on   S08 (In progress, Has work order, Delayed, Has open issues)

Camera (S32)
  layout    full-screen black; top: close, flash (zap-off), photo/video toggle; bottom: gallery picker · 72dp shutter · switch-camera
  rules     photos saved to the app first (works offline), then queued for upload; a counter shows unsent items
  a11y      shutter "Take photo"; volume key also shoots

PhotoGrid
  layout    3 columns, 2dp gaps, square thumbs (size.thumb), video badge; long-press to select
  states    loading (surface-track placeholders) · uploading (progress ring overlay) · failed (circle-alert + retry)
  used on   S09, S30, S14 (challan photo)
```

## Check before a screen is "done"

- Every state above that applies is built: empty, loading, filled, error, offline, not synced, disabled, focus.
- TalkBack reads it in order, every control has a name, and touch targets are at least 48dp.
- It works at 200% font size and on a 360dp-wide phone, with no clipped labels.
- No raw colours, sizes or strings in the screen code.

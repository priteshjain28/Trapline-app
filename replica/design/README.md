# Design system: site app

Measured from the 26 screenshots in `replica/screens/` (pixel-sampled, not eyeballed). The values are our own.

| file | what |
| --- | --- |
| `tokens.json` | **The source of truth.** Colour roles (light + dark), type, spacing, sizes, radius, shadow, motion, expense series. |
| `gen_tokens.py` | Writes `tokens.css`, `tailwind.tokens.cjs` and `compose/*.kt` from `tokens.json`. `--check` runs WCAG on both palettes. |
| `compose/` | Kotlin theme for the Android app (`SiteTheme`, `SiteColors`, `SiteType`, `Space`, `Size`, `Radius`, `ExpenseCategory`). Copied into `site-app/` by `/replica-build`. |
| `tokens.css`, `tailwind.tokens.cjs` | For HTML: report PDFs (the Chromium worker) and the later web dashboard. |
| `components.md` | Every component: variants, sizes, states, tokens, accessibility, screens, empty-state copy. |
| `primitives.src.html` → `primitives.html` | Every primitive in light and dark, self-contained. `primitives.png` is the screenshot check. |
| `icons/` | 45 Lucide icons (ISC, `LICENSE-lucide.txt`). |
| `fonts/` | Noto Sans Latin + Devanagari, 400/600/700 (SIL OFL, `OFL.txt`). |

```
python3 replica/design/gen_tokens.py --check      # regenerate + contrast, light and dark: 30 pairs each, 0 failing AA
python3 replica/design/build_primitives.py        # rebuild the preview page
```

## What was measured

| | original | ours |
| --- | --- | --- |
| Screen | 1344 px wide; a 16 dp gutter measures 48 px, so density is about 3.0 (448 dp) | 16 dp gutter, 8 dp grid |
| Type levels | 12 · 14 · 16 · 20 sp, humanist sans | 12 · 14 · 16 · 20 · 24 · 32, **Noto Sans** (one family for all 8 Indian scripts) |
| Greys | text #071424, muted #4b6075, placeholder #7f90a3, border #dae1e8, surface #f7f9fa, track #f0f3f7 | the same six roles, own values |
| Accent | blue #1a50cc + navy header #00174d: **their brand** | graphite placeholder #334155 / #1e293b until `/replica-brand` |
| Status | amber pill #c78b20 on #faeedc, green toast #388a24, red #c73c1a | warning #7f4d00 on #fcefd9, success #1f7337, danger #b42318 |
| Controls | 48 dp fields, 56 dp primary button, 8 dp radius, flat cards with 1 px borders | the same |
| Expense chart | a ring with 5 hand-picked colours (two the same blue) | stacked bar, 6 validated series colours (adjacent CVD ΔE ≥ 8.4), every category labelled |

Accessibility failures in the original that the tokens fix:

- **Pill text:** the amber "Active"/"0%" text (#c78b20) is 2.5:1 on its pill and 2.9:1 on white. Ours is 6.2:1.
- **Input borders:** #dae1e8 on white is 1.3:1. Ours (#7b8694) is 3.7:1.
- **Placeholder text:** #7f90a3 is 3.2:1. Ours is 5.6:1.
- **Status:** the original shows status by colour alone. Ours always prints the word on the badge.

## Not taken from the original

Their colours (they stay as placeholders), logo, illustrations (we use our own empty-state pattern), icons
(we use Lucide), font and copy (every string in `components.md` is new).

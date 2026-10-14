"""Layout diff of the clone against the reference screenshots, for /replica-diff.

The reference screenshots (replica/screens/) are full phone captures: 1344x2992 with a 190px status bar on top and
a 92px gesture bar at the bottom. The clone's (replica/clone-screens/) are the app area only, recorded at 448x903dp
(1344x2709). This crops the system bars off the reference so both start at the app's first pixel, then runs
imgdiff's layout mode on each pair. Writes replica/diffs/<id>.png (diff image) and <id>.json (for parity.py).

    python3 replica/diff_screens.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(HERE, "..", ".claude", "skills", "replica-diff")
sys.path.insert(0, TOOLS)
import imgdiff  # noqa: E402

STATUS_BAR = 190
GESTURE_BAR = 92

# clone id, reference file, state note (empty when both show the same state)
PAIRS = [
    ("S04", "S04-projects-list", ""),
    ("S06", "S06-report-builder-top", ""),
    ("S07", "S07-reports-list-empty", ""),
    ("S08-empty", "S08-tasks-empty", ""),
    ("S14-empty", "S14-site-grn-empty", ""),
    ("S15", "S15-material-list", "different data: reference has 1 material, clone has the seed site"),
    ("S21", "S21-attendance-error", "reference shows its own server error; clone shows the register"),
    ("S24", "S24-attendance-settings", "reference has a toast at the bottom"),
    ("S25", "S25-more-menu", ""),
    ("S27", "S27-feed", "different data: reference has 1 post, clone has the seed feed"),
    ("S28", "S28-profile", ""),
    ("S30", "S30-gallery-media", ""),
]


def crop_reference(src, dst):
    w, h, rows = imgdiff.read_png(src)
    rows = rows[STATUS_BAR:h - GESTURE_BAR]
    imgdiff.write_png(dst, w, len(rows), rows)


def main():
    out = os.path.join(HERE, "diffs")
    os.makedirs(out, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        for cid, ref, note in PAIRS:
            ref_png = os.path.join(HERE, "screens", ref + ".png")
            clone_png = os.path.join(HERE, "clone-screens", cid + ".png")
            if not os.path.exists(clone_png):
                print("skip %s: no clone screenshot" % cid)
                continue
            cropped = os.path.join(tmp, ref + ".png")
            crop_reference(ref_png, cropped)
            run = [sys.executable, os.path.join(TOOLS, "imgdiff.py"), cropped, clone_png]
            subprocess.run(run + ["--out", os.path.join(out, cid + ".png")], check=True, capture_output=True)
            report = json.loads(subprocess.run(run + ["--json"], check=True, capture_output=True, text=True).stdout)
            report["screen"] = cid.split("-")[0]
            report["state_note"] = note
            with open(os.path.join(out, cid + ".json"), "w") as fh:
                json.dump(report, fh, indent=1)
            results.append((cid, report.get("score"), note))
    for cid, score, note in results:
        print("%-10s %5.1f  %s" % (cid, score, note))


if __name__ == "__main__":
    main()

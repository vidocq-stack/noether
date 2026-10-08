#!/usr/bin/env bash
# Checks that mansart-validation is neutral for the Jakarta Persistence TCK: the first failsafe execution is
# run twice, once with no Bean Validation on the class path and once with jakarta.validation-api and
# mansart-validation-core on it, and the two results are compared test by test.
#
# Why: no test of the TCK uses Bean Validation (see ROADMAP.md, P11), so the temporary validation brick must not
# change a single result. If it does, the brick is in the way and has to be fixed.
#
# Contract with the runner (to be honoured by mansart-jpa-tck, milestone P0):
#   TCK_VALIDATION=off   the first execution has neither jakarta.validation-api nor mansart-validation-core
#   TCK_VALIDATION=on    the first execution has both (the second execution never has them)
#
# Usage:
#   ./check-validation-neutrality.sh                      # whole suite
#   ./check-validation-neutrality.sh -Dit.test=...        # extra arguments are passed to the runner
#
# Overrides (environment): RUNNER, REPORTS (the failsafe XML reports the runner leaves), OUT.
# Exit status: 0 neutral, 1 results differ, 2 unusable run (no reports, no test run), 78 runner not found.

set -euo pipefail
cd "$(dirname "$0")"

RUNNER="${RUNNER:-mansart-jpa-tck/run-official-tck-persistence-3.2.sh}"
REPORTS="${REPORTS:-mansart-jpa-tck/target/failsafe-reports}"
OUT="${OUT:-mansart-jpa-tck/target/validation-neutrality}"

if [ ! -x "$RUNNER" ]; then
    echo "Runner not found or not executable: $RUNNER" >&2
    echo "The TCK runner (milestone P0 of ROADMAP.md) has to exist before this check can run." >&2
    exit 78
fi

rm -rf "$OUT"
for mode in off on; do
    echo ">>> Jakarta Persistence TCK, Bean Validation: $mode"
    rm -rf "$REPORTS"
    # A red TCK is normal before certification: only the comparison matters here.
    TCK_VALIDATION="$mode" "$RUNNER" "$@" || echo "(the runner exited with status $?, continuing)"
    if [ -z "$(find "$REPORTS" -name 'TEST-*.xml' -print -quit 2>/dev/null)" ]; then
        echo "No failsafe report in $REPORTS after the run with validation $mode" >&2
        exit 2
    fi
    mkdir -p "$OUT/$mode"
    cp -R "$REPORTS"/. "$OUT/$mode/"
done

python3 -I - "$OUT/off" "$OUT/on" <<'PY'
import glob, os, sys
import xml.etree.ElementTree as ET

def results(directory):
    found = {}
    for report in sorted(glob.glob(os.path.join(directory, "**", "TEST-*.xml"), recursive=True)):
        execution = os.path.relpath(os.path.dirname(report), directory)
        for case in ET.parse(report).getroot().iter("testcase"):
            ident = f"{execution}/{case.get('classname')}#{case.get('name')}"
            if case.find("failure") is not None or case.find("error") is not None:
                status = "fail"
            elif case.find("skipped") is not None:
                status = "skipped"
            else:
                status = "pass"
            found[ident] = status
    return found

off, on = results(sys.argv[1]), results(sys.argv[2])
def counts(r):
    return {s: sum(1 for v in r.values() if v == s) for s in ("pass", "fail", "skipped")}

print(f"validation off: {len(off):5d} tests  {counts(off)}")
print(f"validation on : {len(on):5d} tests  {counts(on)}")

if not off or not on:
    print("A run executed no test at all: the check proves nothing.", file=sys.stderr)
    sys.exit(2)

differences = [(t, off.get(t, "absent"), on.get(t, "absent")) for t in sorted(set(off) | set(on)) if off.get(t) != on.get(t)]
if differences:
    print(f"\nNOT NEUTRAL: {len(differences)} test(s) differ (off -> on):")
    for test, before, after in differences[:50]:
        print(f"  {test}: {before} -> {after}")
    if len(differences) > 50:
        print(f"  ... and {len(differences) - 50} more")
    sys.exit(1)
print("\nNEUTRAL: the same tests, with the same results, with and without Bean Validation.")
PY

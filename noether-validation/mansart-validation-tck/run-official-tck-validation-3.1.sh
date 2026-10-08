#!/usr/bin/env bash
# Runs the official Jakarta Validation 3.1 TCK against the Mansart provider.
#
# The TCK (jakarta.validation:validation-tck-tests:3.1.1) is on Maven Central: nothing to install.
# The run is container-less: TestNG + the "local" Arquillian container, integration tests excluded.
#
# Usage:
#   ./run-official-tck-validation-3.1.sh                 # whole suite
#   ./run-official-tck-validation-3.1.sh -Dtest=Foo      # extra arguments are passed to Maven
#
# Output: target/tck-validation-output.log and target/tck-report-validation.txt.
#
# Override: TCK_VER=3.1.1 (a TCK version published on Maven Central).

set -euo pipefail
cd "$(dirname "$0")"

TCK_VER="${TCK_VER:-3.1.1}"
MVNW="../../mvnw"
LOG="target/tck-validation-output.log"
REPORT="target/tck-report-validation.txt"

mkdir -p target

echo ">>> Build and install mansart-validation-core (skipTests)"
(cd ../.. && ./mvnw -q -B -ntp -pl mansart-validation/mansart-validation-core -am install -DskipTests)

echo ">>> Run the Jakarta Validation ${TCK_VER} TCK"
"$MVNW" -B -ntp -Dtck.version="$TCK_VER" test "$@" 2>&1 | tee "$LOG" || true

echo ""
echo ">>> Report ($REPORT)"
{
    echo "Mansart Validation 3.1 TCK Report"
    echo "Generated : $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "TCK ver   : $TCK_VER"
    echo ""
    grep -E "Total tests run:|Tests run:|Failures:|BUILD (SUCCESS|FAILURE)" "$LOG" | tail -10 || echo "(no surefire summary)"
} > "$REPORT"
cat "$REPORT"

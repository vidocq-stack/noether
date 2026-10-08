#!/usr/bin/env bash
# Runs the official Jakarta Persistence 3.2 TCK against the Mansart provider, on PostgreSQL.
#
# Steps: install the TCK if needed (install-tck.sh), build and install the provider (mansart-jpa-core), start a throw-away PostgreSQL in Docker (or use an external
# database), apply the official DDL and stored procedures of the bundle, run both failsafe executions, report.
#
# Usage:
#   ./run-official-tck-persistence-3.2.sh                          # whole suite
#   ./run-official-tck-persistence-3.2.sh --area core.lock         # one area (package under ee.jakarta.tck.persistence)
#   ./run-official-tck-persistence-3.2.sh --external               # use JDBC_URL, JDBC_USER, JDBC_PASSWORD
#   ./run-official-tck-persistence-3.2.sh -- -Dsome.maven=option   # anything after -- goes to Maven
#
# Environment:
#   TCK_VALIDATION=off|on   Bean Validation on the class path of execution 1 (default off); see
#                           ../check-validation-neutrality.sh
#   PG_IMAGE                PostgreSQL image (default postgres:17-alpine)
#   KEEP_DB=1               leave the database container running after the run
#
# Output: target/tck-persistence-output.log, target/tck-report-persistence.txt, target/failsafe-reports/.
# The script exits 0 when the TCK ran and was reported, whatever its score; non-zero when it could not run.

set -euo pipefail
cd "$(dirname "$0")"

AREA=""
EXTERNAL=0
MAVEN_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --area) AREA="$2"; shift 2 ;;
        --external) EXTERNAL=1; shift ;;
        --) shift; MAVEN_ARGS=("$@"); break ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 64 ;;
    esac
done

TCK_VER=3.2.1
TCK_VALIDATION="${TCK_VALIDATION:-off}"
PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"
SQL_DIR="../.tck-cache/persistence-tck-$TCK_VER/sql/postgresql"
LOG="target/tck-persistence-output.log"
REPORT="target/tck-report-persistence.txt"
MVNW="../../mvnw"
DB_NAME=jpatck
DB_USER=cts1
DB_PASSWORD=cts1

case "$TCK_VALIDATION" in on|off) ;; *) echo "TCK_VALIDATION must be on or off" >&2; exit 64 ;; esac

rm -rf target/failsafe-reports
mkdir -p target

echo ">>> TCK artifacts"
./install-tck.sh

echo ">>> Build and install the provider under test (mansart-jpa-core)"
(cd ../.. && ./mvnw -q -B -ntp -pl mansart-persistence/mansart-jpa-core,mansart-persistence/mansart-jpa-dialects/mansart-jpa-dialect-postgresql -am install -DskipTests)

if [ "$TCK_VALIDATION" = on ]; then
    echo ">>> Build and install mansart-validation-core (Bean Validation on the class path of execution 1)"
    (cd ../.. && ./mvnw -q -B -ntp -pl mansart-validation/mansart-validation-core -am install -DskipTests)
fi

# ---- the database --------------------------------------------------------------------------------------------
CONTAINER=""
cleanup() {
    if [ -n "$CONTAINER" ] && [ "${KEEP_DB:-0}" != 1 ]; then
        docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

psql_run() { # file: runs it, errors do not stop (the scripts start by dropping what may not exist)
    if [ -n "$CONTAINER" ]; then
        docker exec -i "$CONTAINER" psql -q -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=0 < "$1"
    else
        PGPASSWORD="$JDBC_PASSWORD" psql -q -v ON_ERROR_STOP=0 "$PSQL_URL" < "$1"
    fi
}
psql_query() {
    if [ -n "$CONTAINER" ]; then
        docker exec -i "$CONTAINER" psql -At -U "$DB_USER" -d "$DB_NAME" -c "$1"
    else
        PGPASSWORD="$JDBC_PASSWORD" psql -At "$PSQL_URL" -c "$1"
    fi
}

if [ "$EXTERNAL" = 1 ]; then
    : "${JDBC_URL:?--external needs JDBC_URL, JDBC_USER and JDBC_PASSWORD}" "${JDBC_USER:?}" "${JDBC_PASSWORD:?}"
    command -v psql > /dev/null || { echo "--external needs psql on the PATH to apply the DDL" >&2; exit 69; }
    DB_USER="$JDBC_USER"
    PSQL_URL="postgresql://${JDBC_USER}@${JDBC_URL#jdbc:postgresql://}"
else
    command -v docker > /dev/null || { echo "Docker is needed (or use --external)" >&2; exit 69; }
    CONTAINER="mansart-jpa-tck-pg-$$"
    echo ">>> Starting PostgreSQL ($PG_IMAGE) in container $CONTAINER"
    docker run -d --rm --name "$CONTAINER" -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" \
        -e POSTGRES_PASSWORD="$DB_PASSWORD" -p 127.0.0.1::5432 "$PG_IMAGE" > /dev/null
    PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
    for _ in $(seq 1 60); do
        if docker exec "$CONTAINER" pg_isready -q -U "$DB_USER" -d "$DB_NAME" 2> /dev/null \
                && psql_query "select 1" > /dev/null 2>&1; then
            break
        fi
        sleep 1
    done
    psql_query "select 1" > /dev/null || { echo "PostgreSQL did not start" >&2; exit 1; }
    JDBC_URL="jdbc:postgresql://localhost:$PORT/$DB_NAME"
    JDBC_USER="$DB_USER"
    JDBC_PASSWORD="$DB_PASSWORD"
fi

echo ">>> Applying the official DDL and stored procedures (errors on the initial drops are expected)"
{
    psql_run "$SQL_DIR/postgresql.ddl.persistence.sql"
    psql_run "$SQL_DIR/postgresql.ddl.persistence.sprocs.sql"
} > target/ddl.log 2>&1 || true
TABLES="$(psql_query "select count(*) from information_schema.tables where table_schema = current_schema()")"
EXPECTED_TABLES="$(grep -ci '^ *create table' "$SQL_DIR/postgresql.ddl.persistence.sql")"
PROCEDURES="$(psql_query "select count(*) from information_schema.routines where routine_schema = current_schema()")"
echo "    tables: $TABLES (the DDL creates $EXPECTED_TABLES), stored procedures: $PROCEDURES"
if [ "$TABLES" -lt "$EXPECTED_TABLES" ]; then
    echo "The schema is incomplete, see target/ddl.log" >&2
    exit 1
fi

# ---- the run -------------------------------------------------------------------------------------------------
SELECTION=()
if [ -n "$AREA" ]; then
    AREA_PATH="ee/jakarta/tck/persistence/${AREA//.//}"
    SELECTION+=("-Dtck.tests=**/$AREA_PATH/**/*Client*")
    # Execution 2 holds a single test of se.entityManagerFactory: skip it unless the area contains it.
    case "se/entityManagerFactory" in
        "${AREA//.//}"*) ;;
        *) SELECTION+=("-Dtck.skip.execution2=true") ;;
    esac
fi

echo ">>> Running the Jakarta Persistence $TCK_VER TCK (Bean Validation: $TCK_VALIDATION${AREA:+, area: $AREA})"
"$MVNW" -B -ntp verify "-Dtck.jdbc.url=$JDBC_URL" "-Dtck.jdbc.user=$JDBC_USER" "-Dtck.jdbc.password=$JDBC_PASSWORD" \
    "-Dtck.validation=$TCK_VALIDATION" ${SELECTION[@]+"${SELECTION[@]}"} ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"} 2>&1 | tee "$LOG" || true

# ---- the report ----------------------------------------------------------------------------------------------
python3 -I - target/failsafe-reports "$TCK_VALIDATION" "${AREA:-all}" > "$REPORT" <<'PY'
import collections, datetime, glob, os, re, sys
import xml.etree.ElementTree as ET

reports, validation, area = sys.argv[1:4]
cases = []
for report in sorted(glob.glob(os.path.join(reports, "**", "TEST-*.xml"), recursive=True)):
    for case in ET.parse(report).getroot().iter("testcase"):
        problem = case.find("failure") if case.find("failure") is not None else case.find("error")
        status = "fail" if problem is not None else ("skipped" if case.find("skipped") is not None else "pass")
        text = "" if problem is None else (problem.get("message") or "") + " " + (problem.text or "")
        execution = os.path.basename(os.path.dirname(report))
        cases.append((case.get("classname") or "", case.get("name"), status, text, execution))

tck = [c for c in cases if c[0].startswith("ee.jakarta.tck.")]
foreign = [c for c in cases if not c[0].startswith("ee.jakarta.tck.")]
count = collections.Counter(c[2] for c in tck)
print("Mansart Jakarta Persistence 3.2 TCK report")
print(f"Generated  : {datetime.datetime.now(datetime.timezone.utc):%Y-%m-%dT%H:%M:%SZ}")
print(f"Validation : {validation}    Area: {area}")
print(f"Tests      : {len(tck)}  pass={count['pass']}  fail={count['fail']}  skipped={count['skipped']}")
for execution in sorted({c[4] for c in tck}):
    part = collections.Counter(c[2] for c in tck if c[4] == execution)
    print(f"  {execution:12s}: {sum(part.values())}  pass={part['pass']}  fail={part['fail']}  skipped={part['skipped']}")
if foreign:
    print(f"WARNING    : {len(foreign)} test(s) outside ee.jakarta.tck were run: the selection is wrong")
if not tck:
    print("WARNING    : no TCK test ran")
causes = collections.Counter()
for _, _, status, text, _ in tck:
    if status == "fail":
        # The deepest "Caused by" is the reason; "Setup failed" alone says nothing.
        deepest = re.findall(r"Caused by: ([\w.$]+)(?::\s*([^\n]{0,140}))?", text)
        if deepest:
            name, message = deepest[-1]
        else:
            m = re.search(r"([\w.$]*(?:Exception|Error))(?::\s*([^\n]{0,140}))?", text)
            name, message = (m.group(1), m.group(2) or "") if m else (text.strip()[:140], "")
        causes[(name + ": " + message).strip()] += 1
print("\nFailure causes (deepest cause):")
for cause, n in causes.most_common(15):
    print(f"  {n:5d}  {cause}")
PY
cat "$REPORT"

#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 noether|hibernate|hibernate5|eclipselink" >&2
    exit 2
fi

provider=$1
bench_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$bench_dir"
mvnw="$bench_dir/../../mvnw"
case "$provider" in
    noether)
        profile=noether
        provider_class=io.vidocq.noether.jpa.core.NoetherPersistenceProvider
        ;;
    hibernate)
        profile=hibernate
        provider_class=org.hibernate.jpa.HibernatePersistenceProvider
        ;;
    hibernate5)
        # Jakarta Persistence 3.0 provider: no PersistenceConfiguration (3.2), so the harness hands it a
        # PersistenceUnitInfo through createContainerEntityManagerFactory instead.
        profile=hibernate5
        provider_class=org.hibernate.jpa.HibernatePersistenceProvider
        bootstrap=unit-info
        ;;
    eclipselink)
        profile=eclipselink
        provider_class=org.eclipse.persistence.jpa.PersistenceProvider
        ;;
    *)
        echo "Unknown provider: $provider" >&2
        exit 2
        ;;
esac
bootstrap=${bootstrap:-configuration}

if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin=java
fi

# Each provider builds into its own directory, so its clean never wipes another provider's build.
build_dir="$bench_dir/target/$provider"
# Raw evidence lives outside every provider build directory and is never cleaned by this script.
# Set BENCH_RUN_ID to group several provider runs under one timestamped directory.
run_id=${BENCH_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}
report_dir="$bench_dir/target/bench-reports/$run_id/$provider"
if [ -e "$report_dir" ]; then
    echo "Refusing to overwrite existing benchmark evidence: $report_dir" >&2
    exit 2
fi
mkdir -p "$report_dir"

"$java_bin" -version >"$report_dir/java-version.txt" 2>&1
if "$mvnw" -ntp -P"$profile" -Dbench.build.directory="$build_dir" clean \
    dependency:tree -Dscope=test -DoutputFile="$build_dir/test-dependencies.txt" \
    test-compile dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile="$build_dir/test-classpath.txt" \
    >"$report_dir/maven.log" 2>&1; then
    :
else
    status=$?
    cat "$report_dir/maven.log"
    exit "$status"
fi
cp "$build_dir/test-dependencies.txt" "$build_dir/test-classpath.txt" "$report_dir/"
if [ "$provider" = noether ]; then
    grep -q 'io.vidocq.noether.jpa.bench._NoetherJpaAccess' \
        "$build_dir/test-classes/META-INF/services/io.vidocq.noether.jpa.core.spi.ManagedAccessProvider"
    # Without its pool module, Noether opens one connection per transaction: not the measured configuration.
    grep -q 'noether-jpa-pool-mansart' "$build_dir/test-classpath.txt"
fi
classpath="$build_dir/test-classes:$build_dir/classes:$(cat "$build_dir/test-classpath.txt")"
log="$report_dir/jmh.log"
# The database: in-memory H2 unless BENCH_JDBC_URL (with BENCH_JDBC_DRIVER, BENCH_JDBC_USER, BENCH_JDBC_PASSWORD)
# names another one, e.g. a disposable PostgreSQL server. Every provider of a run must get the same one.
set --
if [ -n "${BENCH_JDBC_URL:-}" ]; then set -- "$@" "-Dbench.jdbc.url=$BENCH_JDBC_URL"; fi
if [ -n "${BENCH_JDBC_DRIVER:-}" ]; then set -- "$@" "-Dbench.jdbc.driver=$BENCH_JDBC_DRIVER"; fi
if [ -n "${BENCH_JDBC_USER:-}" ]; then set -- "$@" "-Dbench.jdbc.user=$BENCH_JDBC_USER"; fi
if [ -n "${BENCH_JDBC_PASSWORD:-}" ]; then set -- "$@" "-Dbench.jdbc.password=$BENCH_JDBC_PASSWORD"; fi
echo "${BENCH_JDBC_URL:-jdbc:h2:mem:jpa_bench (default)}" >"$report_dir/database.txt"
results="$report_dir/jmh.json"
# -foe true: a failing setup/preflight or benchmark makes JMH exit non-zero instead of skipping it.
if "$java_bin" "$@" -Dbench.provider="$provider_class" -Dbench.bootstrap="$bootstrap" -cp "$classpath" org.openjdk.jmh.Main \
    'io.vidocq.noether.jpa.bench.JpaProviderBenchmark.*' -wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 \
    -foe true -prof gc -rf json -rff "$results" >"$log" 2>&1; then
    grep -E '^Benchmark[[:space:]]+Mode|^JpaProviderBenchmark' "$log"
    echo "Raw evidence: $report_dir"
else
    status=$?
    cat "$log"
    echo "Raw evidence: $report_dir" >&2
    exit "$status"
fi

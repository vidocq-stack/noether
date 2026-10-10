#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 mansart|hibernate|eclipselink" >&2
    exit 2
fi

provider=$1
bench_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$bench_dir"
mvnw="$bench_dir/../../mvnw"
case "$provider" in
    mansart)
        profile=mansart
        provider_class=io.vidocq.mansart.jpa.core.MansartPersistenceProvider
        ;;
    hibernate)
        profile=hibernate
        provider_class=org.hibernate.jpa.HibernatePersistenceProvider
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
if [ "$provider" = mansart ]; then
    grep -q 'io.vidocq.mansart.jpa.bench._MansartJpaAccess' \
        "$build_dir/test-classes/META-INF/services/io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider"
fi
classpath="$build_dir/test-classes:$build_dir/classes:$(cat "$build_dir/test-classpath.txt")"
log="$report_dir/jmh.log"
results="$report_dir/jmh.json"
# -foe true: a failing setup/preflight or benchmark makes JMH exit non-zero instead of skipping it.
if "$java_bin" -Dbench.provider="$provider_class" -cp "$classpath" org.openjdk.jmh.Main \
    'io.vidocq.mansart.jpa.bench.JpaProviderBenchmark.*' -wi 3 -i 5 -w 2s -r 2s -f 1 -t 1 \
    -foe true -prof gc -rf json -rff "$results" >"$log" 2>&1; then
    grep -E '^Benchmark[[:space:]]+Mode|^JpaProviderBenchmark' "$log"
    echo "Raw evidence: $report_dir"
else
    status=$?
    cat "$log"
    echo "Raw evidence: $report_dir" >&2
    exit "$status"
fi

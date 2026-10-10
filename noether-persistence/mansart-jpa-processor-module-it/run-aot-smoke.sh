#!/bin/sh
set -eu

module_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
persistence_dir=$(dirname "$module_dir")
mvnw="$persistence_dir/../mvnw"

"$mvnw" -ntp -f "$persistence_dir/pom.xml" -pl mansart-jpa-processor-module-it -am install -DskipTests
"$mvnw" -ntp -f "$module_dir/pom.xml" dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile=target/aot-module-path.txt

classpath=$(cat "$module_dir/target/aot-module-path.txt")
application_jar="$module_dir/target/mansart-jpa-processor-module-it-0.4.0-SNAPSHOT.jar"
module_path="$application_jar:$classpath"
cache="$module_dir/target/mansart-jpa-smoke.aot"
record_log="$module_dir/target/aot-record.log"
use_log="$module_dir/target/aot-cache-use.log"
main="io.vidocq.mansart.jpa.processorit/io.vidocq.mansart.jpa.processorit.AotSmoke"

if [ -n "${JAVA_HOME:-}" ]; then
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin=java
fi

# Remove only this smoke's named cache and logs so a stale cache can never satisfy the checks.
rm -f "$cache" "$record_log" "$use_log"

if "$java_bin" -XX:AOTCacheOutput="$cache" --module-path "$module_path" \
    --add-modules com.h2database --module "$main" >"$record_log" 2>&1; then
    :
else
    status=$?
    cat "$record_log"
    exit "$status"
fi
test -s "$cache"
grep -q 'AOT_SMOKE_OK' "$record_log"

if "$java_bin" -Xlog:aot=info -XX:AOTCache="$cache" --module-path "$module_path" \
    --add-modules com.h2database --module "$main" >"$use_log" 2>&1; then
    :
else
    status=$?
    cat "$use_log"
    exit "$status"
fi
grep -q 'AOT_SMOKE_OK' "$use_log"
grep -qi 'Opened AOT cache' "$use_log"
grep -q 'Using AOT-linked classes: true' "$use_log"
echo "AOT smoke passed: cache $cache; logs $record_log and $use_log"

#!/usr/bin/env bash
# Installs the official Jakarta Persistence TCK into the local Maven repository and unpacks the bundle (the SQL
# scripts the runner applies live only there, not in the Maven artifacts).
#
# The TCK is not on Maven Central. The bundle is downloaded from eclipse.org, its SHA-256 is checked against the one
# published next to it, then its three jars (with their POMs and sources) and the parent POM are installed.
#
# Usage:
#   ./install-tck.sh            install if missing (idempotent)
#   ./install-tck.sh --force    download and install again
#   ./install-tck.sh --verify   only check that everything is in place (exit 1 otherwise)
#
# Overrides: TCK_VER (default 3.2.1), TCK_URL (a file:// URL works for an offline copy of the bundle).

set -euo pipefail
cd "$(dirname "$0")"

TCK_VER="${TCK_VER:-3.2.1}"
TCK_URL="${TCK_URL:-https://download.eclipse.org/jakartaee/persistence/3.2/jakarta-persistence-tck-${TCK_VER}.zip}"
CACHE="../.tck-cache"
BUNDLE_DIR="$CACHE/persistence-tck-$TCK_VER"
M2_TCK="${HOME}/.m2/repository/jakarta/tck"
MVNW="../../mvnw"
MODE="${1:-install}"

installed() {
    [ -f "$M2_TCK/persistence-tck-spec-tests/$TCK_VER/persistence-tck-spec-tests-$TCK_VER.jar" ] \
        && [ -f "$M2_TCK/persistence-tck-common/$TCK_VER/persistence-tck-common-$TCK_VER.jar" ] \
        && [ -f "$M2_TCK/dbprocedures/$TCK_VER/dbprocedures-$TCK_VER.jar" ] \
        && [ -f "$M2_TCK/persistence-tck/$TCK_VER/persistence-tck-$TCK_VER.pom" ] \
        && [ -f "$BUNDLE_DIR/sql/postgresql/postgresql.ddl.persistence.sql" ]
}

case "$MODE" in
    --verify)
        if installed; then echo "Jakarta Persistence TCK $TCK_VER: installed"; exit 0; fi
        echo "Jakarta Persistence TCK $TCK_VER: missing (run ./install-tck.sh)" >&2; exit 1 ;;
    --force) ;;
    install)
        if installed; then echo "Jakarta Persistence TCK $TCK_VER already installed"; exit 0; fi ;;
    *) echo "Usage: $0 [--force|--verify]" >&2; exit 64 ;;
esac

sha256() { if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

mkdir -p "$CACHE"
ZIP="$CACHE/jakarta-persistence-tck-$TCK_VER.zip"
echo ">>> Downloading $TCK_URL"
curl -fsSL -o "$ZIP" "$TCK_URL"
if [[ "$TCK_URL" == http* ]]; then
    expected="$(curl -fsSL "$TCK_URL.sha256" | tr -d '[:space:]' | cut -c1-64)"
    actual="$(sha256 "$ZIP")"
    if [ "$expected" != "$actual" ]; then
        echo "SHA-256 mismatch for $ZIP: expected $expected, got $actual" >&2
        exit 1
    fi
    echo ">>> SHA-256 verified ($actual)"
fi

rm -rf "$BUNDLE_DIR" "$CACHE/persistence-tck"
unzip -q "$ZIP" -d "$CACHE"
mv "$CACHE/persistence-tck" "$BUNDLE_DIR"
A="$BUNDLE_DIR/artifacts"

install_file() { # file pomFile [sources]
    local extra=()
    [ -n "${3:-}" ] && extra=("-Dsources=$3")
    "$MVNW" -q -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
        "-Dfile=$1" "-DpomFile=$2" "${extra[@]}"
}

echo ">>> Installing the TCK artifacts into the local Maven repository"
install_file "$A/persistence-tck-$TCK_VER.pom" "$A/persistence-tck-$TCK_VER.pom"
for artifact in persistence-tck-common persistence-tck-spec-tests dbprocedures; do
    install_file "$A/$artifact-$TCK_VER.jar" "$A/$artifact-$TCK_VER.pom" "$A/$artifact-$TCK_VER-sources.jar"
done
installed && echo ">>> Jakarta Persistence TCK $TCK_VER installed (bundle unpacked in $BUNDLE_DIR)"

#!/bin/zsh
# Publish a signed release that Nbeta's updater picks up:
#   1. bump versionCode/versionName in app/build.gradle.kts and commit
#   2. ./scripts/release.sh            (needs signing/ and the GitHub CLI logged in)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
code=$(sed -nE 's/.*\?: ([0-9]+)$/\1/p' app/build.gradle.kts | head -1)
name=$(sed -nE 's/^ *versionName = "([^"]+)"/\1/p' app/build.gradle.kts | head -1)
[[ -f signing/keystore.properties ]] || { echo "signing/keystore.properties missing: release must be signed with Nbeta's key"; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Commit your changes first"; exit 1; }
./gradlew :app:testDebugUnitTest :app:assembleRelease -q
mkdir -p build/dist
apk="build/dist/nbeta-$code.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
prev=$(git describe --tags --abbrev=0 2>/dev/null || true)
notes=$(git log --format='- %s' ${prev:+$prev..}HEAD)
git tag -a "v$name" -m "Nbeta $name"
git push origin HEAD "v$name"
gh release create "v$name" "$apk" --title "Nbeta $name" --notes "$notes"
echo "Released v$name (build $code)"

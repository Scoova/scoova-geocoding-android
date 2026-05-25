#!/usr/bin/env bash
# Publish a staged maven artifact to the Sonatype Central Portal.
#
# Prereq: `./gradlew publishReleasePublicationToLocalStagingRepository` has
# already run, producing the standard maven layout under
# `sdk-android/build/staging-deploy/`. This script zips that tree (minus
# the `maven-metadata.xml` files, which Central Portal generates server-side)
# and POSTs it to `/api/v1/publisher/upload?publishingType=AUTOMATIC`.
#
# The Central Portal accepts the same Sonatype JIRA-account credentials as
# legacy OSSRH did — Authorization is `Bearer base64(USER:PASS)`. The
# `publishingType=AUTOMATIC` query string tells Sonatype to auto-promote
# after validation (vs `USER_MANAGED` which leaves it staged for review).
#
# Env required:
#   OSSRH_USERNAME    Sonatype Central Portal username
#   OSSRH_PASSWORD    Sonatype Central Portal password / user-token
#
# Optional:
#   STAGING_DIR       default: build/staging-deploy
#   ARTIFACT_NAME     default: scoova-monitor-android-<version>
#   PUBLISHING_TYPE   AUTOMATIC (default) | USER_MANAGED
#
# Exits 0 on accepted upload, 1 otherwise. The deployment id is printed so
# callers can poll status if running USER_MANAGED.

set -euo pipefail

: "${OSSRH_USERNAME:?OSSRH_USERNAME not set}"
: "${OSSRH_PASSWORD:?OSSRH_PASSWORD not set}"

cd "$(dirname "$0")/.."

STAGING_DIR="${STAGING_DIR:-build/staging-deploy}"
PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"

if [[ ! -d "$STAGING_DIR" ]]; then
  echo "✗ staging dir not found: $STAGING_DIR" >&2
  echo "  run \`./gradlew publishReleasePublicationToLocalStagingRepository\` first" >&2
  exit 2
fi

# Resolve the published version from the staged tree. The layout is:
#   <STAGING_DIR>$STAGING_DIR/info/scoo-va/scoova-geocoding-android/<version>/sdk-<version>.aar
VERSION=$(ls "$STAGING_DIR"$STAGING_DIR/info/scoo-va/scoova-geocoding-android/ 2>/dev/null | sort -V | tail -1)
if [[ -z "$VERSION" ]]; then
  echo "✗ couldn't find a published version under $STAGING_DIR$STAGING_DIR/info/scoo-va/scoova-geocoding-android/" >&2
  exit 3
fi

ARTIFACT_NAME="${ARTIFACT_NAME:-scoova-geocoding-android-$VERSION}"
BUNDLE="build/$ARTIFACT_NAME.zip"

echo "Staging dir : $STAGING_DIR"
echo "Version     : $VERSION"
echo "Bundle      : $BUNDLE"

rm -f "$BUNDLE"
# `cd $STAGING_DIR` so paths in the zip are relative to the maven root (the
# Central Portal expects `com/scoova/monitor/sdk/...` at the top level).
# Exclude maven-metadata.xml — Central Portal rejects bundles that include it.
( cd "$STAGING_DIR" && zip -r -q -X "$OLDPWD/$BUNDLE" . -x '*/maven-metadata.xml*' )

echo
echo "Bundle contents (first 12 entries):"
unzip -l "$BUNDLE" | head -16

# Bearer auth — Sonatype Central Portal accepts `Bearer base64(user:pass)`.
TOKEN=$(printf '%s:%s' "$OSSRH_USERNAME" "$OSSRH_PASSWORD" | base64 | tr -d '\n')

echo
echo "Uploading to Central Portal (publishingType=$PUBLISHING_TYPE) ..."

HTTP_OUT=$(mktemp)
HTTP_CODE=$(curl -sS \
  -o "$HTTP_OUT" -w "%{http_code}" \
  -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -F "bundle=@$BUNDLE" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=$PUBLISHING_TYPE&name=$ARTIFACT_NAME")

BODY=$(cat "$HTTP_OUT" | head -c 4096)
rm -f "$HTTP_OUT"

if [[ "$HTTP_CODE" =~ ^2[0-9][0-9]$ ]]; then
  echo "✓ Accepted. Deployment id: $BODY"
  echo
  if [[ "$PUBLISHING_TYPE" == "AUTOMATIC" ]]; then
    echo "Central Portal will validate + auto-promote. Track at:"
    echo "  https://central.sonatype.com/publishing/deployments"
    echo "Artifact will be queryable at:"
    echo "  https://repo1.maven.org/maven2/info/scoo-va/scoova-geocoding-android/$VERSION/"
    echo "(usually live within 15-30 min after AUTOMATIC promotion)"
  else
    echo "Bundle is staged for manual review at:"
    echo "  https://central.sonatype.com/publishing/deployments"
  fi
  exit 0
fi

echo "✗ Upload failed: HTTP $HTTP_CODE" >&2
echo "$BODY" >&2
exit 1

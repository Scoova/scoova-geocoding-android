#!/usr/bin/env bash
# Publish a staged maven artifact to the Sonatype Central Portal.
# Env required: OSSRH_USERNAME, OSSRH_PASSWORD
# Optional: STAGING_DIR (default build/staging-deploy), PUBLISHING_TYPE (AUTOMATIC|USER_MANAGED)

set -euo pipefail

: "${OSSRH_USERNAME:?OSSRH_USERNAME not set}"
: "${OSSRH_PASSWORD:?OSSRH_PASSWORD not set}"

cd "$(dirname "$0")/.."

STAGING_DIR="${STAGING_DIR:-build/staging-deploy}"
PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"
ARTIFACT_DIR="info/scoo-va/scoova-geocoding-android"

if [[ ! -d "$STAGING_DIR" ]]; then
  echo "✗ staging dir not found: $STAGING_DIR" >&2
  exit 2
fi

VERSION=$(find "$STAGING_DIR/$ARTIFACT_DIR/" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; 2>/dev/null | sort -V | tail -1)
if [[ -z "$VERSION" ]]; then
  echo "✗ couldn't find a published version under $STAGING_DIR/$ARTIFACT_DIR/" >&2
  echo "  contents:" >&2
  ls -R "$STAGING_DIR" >&2 || true
  exit 3
fi

ARTIFACT_NAME="${ARTIFACT_NAME:-scoova-geocoding-android-$VERSION}"
BUNDLE="build/$ARTIFACT_NAME.zip"

echo "Staging dir : $STAGING_DIR"
echo "Version     : $VERSION"
echo "Bundle      : $BUNDLE"

rm -f "$BUNDLE"
( cd "$STAGING_DIR" && zip -r -q -X "$OLDPWD/$BUNDLE" . -x '*/maven-metadata.xml*' )

echo
echo "Bundle contents (first 12 entries):"
unzip -l "$BUNDLE" | awk 'NR<=16'

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
  echo "Track at: https://central.sonatype.com/publishing/deployments"
  echo "Artifact will be queryable at: https://repo1.maven.org/maven2/info/scoo-va/scoova-geocoding-android/$VERSION/"
  exit 0
fi

echo "✗ Upload failed: HTTP $HTTP_CODE" >&2
echo "$BODY" >&2
exit 1

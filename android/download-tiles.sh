#!/usr/bin/env bash
# Download offline tile pack from GitHub Releases.
# Usage: ./download-tiles.sh [--force]
#
# The mbtiles file (~58 MB) is hosted as a GitHub Release asset
# instead of being tracked in Git LFS to keep the repo lightweight.

set -euo pipefail

RELEASE_TAG="tiles-v1"
REPO="SebastienBinet/NidsDePoule"
ASSET_NAME="montreal_tiles.mbtiles"
DEST="app/src/main/assets/${ASSET_NAME}"

cd "$(dirname "$0")"

if [[ -f "$DEST" && "${1:-}" != "--force" ]]; then
    echo "✓ ${ASSET_NAME} already present ($(du -h "$DEST" | cut -f1) — use --force to re-download)"
    exit 0
fi

URL="https://github.com/${REPO}/releases/download/${RELEASE_TAG}/${ASSET_NAME}"

echo "Downloading ${ASSET_NAME} from release ${RELEASE_TAG}..."
curl -fSL --retry 4 --retry-delay 2 -o "$DEST" "$URL"
echo "✓ Saved to ${DEST} ($(du -h "$DEST" | cut -f1))"

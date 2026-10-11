#!/bin/bash
# Bring changes made by Chief (branch atlas/live) into the working branch and into the scratch source tree.
# Usage: bash tools/syncdown.sh [path-to-scratch-source-tree]
set -e
cd "$(dirname "$0")/.."
git fetch -q origin atlas/live
git merge --no-edit origin/atlas/live
DEST="${1:-}"
if [ -n "$DEST" ]; then mkdir -p "$DEST" && cp -a project/. "$DEST"/ && echo "Copied project/ into $DEST"; fi

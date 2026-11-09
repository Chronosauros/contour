#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "=== Packaging Contour for macOS ==="

rm -rf "$DIR/dist/mac/Contour.app"
rm -f "$DIR/dist/mac/Contour-1.6.4.dmg"

cd "$DIR/android"
./gradlew :desktop:packageMacApp
./gradlew :desktop:packageMacDmg

echo ""
echo "Packaging complete!"
echo "Native macOS Application: dist/mac/Contour.app"
echo "macOS DMG Installer:      dist/mac/Contour-1.6.4.dmg"

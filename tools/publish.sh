#!/usr/bin/env bash
# Builds both flavors, tags the current commit and publishes the GitHub release.
#
#   tools/publish.sh              # build, tag, release
#   tools/publish.sh --unsigned   # build without signing material (never for a real release)
#
# The version comes from `appVersion` in app/build.gradle.kts, the tag is `v` plus that
# string, and the notes come from docs/releases/<version>.md when the file exists.
# Signing works exactly as in tools/release.sh: keystore.properties or the RIVO_* variables.
#
# Assets are copied out of the Gradle output into build/release under their published names
# before upload. `gh release create file#name` does not rename on every gh version, so the
# copy is what guarantees the names users see.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if ! command -v gh >/dev/null 2>&1; then
    echo "error: gh is not installed" >&2
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "error: gh is not authenticated; run 'gh auth login'" >&2
    exit 1
fi

version="$(sed -n 's/^val appVersion = "\(.*\)"[[:space:]]*$/\1/p' app/build.gradle.kts | head -n 1)"
if [[ -z "$version" ]]; then
    echo "error: appVersion not found in app/build.gradle.kts" >&2
    exit 1
fi
tag="v$version"
notes="docs/releases/$version.md"
out="build/release"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "warning: uncommitted changes; the tag will point at the last commit, not this tree" >&2
fi

echo "==> Rivo $version"

if [[ "${1:-}" == "--unsigned" ]]; then
    bash tools/release.sh fast --unsigned
    bash tools/release.sh ytdlp --unsigned
else
    bash tools/release.sh fast
    bash tools/release.sh ytdlp
fi

mkdir -p "$out"
for flavor in fast ytdlp; do
    for apk in "app/build/outputs/apk/$flavor/release/"*.apk; do
        [[ -f "$apk" ]] || continue
        target="$out/rivo-$version-$flavor.apk"
        cp "$apk" "$target"
        echo "packed: $target"
    done
done

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    echo "==> tag $tag already exists"
else
    git tag -a "$tag" -m "Rivo $version"
    echo "==> tag $tag created"
fi
git push origin main
git push origin "$tag"

if gh release view "$tag" >/dev/null 2>&1; then
    echo "==> release $tag exists; replacing assets"
    gh release upload "$tag" "$out"/*.apk --clobber
else
    if [[ -f "$notes" ]]; then
        gh release create "$tag" "$out"/*.apk \
            --title "Rivo $version" --notes-file "$notes" --latest
    else
        echo "note: $notes is missing; GitHub will generate the notes" >&2
        gh release create "$tag" "$out"/*.apk \
            --title "Rivo $version" --generate-notes --latest
    fi
fi

gh release view "$tag" --json url,isDraft,isPrerelease,assets \
    --jq '"release: \(.url)\ndraft: \(.isDraft)  prerelease: \(.isPrerelease)", (.assets[] | "asset: \(.name) (\(.size) bytes, \(.state))")'

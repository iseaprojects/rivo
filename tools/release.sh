#!/usr/bin/env bash
# Builds a signed release APK.
#
# Signing material never lives in the repository. Put it in `keystore.properties` at
# the repository root (ignored by git) and this script exports it for Gradle:
#
#   storeFile=release.jks
#   storePassword=...
#   keyAlias=rivo
#   keyPassword=...
#
# `storeFile` is relative to the repository root.
#
# Environment variables win over the file, so CI can inject RIVO_KEYSTORE and friends
# instead. With neither present the build still runs but the APK stays unsigned.
#
#   tools/release.sh             # fast flavor, signed when material is available
#   tools/release.sh ytdlp       # yt-dlp flavor
#   tools/release.sh fast --unsigned
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

flavor="${1:-fast}"
case "$flavor" in
    fast|ytdlp) ;;
    *) echo "usage: $0 [fast|ytdlp] [--unsigned]" >&2; exit 2 ;;
esac

if [[ "${2:-}" == "--unsigned" ]]; then
    unset RIVO_KEYSTORE RIVO_KEYSTORE_PASSWORD RIVO_KEY_ALIAS RIVO_KEY_PASSWORD
fi

properties="keystore.properties"
if [[ -f "$properties" ]]; then
    read_property() {
        sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$properties" | tail -n 1 | tr -d '\r'
    }
    store="$(read_property storeFile)"
    if [[ -n "$store" && "$store" != /* && ! "$store" =~ ^[A-Za-z]: ]]; then
        store="$root/$store"
    fi
    [[ -n "$store" ]] && export RIVO_KEYSTORE="${RIVO_KEYSTORE:-$store}"
    [[ -n "$RIVO_KEYSTORE" ]] || { echo "warning: $properties has no storeFile" >&2; }
    store_password="$(read_property storePassword)"
    key_alias="$(read_property keyAlias)"
    key_password="$(read_property keyPassword)"
    [[ -n "$store_password" ]] && export RIVO_KEYSTORE_PASSWORD="${RIVO_KEYSTORE_PASSWORD:-$store_password}"
    [[ -n "$key_alias" ]] && export RIVO_KEY_ALIAS="${RIVO_KEY_ALIAS:-$key_alias}"
    [[ -n "$key_password" ]] && export RIVO_KEY_PASSWORD="${RIVO_KEY_PASSWORD:-$key_password}"
fi

task=":app:assemble$(echo "${flavor:0:1}" | tr '[:lower:]' '[:upper:]')${flavor:1}Release"
echo "==> ./gradlew $task"
./gradlew "$task" --console=plain

apk="app/build/outputs/apk/$flavor/release"
ls -1 "$apk"/*.apk | while read -r file; do
    if command -v du >/dev/null 2>&1; then
        echo "artifact: $file ($(du -h "$file" | cut -f1))"
    else
        echo "artifact: $file"
    fi
done

if [[ -z "${RIVO_KEYSTORE:-}" ]]; then
    echo "note: no signing material set; the APK above is unsigned."
fi

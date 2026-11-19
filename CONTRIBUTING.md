# Contributing to Rivo

Rivo is a native Android client for YouTube built on the menu model of
[yt-x](https://github.com/Benexl/yt-x). Bug reports, build fixes and new declarative
handlers are welcome.

## Build

Requirements: JDK 17, Android SDK 37 (Build Tools 36 or 37), Gradle via the committed
wrapper. `local.properties` is the only machine-specific file.

```bash
./gradlew :app:assembleFastDebug
./gradlew :app:assembleYtdlpDebug
```

The `fast` flavor uses NewPipeExtractor only; `ytdlp` adds youtubedl-android (CPython and
aria2), which is why its APK is much larger.

## Publishing

The repository is self-contained; `main` holds the import commit. To publish a mirror and
push it:

```bash
gh repo create iseaprojects/rivo --public --source . --remote origin --push
```

A release is tagged with the exact string in `app/build.gradle.kts` (`appVersion`), so the
in-app update checker compares like for like. Write the notes in `docs/releases/<version>.md`
first, then let `tools/publish.sh` do the rest:

```bash
tools/publish.sh
```

The script signs and builds both flavors, copies the APKs to `build/release` as
`rivo-<version>-fast.apk` and `rivo-<version>-ytdlp.apk`, creates the tag if it is missing,
pushes `main` and the tag, and creates or updates the GitHub release. Without a notes file it
falls back to GitHub's generated notes.

Signing keys stay in `keystore.properties` or the `RIVO_*` environment variables; both are
excluded from git.

## Before sending a change

Run the same gates CI runs:

```bash
./gradlew :app:testFastDebugUnitTest :app:testYtdlpDebugUnitTest
./gradlew :app:lintFastDebug
./gradlew :app:assembleFastDebug :app:assembleYtdlpDebug
python tools/check_strings.py
```

`check_strings.py` must report `0 missing`. New user-visible text belongs in
`app/src/main/assets/extensions/langs/{en,es,pt-BR}.lang.json`; Android system surfaces
(labels, notification text) live in `app/src/main/res/values*/strings.xml`.

## Rules that are not negotiable

- **Extensions are data, never code.** A picked folder may only name handlers the app
  already ships with (D43). Do not add a shell, JavaScript or Lua evaluator to the extension
  loader.
- **No DRM, age-gate, paywall or geo-restriction bypass.** Those states are reported as typed
  errors (D2/D3). Cookies are for the signed-in user's own account, not impersonation.
- **The update checker never installs an APK.** It opens the release page and stops (D45).
- **No silent self-update path and no telemetry.** The app talks to YouTube/the configured
  extractor, GitHub releases, and SponsorBlock when enabled; nothing else.

## Style

- Kotlin, 4-space indent, no wildcard imports, no `TODO()`/`FIXME` left in shipped code.
- `core/` stays free of Compose and Android UI imports; parsing and menu logic must remain
  JVM-testable.
- New behaviour gets a unit test next to the class it covers (`app/src/test`).
- User-visible strings go through the lang packs, not literals in Compose.
- Configuration keys keep the `CONFIG_*` spelling so an upstream `config` file still imports.

## License

Rivo is MIT. By contributing you agree that your changes are licensed under the same terms,
and the upstream yt-x copyright notice in [LICENSE](LICENSE) is preserved.

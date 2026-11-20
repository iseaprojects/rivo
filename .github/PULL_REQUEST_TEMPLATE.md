## What changes

## Why

## Checks

- [ ] `./gradlew :app:testFastDebugUnitTest :app:testYtdlpDebugUnitTest`
- [ ] `./gradlew :app:lintFastDebug`
- [ ] `./gradlew :app:assembleFastDebug :app:assembleYtdlpDebug`
- [ ] `python tools/check_strings.py` reports `0 missing`
- [ ] New user-visible strings exist in `en`, `es` and `pt-BR`
- [ ] No `TODO()` or `FIXME` left in shipped code

## Notes for reviewers

Extractor changes: which backend, and what happens when the other one handles the URL.
Config changes: whether an upstream `config` still imports unchanged.

# AI Instructions

## Context

Télé is an IPTV player for Android TV / Google TV, driven by the remote. Channels come from a Dispatcharr server, an Xtream Codes account or an M3U playlist, with an EPG guide (Dispatcharr grid or XMLTV). See `README.md` for features and architecture.

## Modification Process

For each modification to the project, perform the following checks and adjustments in order:

### 1. Tests

- Write JVM unit tests (`app/src/test`) for the modified logic: parsers, EPG resolution, URL handling, clients' pure helpers
- Run only the tests related to the change (e.g. `./gradlew testDebugUnitTest --tests 'com.iptv.player.data.parser.*'`); the CI runs the full suite
- Android framework classes are stubbed in unit tests: keep logic testable by isolating it from `Context`, `android.util.*` and Compose

### 2. Security Audit

- Credentials (API keys, Xtream passwords, M3U links with tokens) stay on the device: never log them, never display them (`SourceConfig.displayUrl`)
- The Dispatcharr API key is only sent to the Dispatcharr host, never to the providers it redirects to (`TvPlayerManager` network interceptor)
- Treat every server response (JSON, M3U, XMLTV) as untrusted: no crash on malformed or huge input
- Review new dependencies for known vulnerabilities

### 3. Optimizations and Bug Fixes

- Playlists and guides can be huge (50,000+ entries, tens of MB): stream them, never load them fully in memory
- Network and parsing run off the main thread (`Dispatchers.IO` / `Dispatchers.Default`)
- Check focus handling with the remote (D-pad) on every screen change
- Verify error handling: user-facing errors from the data layer are `UiText` (`UserFacingException`, `failure(...)`)

### 4. Translations

- All code, comments and logs are in English
- Every user-visible text is a string resource: English in `app/src/main/res/values/strings.xml`, French in `app/src/main/res/values-fr/strings.xml`, with the same keys
- Use `stringResource` / `pluralStringResource` in composables, `UiText` outside the UI, `<plurals>` for counts (French: `one`, `many`, `other`)
- `./gradlew lintDebug` must not report missing translations

### 5. Pre-commit Execution and Fixes

- Run `prek run` and fix all reported issues
- Re-run until all hooks pass, then once with `--all-files`
- Run `./gradlew testDebugUnitTest lintDebug` and fix lint errors

### 6. README Screenshots

- After a notable visual change, update `docs/screenshots/` and the README images

## Recommended Execution Order

1. Develop the modification
2. Write and validate tests
3. Perform security audit
4. Optimize and fix bugs
(following tasks should be run at the end of all tasks, ask if there is anything to do else, before running them)
5. Update translations
6. Run pre-commit, tests and lint, and fix issues until all checks pass
7. Update the README screenshots after a notable visual change

## Commits and Releases

- Commit messages follow Conventional Commits (checked by the `committed` hook): `feat` and `fix` trigger a release, `perf` too; `build`, `chore`, `ci`, `docs`, `refactor`, `style` and `test` do not
- Releases are automatic on `main` (semantic-release): never edit `appVersion` in `gradle.properties` or `CHANGELOG.md` by hand
- The release APK is signed in CI with the key stored in the repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`)

## Pushing

- After a push, offer to watch the CI pipeline (`gh run watch`) and fix any failure it reports

## Project References

- Language: Kotlin, Jetpack Compose for TV, AndroidX Media3 (ExoPlayer), OkHttp, Coil
- Build: Gradle (wrapper), Android Gradle Plugin, minSdk 26, JDK from `mise.toml`
- Tests: JUnit 4 (JVM unit tests)
- Lint: Android lint, prek (pre-commit hooks: yamllint, zizmor, committed)
- Tooling: mise (`mise.toml`, 7-day minimum release age), Node only for semantic-release
- Format: UTF-8
- Localization: English (default), French (`values-fr`)

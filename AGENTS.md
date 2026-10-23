# AGENTS.md - Tempus (Android Subsonic Client)

## Project Overview
**Tempus** is an open-source Android music client for Subsonic servers. Two product flavors:
- **tempus** (`com.eddyizm.tempus`) - GitHub release with Android Auto & Chromecast
- **degoogled** (`com.eddyizm.degoogled.tempus`) - IzzyOnDroid/F-Droid release, no Google services

## Build & Development

### Toolchain
- **Gradle**: 9.8.0 (wrapper)
- **AGP**: 9.2.1
- **Kotlin**: 2.2.20
- **Java**: 21 (via Foojay toolchain resolver in `settings.gradle`)
- **compileSdk/targetSdk**: 37
- **minSdk**: 24

### Key Commands
```bash
# Build both release APKs (as in CI)
./gradlew assembleTempusRelease
./gradlew assembleDegoogledRelease

# Build debug APKs (for testing)
./gradlew assembleTempusDebug
./gradlew assembleDegoogledDebug

# Run unit tests (per flavor)
./gradlew testTempusDebugUnitTest
./gradlew testDegoogledDebugUnitTest

# Run lint (per flavor/variant)
./gradlew lintTempusDebug
./gradlew lintTempusRelease
./gradlew lintDegoogledDebug
./gradlew lintDegoogledRelease

# Run all lint
./gradlew lint

# Run instrumented tests (requires device/emulator)
./gradlew connectedTempusDebugAndroidTest
./gradlew connectedDegoogledDebugAndroidTest

# Clean
./gradlew clean
```

### Build Notes
- Uses **version catalog** (`gradle/libs.versions.toml`) for dependency management
- **ProGuard/R8** enabled for release builds (`minifyEnabled`, `shrinkResources`)
- **Foojay resolver** downloads JDK 21 automatically (no system Java needed)
- Generates **universal APKs** (all ABIs in one APK) via `splits.abi`
- Room schema export to `app/schemas/`
- Mapping files generated for release builds (used for crash deobfuscation)

## Local APK preference (important)

Unless the user explicitly asks otherwise, **always hand over the `degoogled` release APK**, not a debug build and not the `tempus` flavor. That is the build the user installs on their phone.

```bash
# Build the release (R8, unsigned):
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SDK_ROOT=/tmp/opencode/android-sdk \
  ./gradlew :app:assembleDegoogledRelease

# Sign it for local installation. The repo has no release signingConfig; CI signs
# with repository secrets, so locally use the Android debug keystore.
BT=$(ls -d "$ANDROID_HOME"/build-tools/36.1.0* | tail -1)
cd app/build/outputs/apk/degoogled/release
"$BT/apksigner" sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app-degoogled-release.apk \
  app-degoogled-release-unsigned.apk
"$BT/apksigner" verify app-degoogled-release.apk

# Install over the previous local build:
adb install -r app/build/outputs/apk/degoogled/release/app-degoogled-release.apk
```

Notes:
- Package is `com.eddyizm.degoogled.tempus`; it installs alongside a `.debug` build. A debug-key-signed APK is for local testing only, not distribution. Uninstall an officially signed degoogled release first if present.
- In this sandbox the Nix Android SDK is read-only and lacks build-tools 36.1.0. Point `ANDROID_HOME`/`ANDROID_SDK_ROOT` at the writable overlay `/tmp/opencode/android-sdk` (symlinks to the Nix SDK plus build-tools 36.1.0). If `/tmp` was cleared, recreate that overlay or use any writable SDK with build-tools 36.1.0.
- A clean release build depends on `buildEternalJni` and needs Rust, `cargo-ndk`, and NDK `28.2.13676358`. If `app/build/generated/eternalJni/*/libeternal_jni.so` already exists and `rust/` is unchanged, the task is up to date.
- Before handing over, run `./gradlew :app:testDegoogledDebugUnitTest` (and ideally `:app:lintDegoogledDebug`), then build/sign the release.

## Architecture
- **Language**: Kotlin (primary), some Java
- **Architecture**: MVVM-ish with Media3/ExoPlayer for playback
- **Key libs**: Room (DB), Retrofit/OkHttp (network), Media3/ExoPlayer (media), Glide (images), Material3
- **Flavors**: `tempus` (Media3 Cast) vs `degoogled` (no Cast)
- **Main package**: `com.eddyizm.tempus` (namespace), app IDs differ by flavor

### Crossfade and Eternal
- Local music crossfade lives in `service/playback/CrossfadeEngine.kt` and uses **two rotating ExoPlayers**. At the fade start the incoming player is prepared at position 0 and **published to the MediaSession**; the outgoing player fades out hidden. Never reintroduce an end-of-track seek or a same-track handoff — that was the source of audible seams.
- Eternal Jukebox snapshots the live session player. Starting Eternal must call `BaseMediaService.onEternalPreparationStarted()` so the crossfade engine cancels/stops scheduling until the session switches; cancel/fail calls `onEternalPreparationEnded()`.
- Returning from Eternal prepares the normal ExoPlayer silently while Eternal is still audible, then equal-power blends back (`EternalPlaybackController.restoreExoOnMain`). Do not tear down the Eternal output before the normal player is playing.
- Each local player owns its `FadeAudioProcessor`, ReplayGain processor and parametric EQ. Both share one audio session ID so the system equalizer survives a role swap; `ReplayGainUtil.setActiveProcessor(...)` must follow the session swap. Audio focus is managed manually by `LocalAudioFocus`/`CrossfadeEngine` (local players are built with `handleAudioFocus = false`).

## Testing
- **Unit tests**: JUnit 4 + Mockito + MockK (run via `test*UnitTest` tasks)
- **Instrumented tests**: AndroidX Test + Espresso (run via `connected*AndroidTest`)
- **No CI test execution** currently - tests run locally only
- Test configs in `app/build.gradle`: `testOptions.unitTests.returnDefaultValues = true`
- Crossfade timing/curve tests live in `app/src/test/.../service/playback/CrossfadeTimingTest.kt`; the fade audio processor is covered by `FadeAudioProcessorTest`.

## CI / Release Process
- **Prerelease** (`.github/workflows/github_prerelease.yml`): Triggered on `v*-dev*` tags, builds debug APKs, signs with release keystore
- **Release** (`.github/workflows/github_release.yml`): Triggered on `v*` tags (non-dev), builds both release flavors, signs, creates GitHub release with APKs + ProGuard mapping files (gzipped)
- **Signing**: Uses `r0adkll/sign-android-release` action with keystore from secrets
- **Java**: Zulu JDK 21 in CI

## Key Files
- `app/build.gradle` - Main build config, flavors, dependencies
- `gradle/libs.versions.toml` - Version catalog (all versions here)
- `settings.gradle` - Plugin management, Foojay resolver
- `app/proguard-rules.pro` - ProGuard rules (Retrofit, exceptions, line numbers)
- `bin/build.sh` - FFmpeg AAR build script (for custom decoder)
- `app/src/main/java/com/eddyizm/tempus/service/playback/` - Dual-player crossfade engine, local player bundle, audio focus
- `app/src/main/java/com/eddyizm/tempus/eternal/EternalPlaybackController.kt` - Eternal preparation/entry/exit hand-offs

## Contributing Notes (from CONTRIBUTING.md)
- PRs against `development` branch
- Include before/after screenshots for UI changes
- Tests exist, but CI does not execute them; run the flavor-specific unit-test tasks locally.
- Update docs (`USAGE.md`) with changes
- Crash logs are obfuscated in release; deobfuscate with mapping.txt:
  ```bash
  $ANDROID_HOME/cmdline-tools/latest/bin/retrace app/build/outputs/mapping/tempusRelease/mapping.txt stack_error_transcript.txt
  ```

## Common Tasks

| Task | Command |
|------|---------|
| Build debug APK (tempus) | `./gradlew assembleTempusDebug` |
| Build release APK (both) | `./gradlew assembleTempusRelease assembleDegoogledRelease` |
| Build + sign local degoogled release | see **Local APK preference** above |
| Run unit tests (tempus) | `./gradlew testTempusDebugUnitTest` |
| Run lint (all) | `./gradlew lint` |
| Clean build | `./gradlew clean` |
| Generate lint baseline | `./gradlew updateLintBaseline` |

## Gotchas
- **Two app IDs**: `com.eddyizm.tempus` vs `com.eddyizm.degoogled.tempus` - debug adds `.debug` suffix
- **Media3 Cast** only in `tempus` flavor (`tempusImplementation libs.media3.cast`)
- **FFmpeg decoder** is a local AAR (`libs/lib-decoder-ffmpeg-release.aar`), built separately via `bin/build.sh`
- **Room schemas** committed to `app/schemas/` - update on schema changes
- **No tests in CI** - run locally before PR
- **Java 21 required** (managed by Foojay in Gradle)
- **Local release APKs are debug-key signed** (see **Local APK preference**); they are for installation/testing, not distribution. The `tempus` flavor is not the user's default test build.
- **Crossfade is local-only**: radio, podcasts, audiobooks, Cast, UPnP and Eternal keep the normal transition; the end-of-track sleep timer suppresses a new fade but lets an in-flight one finish.

## Proguard mapping for Release

To deobfuscate crash logs:
```bash
$ANDROID_HOME/cmdline-tools/tools/bin/retrace app/build/outputs/mapping/tempusRelease/mapping.txt stack_error_transcript.txt
```

### Development Guidelines

1. **Testing**: Manual testing on physical devices is required - avoid VM testing for network/foreground service features
2. **Database Integrity**: `isDownloaded()` must stay authoritative (Media3 index + Room DB row); external deletes must purge the Media3 index via `DownloaderManager.remove(...)`.
3. **Notification UX**: Single notification with correct actions, tap opens app to queue fragment
4. **Cross-Flavour**: Test on both `tempus` and `degoogled` flavors as they have different capabilities

Skills provide specialized instructions and workflows for specific tasks.
Use the skill tool to load a skill when a task matches its description.
<available_skills>
  <skill>
    <name>customize-opencode</name>
    <description>Use ONLY when the user is editing or creating opencode's own configuration: opencode.json, opencode.jsonc, files under .opencode/, or files under ~/.config/opencode/. Also use when creating or fixing opencode agents, subagents, skills, plugins, MCP servers, or permission rules. Do not use for the user's own application code, or for any project that is not configuring opencode itself.</description>
    <location>&lt;built-in&gt;</location>
  </skill>
</available_skills>

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

# BlockVerse — Native Android block puzzle

**Source version: 1.4.0 (versionCode 5)**  
Native Kotlin · Android Canvas · local SQLite · offline-only

BlockVerse is an original single-player block-placement puzzle. The application manifest does not request `INTERNET`; the project contains no backend, account, advertising, analytics, or network integration.

## Included gameplay

- 8×8 board, three-piece tray, 15 catalogue shapes, no-rotation placement.
- Horizontal and vertical line clearing, simultaneous clears, combo scoring, best-score tracking.
- Classic, Zen, Journey, Time Attack, Daily, and Challenge modes.
- Daily puzzle seed based on the local calendar date.
- Prism pieces that clear a 3×3 area around the placement anchor.
- Energy meter with selectable row and column blasts.
- One-step undo, legal-move hint, game-over detection, and Daily/Challenge completion states.
- Tap-to-select and drag-and-drop controls with board-aligned placement preview.
- Local sound effects, optional haptics, dark/light themes, local statistics and achievements.

## Corrective work in version 1.4.0

### Game engine and persistence
- Default new-game random sequences now use a per-instance time-derived seed; Daily puzzles remain reproducible because their date seed is explicit and persisted PRNG state restores exactly.
- Save import remains bounded and atomic, with strict validation for schema version, board dimensions, palette, piece catalogue, IDs, tray contents, counters, mode, score plausibility, random state, and terminal-state consistency.
- Score arithmetic saturates rather than wrapping. Journey level calculation is bounded and constant-time.
- Undo restores the exact random sequence and validates snapshots.
- SQLite writes for the game payload and active session are checked inside one transaction; a partial write is rolled back.
- Session identifiers are constrained to a safe character set. Game completion is idempotent and statistics counters saturate rather than overflowing.
- Legacy database timer migration validates the old setting and safely clamps its range.
- Oversized database payloads are detected using SQLite length metadata before the text is materialized into a Kotlin string, then cleared with the active session.
- Persistence failures are logged and do not falsely advertise a Continue save.

### UI, accessibility, and lifecycle
- Light-theme secondary text now has stronger contrast; status/navigation bar icon and background appearance follow the selected theme where Android supports it.
- Drag preview follows the exact board-cell placement footprint and indicates whether the placement is valid.
- Multi-touch interruptions cancel the whole gesture so a trailing pointer-up cannot accidentally place a piece.
- Virtual accessibility nodes expose screen-relative bounds, accessibility focus actions, modes, controls, tray pieces, and board cells.
- Time Attack finalization is guarded against repeated timer completion callbacks.
- Source version and Android versionCode are now 1.4.0 and 5.

## Verification completed in this environment

- 28/28 pure Kotlin engine test methods passed using a temporary local JUnit-compatible assertion harness.
- Randomized play/save/restore/undo simulation passed across 24 deterministic seeds.
- SQLite migration and statistics SQL were exercised with Python's SQLite engine, including invalid timer values and integer saturation.
- Android XML resources and manifest parse successfully.
- Kotlin source parsing reported no Kotlin syntax diagnostics. Android symbol resolution could not complete because the Android SDK is not installed in this environment.
- The manifest contains no `INTERNET` permission; no network or analytics dependency was added.
- Added eight Android instrumentation tests for SQLite save/idempotency, v1 database migration, invalid legacy timer recovery, statistics overflow protection, oversized-save recovery, transactional save validation, clearing saved sessions, and accessibility node exposure. These tests are included but have not been run on Android.

These checks are not an Android Gradle build, Android lint run, instrumentation execution, APK build, or physical-device test.

## Open release gates — not completed

- Android Gradle sync/compile and Android lint against the actual Android SDK.
- Run the 28 unit tests through Gradle/JUnit and the eight instrumentation tests on emulator/device.
- Validate SQLite migration and corruption recovery against Android's SQLite runtime.
- Test Time Attack during backgrounding, screen lock, Activity recreation, process death, and timeout races.
- Test TalkBack touch exploration, focus traversal, virtual-node actions, and contrast on real Android versions.
- Test 360×640, 393×873, 432×960, and tablet-sized displays, gesture navigation, three-button navigation, cutouts, and edge-to-edge behavior.
- Profile frame pacing, memory, battery, and touch response on low-end and 60/90/120 Hz devices.
- Review drag-preview visuals, animation quality, and sound/haptic behavior on physical devices.
- Generate, install, and verify a signed release APK.

Do not call this release production-ready until these gates pass.

## Deliberate product-scope limits

- Journey is score-threshold progression, not an authored campaign map.
- Zen currently uses the shared placement rules; it is not a separate endless/relaxed ruleset.
- Daily and Challenge use line-clear objectives rather than a large authored puzzle library.
- No online leaderboard, multiplayer, cloud sync, ads, account system, or background music is included. These are intentionally absent to preserve the offline-only product boundary; adding online services would change that requirement.
- Offline save validation detects malformed and implausible state, but cannot provide server-grade anti-cheat against a device owner who can modify a local app or database.

## Open the project

1. Install Android Studio, Android SDK Platform 35, and JDK 17.
2. Open the `BlockVerse` project folder.
3. Allow Android Studio to use a compatible Gradle 8.9+ installation with Android Gradle Plugin 8.7.3.
4. Run the checks below before any release build:

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
gradle :app:lintDebug
```

`connectedDebugAndroidTest` requires a running emulator or attached Android device. The Gradle wrapper JAR and wrapper scripts are not included. Use Android Studio's configured Gradle installation or generate a wrapper after installing Gradle. No APK is included.

## Privacy and offline behavior

The app manifest has no `INTERNET` permission. Game state, session metadata, settings, and aggregate statistics remain in the app's private SQLite database. Android backup is disabled. No user data is uploaded.

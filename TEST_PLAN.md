# BlockVerse QA and correction record — v1.4.0

## Pure Kotlin engine tests — executed

- [x] Fresh game initializes an empty 8×8 board and three pieces.
- [x] Invalid placement does not change score or consume a piece.
- [x] Undo restores board, tray, score, combo, level, energy, power-up count, and PRNG state.
- [x] Save-state round trip preserves board, mode, tray, special metadata, and PRNG state.
- [x] Horizontal and vertical lines completed by one placement clear together.
- [x] Prism piece clears the 3×3 area around its placement anchor.
- [x] Energy meter and row blast work; undo restores energy.
- [x] Daily continuation after save/restore generates the same subsequent tray.
- [x] Malformed save data is rejected without partially replacing live state.
- [x] Invalid board colors are rejected.
- [x] Empty tray saves are rejected.
- [x] Serialized game-over flag must match actual legal-move availability.
- [x] Unsupported piece shapes are rejected.
- [x] Full board/no legal move state restores as game over.
- [x] Challenge completion can be undone before finalization.
- [x] Journey level calculation handles high-score boundaries without an unbounded loop.
- [x] Score saturates instead of wrapping negative.
- [x] Impossible score values for a low move count are rejected.
- [x] Power-up combo starts a new chain when the previous move did not clear a line.
- [x] Engine save versions 1 and 2 migrate into the current state format.
- [x] Time Attack mode state is serializable.
- [x] Zero PRNG state is rejected.
- [x] Oversized save payload is rejected atomically.
- [x] Randomized play/save/restore/undo simulation passes for 24 seeds.
- [x] Invalid piece-ID/next-ID relationship is rejected.
- [x] Unknown mode falls back to a valid Classic game.
- [x] 28/28 engine test methods passed using a temporary JUnit-compatible local assertion harness (not Gradle/JUnit runtime).

## Database and Android instrumentation tests — added, not executed

- [ ] Transactional game/session save and idempotent result recording.
- [ ] Upgrade from the v1 SQLite schema while preserving settings and Time Attack time.
- [ ] Invalid legacy timer value falls back to a safe default.
- [ ] Statistics counters saturate instead of overflowing.
- [ ] Rejected session identifier does not replace the previous committed save.
- [ ] Oversized SQLite payload is discarded before creating a large Kotlin string.
- [ ] Clear operation removes game payload and active session together.
- [ ] Activity exposes home/game accessibility virtual nodes and actions.

The eight tests are under `app/src/androidTest/` and use Android's instrumentation test framework. They require an emulator or physical Android device.

## Source-level corrections applied in v1.4.0

- [x] Per-instance default PRNG seed while keeping date-seeded Daily puzzles deterministic.
- [x] Bounded atomic import validation and exact PRNG state restoration.
- [x] Saturating game score and database statistics arithmetic.
- [x] Checked transactional persistence and safe session identifiers.
- [x] Validated/clamped legacy Time Attack timer migration.
- [x] Persistence failures do not falsely enable Continue; failures are logged.
- [x] Time Attack completion callback is idempotent at the UI lifecycle boundary.
- [x] Theme-aware secondary text contrast and system-bar appearance.
- [x] Board-aligned drag preview with valid/invalid visual feedback.
- [x] Multi-touch cancellation prevents accidental final pointer placement.
- [x] Accessibility nodes publish parent/screen bounds and focus actions.

## Android build/device release gates — still open

- [ ] Android Gradle sync and full app compilation with SDK Platform 35.
- [ ] Android lint, merged-manifest and dependency analysis.
- [ ] Run unit tests using Gradle/JUnit.
- [ ] Run the eight instrumentation tests on emulator/device.
- [ ] Airplane-mode install, launch, play, save, force-stop, and relaunch.
- [ ] Validate SQLite schema v1-to-v3 upgrade, engine save-state v1/v2 migration, and corruption recovery on Android.
- [ ] Validate Time Attack across backgrounding, screen lock, Activity recreation, process death, and timeout races.
- [ ] Validate TalkBack touch exploration, focus traversal, and every virtual accessibility action.
- [ ] Test 360×640, 393×873, 432×960, and tablet-sized displays with cutouts and system navigation modes.
- [ ] Profile frame pacing, memory, battery, and touch response on low-end and 60/90/120 Hz devices.
- [ ] Review drag preview, animation, light-theme contrast, sound, and haptics on physical devices.
- [ ] Generate, install, and verify a signed release APK.

## Product scope limits (not defects fixed by this correction pass)

- Journey remains score-threshold progression, not an authored campaign map.
- Zen uses the shared placement rules rather than a separate ruleset.
- Daily and Challenge are line-clear objectives, not a broad library of authored puzzle variants.
- No online leaderboard, multiplayer, cloud sync, ads, account system, or background music. Online features would violate the current offline-only boundary.
- Offline save validation is not server-grade anti-cheat against a device owner able to modify local app data.

Do not call the game production-ready until all Android/device checks above pass and no critical/high-severity defects remain.

# BlockVerse v1.4.0 — Corrective security and QA audit

## Source corrections

| Area | Finding | v1.4.0 correction | Status |
|---|---|---|---|
| Randomness | Fresh installs always began from the same fixed PRNG seed | New engine instances receive a per-instance time-derived seed; Daily still uses its explicit date seed | Fixed in source |
| Save import | Malformed or oversized save could corrupt live state | Bounded atomic parsing validates schema, board, palette, shapes, IDs, counters, score plausibility, random state, and terminal consistency | Fixed in source |
| Undo | Random sequence could diverge after undo | Snapshot validates and restores the exact PRNG state | Fixed in source |
| Score | Integer arithmetic could wrap | Engine score saturates at `Int.MAX_VALUE` | Fixed in source |
| Journey | Score-controlled loop could run excessively | Constant-time bounded level calculation | Fixed in source |
| SQLite write | Game payload and session could be committed inconsistently | Both writes are checked and committed in one transaction; failure rolls back | Fixed in source; Android runtime test pending |
| Session ID | Arbitrary identifiers could enter the persistence layer | Save and completion IDs are bounded to a safe ASCII identifier set | Fixed in source |
| Duplicate completion | Process restart could count a game twice | Unique session table and transactional idempotent completion | Fixed in source; Android runtime test pending |
| Statistics | Repeated games could overflow counters | Games and total score use saturating SQL arithmetic | Fixed in source; Android runtime test pending |
| Legacy timer | Invalid stored timer could migrate to zero | Migration accepts digits only and clamps to 0–90,000 ms; invalid values use 90,000 ms | SQL logic exercised locally; Android migration test pending |
| Oversized database payload | Loading a tampered large text field could allocate it before the size check | Query checks SQLite `length(payload)` before calling `getString`; oversized save and session are cleared | Fixed in source; Android runtime test pending |
| Persistence errors | UI could advertise a save that failed | Save exceptions are logged; Continue is enabled only after successful persistence | Fixed in source |
| Timer lifecycle | Timer completion callbacks could repeat finalization | Time Attack finish method is guarded; DB completion remains idempotent | Fixed in source; device race test pending |
| Theme contrast | Light theme used low-contrast secondary text and dark system-bar icons could mismatch | Theme-aware secondary color and system bar appearance | Source corrected; device contrast review pending |
| Drag preview | Preview did not match placement anchor | Preview follows board cell footprint and indicates valid/invalid placement | Source corrected; visual device review pending |
| Multi-touch | Trailing pointer-up could accidentally place a piece | Multi-touch gesture is cancelled as a whole | Source corrected; device touch test pending |
| Accessibility | Virtual nodes lacked complete screen bounds/focus actions | Parent and screen bounds, focus/clear-focus actions, virtual controls and board cells | Source corrected; TalkBack test pending |

## Verification executed

- 28/28 pure Kotlin engine test methods passed using a temporary local JUnit-compatible assertion harness.
- Randomized play/save/restore/undo simulation passed across 24 seeds.
- SQLite migration and statistics SQL were exercised with Python SQLite, including invalid timer values and integer saturation.
- Android XML resources and manifest parse successfully.
- Kotlin parser reported no syntax diagnostics. Android types could not be resolved because no Android SDK is installed in this environment.
- Manifest has no `INTERNET` permission; no network integration or additional runtime permission was added.
- Eight Android instrumentation tests were added but not executed.

## Items that remain unverified or intentionally out of scope

1. Android Gradle compilation, Android lint, merged-manifest analysis, APK generation, and release signing.
2. Android SQLite runtime migration and corruption recovery on a device.
3. Time Attack lifecycle behavior on emulator/device: backgrounding, screen lock, Activity recreation, process death, and timeout races.
4. TalkBack virtual-node focus, hover exploration, and click actions.
5. Edge-to-edge layout, system navigation modes, cutouts, theme contrast, touch accuracy, and multi-touch cancellation.
6. Frame pacing, memory leaks, battery use, low-end performance, and 90/120 Hz behavior.
7. Full visual review of drag preview, animations, and sound/haptic behavior.
8. Journey campaign map, separate Zen ruleset, and larger Daily/Challenge puzzle library remain product-design gaps.
9. No online leaderboard, multiplayer, cloud sync, ads, accounts, or background music are included; online services conflict with the offline-only boundary.
10. Offline validation detects malformed and implausible saves but cannot provide server-grade anti-cheat against a device owner able to patch the app or local database.
11. Gradle wrapper scripts/JAR are not included. No APK is provided or claimed.

## Release decision

**Source hardening and reproducible engine tests: completed for the corrections listed above.**

**Android release validation: not certified.** SDK compilation, instrumentation, device, accessibility, lifecycle, and performance gates remain open. No APK build was attempted because this environment has no Android SDK or emulator, and this task is delivering corrected source rather than claiming an unverified binary.

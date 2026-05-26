# Thumbkeeper

A mobile-first 2D casual puzzle-platformer designed entirely around one-thumb
gameplay and ultra-fast retry loops. Built on [libGDX](https://libgdx.com/) so
the same Java codebase runs on Android and desktop (LWJGL3) without changes.

> Hold to charge. Drag to aim. Release to launch.
> The tower never stops rising — and neither should you.

## Highlights

- **One-thumb input** — tap to charge, drag to aim, release to launch. A live
  trajectory preview shows the predicted arc so timing & angle stay readable.
- **14 platform/hazard archetypes** — stable, disappearing, moving (H/V),
  rotating, crumbling, bounce, ice, magnetic, gravity-flip (low-grav pad),
  teleport pairs, fake, spikes, and rhythmic lasers.
- **Procedural tower** that respects reachability budgets so jumps are always
  fair, with difficulty tiers that introduce one new mechanic at a time.
- **Polished feel** — squash & stretch, particles, trauma-based screen shake,
  charge glow, fast-trail, slow-mo on near-misses, and a smooth camera that
  anticipates motion and gently zooms out at high speed.
- **Procedural neon audio** — every sound (charge tick, jump, land, bounce,
  teleport, near-miss, death) and the ambient pad are synthesized in real time
  via a single background `AudioDevice` thread, so the build ships with zero
  audio assets.
- **Cosmetic-only monetization-ready skin system** with milestone unlocks
  (total runs, best height). No pay-to-win mechanics — ever.
- **Daily challenge** mode seeded by the calendar day, plus persistent
  high-score / best-height / total-runs tracking via libGDX `Preferences`.
- **Local + global leaderboards** — the top 10 per-device runs are stored in
  `Preferences`; the top 10 *globally* live in a Postgres table on Neon, talked
  to via the Neon SQL-over-HTTP endpoint on a background thread (no JDBC, no
  native dependency). The global table is auto-created and seeded with ten
  `Arc` defaults (5000, 10000, ... 50000) on first connect, so the leaderboard
  is never empty.

## Project layout

| Module    | Purpose                                                |
| --------- | ------------------------------------------------------ |
| `core`    | Shared game logic — runs on every backend.             |
| `lwjgl3`  | Desktop launcher (LWJGL3). Portrait window for parity. |
| `android` | Android launcher. Forced to portrait, fullscreen.      |

Inside `core/src/main/java/arc/keeper`:

```
Main.java               -- Game entry, owns shared GPU + save + audio resources
Constants.java          -- World size, physics tuning, palette, helpers
audio/AudioManager.java -- Procedural SFX + ambient pad on AudioDevice thread
data/                   -- SaveData, Skin/SkinManager, LocalHighScores,
                           GlobalHighScores (Neon Postgres on a background thread)
fx/                     -- ParticleSystem, ScreenShake
game/                   -- Player, Tower, Platform, PlatformType
screens/                -- TitleScreen, GameScreen, HighScoresScreen, UI helpers
```

## Run on desktop

```bash
./gradlew lwjgl3:run        # macOS / Linux
gradlew.bat lwjgl3:run      # Windows
```

A portrait window (405x720, resizable) opens. Click/tap the screen to charge,
drag to aim, release to launch. Press `M` to mute audio, `Esc` to return to
the title screen, `R` to retry from the game-over overlay.

## Run on Android

```bash
./gradlew android:installDebug android:run
```

Requires an Android SDK install and a connected device or emulator (configured
through `local.properties`). The app forces portrait and uses immersive mode.

## Global leaderboard

`GlobalHighScores` POSTs SQL to Neon's `/sql` HTTPS endpoint on a single daemon
background thread — no JDBC, no native dependency. On first connect it runs
`CREATE TABLE IF NOT EXISTS highscores ...` and seeds ten default `Arc` rows
(5000, 10000, ... 50000) if the table is empty. The Neon connection string is
hardcoded in `GlobalHighScores.NEON_URL`; replace it (and rotate the password)
if you fork the game. Players pick a name from the title screen — it's
uppercased, stripped of control characters, and capped at 12 chars by
`HighScoreEntry.sanitizeName` before being sent to the database.

## Tuning the game feel

Every gameplay knob lives in `core/src/main/java/arc/keeper/Constants.java`.
Recommended starting points:

| Constant                  | Effect                                       |
| ------------------------- | -------------------------------------------- |
| `GRAVITY`                 | Higher = snappier arcs, harder fast falls    |
| `JUMP_MIN/MAX_IMPULSE`    | Lower bound = small taps, upper = full charge|
| `JUMP_CHARGE_TIME`        | Time to fully charge a jump                  |
| `WALL_SLIDE_GRAVITY`      | Lower = stickier wall slides                 |
| `DEATH_LINE_START_SPEED`  | Initial pressure                             |
| `DEATH_LINE_RAMP_HEIGHT`  | Player height at which difficulty caps       |
| `PERFECT_JUMP_BONUS`      | Points per chained max-charge jump           |

Difficulty tiers (which mechanics appear at which height) live in
`Tower.difficultyFor` and `Tower.pickType` — easy to retune without touching
gameplay code.

## Roadmap / ideas left in the design doc

- **Tower themes** (more palettes) wired into `SkinManager`.
- **Optional revive ads** — slot is left open in `Main.audio` / `SaveData`.
- **Seasonal challenge ladders** — extend `SaveData.recordRun` to push a
  rolling buffer.
- **Darkness zones** (limited visibility) — requires a stencil/light mask pass.
- **True gravity flip** — currently implemented as a low-grav buff so the
  collision system stays simple; a flipped-gravity variant is a natural follow-up.

## Credits

Built with [libGDX 1.14.1](https://libgdx.com/). All gameplay code under
`arc.keeper.*` is original.

# Thor Emu Tuner

An Android app for the **AYN Thor** (Snapdragon 8 Gen 2, Android 13, 1920x1080 top screen and
1080x1240 bottom screen) that helps you choose, apply and **A/B-test per-game emulator settings**.

It scans your ROM folders, identifies each game (GameCube/Wii ID, PS1/PS2 serial, PSP disc ID,
3DS/Switch title ID, ...), suggests a starting preset for the matching emulator, lets you tweak
settings in plain English, writes them to the emulator's per-game config (with a backup and a
read-back check), launches the game, and runs a timed test session that records battery power and
temperature while you play. You enter the FPS you saw; the app keeps a per-game history so you can
compare two setting revisions side by side.

> **Honesty note.** During research, the community guide sites for the Thor and Odin 2
> (Retro Game Corps, Retro Handhelds, Joey's Retro Handhelds, Reddit and others) were **unreachable**.
> Every value in the shipped presets is therefore either taken from the emulators' own source code
> (for key names and defaults) or is a **starting guess**. All presets currently carry the
> "Starting guess (unverified)" badge. Treat them as a place to start and use the app's A/B test
> sessions to find what actually works on your device.

## Install

1. Open the [Releases](../../releases) page of this repository and download the latest
   `thor-emu-tuner-<version>.apk`.
2. On the Thor, allow your browser or file manager to install unknown apps
   (Settings > Apps > Special app access > Install unknown apps).
3. Open the APK and install it. Updates install over older versions as long as they are signed
   with the same key (see below).

The app needs Android 11 or newer (minSdk 30). It requests **no** internet, storage or
all-packages permissions; folders are accessed only through the Storage Access Framework.

### Signing-key caveat (read this)

Release APKs built by GitHub Actions are signed with a **public test keystore that is committed
to this repository** (`keystore/thor-emu-tuner-public.jks`, alias `thoremutuner`, password
`thoremutuner-public`). This key is **not secret**: anyone can build an APK with the same signature.
It exists only so that updates install over older builds without uninstalling.

- Only install APKs from this repository's Releases page.
- If you fork the project, set the repository secrets `SIGNING_KEYSTORE_B64`,
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`; CI then signs with your
  private key instead. Switching keys requires uninstalling the old build once.

## First run

1. **Welcome**: what the app does and does not do.
2. **ROM folders**: press *Add folder* and pick your ROMs root (for example `ROMs` containing
   `gc`, `ps2`, `psp`, `3ds`, `switch` ...) or one folder per system. Folder names follow ES-DE
   conventions. Android does not allow picking the storage root, `Download` or `Android/data`.
3. **Scan**: the app reads only small parts of each file (at most 256 KiB) to find game IDs.
   Unreadable files are still listed with a note.
4. **Emulator config folders** (optional): grant the folders the app may write to.
   - *Dolphin*: in the folder picker open the menu (≡) and choose **Dolphin** (Dolphin exposes its
     user folder through its own document provider).
   - *PPSSPP*: the memory-stick folder that contains `PSP` (must be in shared storage).
   - *Azahar*: the user folder you chose in Azahar (contains `config/config.ini`).
   - *RetroArch*: `/storage/emulated/0/RetroArch` (contains `config`).
   - *Eden* needs no folder: its settings travel inside the launch intent.

   You can skip this and grant later in Settings, or use *Export instead* to write files to a
   folder of your choice and copy them by hand.

Then, for a game: **Choose baseline** > **Tweak** (optional) > **Apply** > **Launch** or
**Run test**. Every save creates a new, immutable revision.

**Gamepad**: the D-pad moves focus (a bright yellow ring), **A** activates, **B** goes back.

## Supported emulators

| Emulator | Systems | v1 support | How settings are applied |
|---|---|---|---|
| Dolphin | GameCube, Wii | **Full** | Per-game `GameSettings/<ID6>.ini` (merged; never writes `Video_Hacks` from presets) |
| PPSSPP | PSP | **Full** | Per-game `PSP/SYSTEM/<DISCID>_ppsspp.ini` (merged; never writes `GraphicsBackend`) |
| Eden | Switch | **Full** | Per-game INI sent in the `LAUNCH_WITH_CUSTOM_CONFIG` launch intent |
| Azahar | 3DS | **Full** (global merge) | Managed keys swapped into `config/config.ini` before launch; originals kept; *Restore my Azahar settings* |
| RetroArch | NES, SNES, GB/GBC/GBA, Genesis family, N64, PS1 | **Full** (6 cores) | Game override `config/<core>/<rom name>.cfg` |
| NetherSX2 | PS2 | Reference | Manual checklist |
| ARMSX2 | PS2 | Reference | Manual checklist |
| DuckStation | PS1 | Reference | Manual checklist |
| melonDS / MelonDualDS | DS | Reference | Manual checklist |
| Vita3K | PS Vita | Reference | Manual checklist |
| Winlator (Cmod) | Windows | Reference | Manual checklist |
| Flycast | Dreamcast | Reference | Manual checklist |
| Mupen64Plus FZ | N64 | Reference | Manual checklist |

"Reference" emulators keep their settings in private storage that other apps cannot write. For
them the app shows a checklist (menu path > setting > value), lets you keep **manual revisions**
of what you set, and still launches games and runs test sessions, so PS2, DS and the others get the
same per-game history and A/B comparison. Nothing is ever written for reference emulators.

Before every write the current file is backed up in app-private storage (last 20 per emulator; the
first Azahar `config.ini` backup is kept forever), written in truncate mode and read back; on a
mismatch the backup is restored.

## Benchmark method and its limits

Android does not let one app read another app's frame rate without root, so a test session combines
device telemetry with what you report:

- **Pre-flight**: the charger must be unplugged (power readings are meaningless while charging);
  warnings for battery below 20% and for an external display. You pick the performance and fan
  mode set in AYN's quick settings (apps cannot read them). Durations: 3, 5, 10 or 20 minutes.
- **During the session** a foreground service samples every 2 s: battery current and voltage,
  battery temperature, charge level and the system thermal status. It starts before the emulator
  launches, alerts you when the time is up and stops by itself 5 minutes later.
- **Afterwards** you enter the average FPS from the emulator's overlay (required unless it crashed),
  the target FPS, the lowest FPS, stutter (1-5), audio and graphics issues and the outcome.
- **Metrics**: average and 95th-percentile power (first 60 s excluded as warm-up), peak power,
  energy (trapezoid integration), estimated runtime (22.2 Wh / average W), temperature rise, worst
  thermal status, FPS per watt and speed (FPS / target).
- **Comparison**: the better session is decided by speed, then power, then temperature, then
  stutter, then outcome. Sessions are flagged **not comparable** when durations differ by more
  than 20%, starting battery temperatures differ by more than 5 °C, emulator versions or
  performance/fan modes differ, or the current-unit heuristic applied to only one of them.

Limits you should know about:

- FPS is typed in by you, so it is as accurate as your reading of the overlay.
- Battery current units and signs differ between devices. If readings look like mA they are
  scaled (a flagged heuristic). The runtime estimate assumes a 3.7 V nominal battery voltage,
  which is an assumption, not a measured value.
- Battery temperature lags the SoC; thermal status is a coarse 0-6 scale.
- Background apps, screen brightness and the second screen all affect power. Keep them constant
  between A and B.
- If Android kills the app during a session, power falls back to the battery charge counter.

## Privacy

- No internet permission, no analytics, no crash reporter.
- The app never reads device serials or other device identifiers.
- Data lives in app-private storage as JSON. *Export data* writes titles, game IDs, profiles and
  sessions, never folder locations, document IDs, file paths or device identifiers.

## Planned (after v0.1)

- GCZ decoding and NSP ticket parsing for IDs (these files currently use filename IDs).
- A formatted in-app viewer for the sources list (v0.1 shows plain text).
- Importing exported data.
- Charts in the A/B comparison (v0.1 shows a table).
- Thermal-headroom sampling.
- Reading Azahar's version to pick the dual-screen layout value automatically (v0.1 lets you pick).
- A "default emulator per system" onboarding step (you can switch per game today).

## Building from source

- `:core` is pure Kotlin/JVM and holds all the logic that can be unit-tested (scanner, writers,
  launch planner, metrics). It builds without the Android SDK:
  `./gradlew :core:test -Pthor.skipAndroid=true`
- `:app` is included automatically when an Android SDK is found (`ANDROID_HOME`,
  `ANDROID_SDK_ROOT` or `local.properties`): `./gradlew :app:assembleRelease`
- CI (`.github/workflows/build.yml`) runs the core tests, a privacy check, builds the release APK
  and, for tags `v*`, publishes a GitHub Release with the APK attached.

Toolchain: Gradle 8.14.3, Kotlin 2.0.21, AGP 8.7.3, JDK 17, compileSdk/targetSdk 35, minSdk 30.

## Credits and sources

Launch commands, file formats and setting keys come from the emulators' own source code and from
ES-DE. Every URL used is listed in [`docs/SOURCES.md`](docs/SOURCES.md) (also viewable in the app
under Settings > About); the design is in [`docs/PLAN.md`](docs/PLAN.md). Thanks to the Dolphin,
PPSSPP, Eden, Azahar, RetroArch/libretro, PCSX2, DuckStation, melonDS, mGBA, Mupen64Plus, Vita3K,
Flycast, Winlator and ES-DE projects, whose documentation and code made this possible. This project
is not affiliated with AYN or with any emulator project.

Test fixtures under `core/src/test/resources/fixtures` include short excerpts of emulator config
files for round-trip tests; see the `NOTICE.md` there for their origin and licenses.

## License

[MIT](LICENSE), copyright Thor Emu Tuner contributors.

# Thor Emu Tuner

> **Status: v0.1.0 has not been run on a Thor or any other physical device yet.** It is built and
> unit-tested in CI only. Expect rough edges and please report bugs in this repository's
> [Issues](../../issues).

An Android app for the **AYN Thor** (Snapdragon 8 Gen 2, Android 13, 1920x1080 top screen and
1080x1240 bottom screen) that helps you choose, apply and **A/B-test per-game emulator settings**.

It scans your ROM folders, identifies each game (GameCube/Wii ID, PS1/PS2 serial, PSP disc ID,
3DS/Switch title ID, ...), suggests a starting preset for the matching emulator, lets you tweak
settings in plain English, writes them to the emulator's per-game config (with a backup and a
read-back check), launches the game, and runs a timed test session that records battery power and
temperature while you play. You enter the FPS you saw; the app keeps a per-game history so you can
compare two setting revisions side by side.

## There are no community-tested presets yet

During research, the community guide sites for the Thor and Odin 2 (Retro Game Corps, Retro
Handhelds, Joey's Retro Handhelds, Reddit and others) were **unreachable**. Setting names, value
ranges and defaults come from the emulators' own source code, but every bundled preset is a
**"Starting guess (unverified)"**. Treat presets as a place to start and use the app's A/B test
sessions to find what actually works on your device.

Each preset shows an evidence badge. From strongest to weakest:

1. **Thor-tested**: measured on an AYN Thor.
2. **Odin 2 (same chip)**: measured on an Odin 2 (same Snapdragon 8 Gen 2).
3. **SD 8 Gen 2 general**: reported for Snapdragon 8 Gen 2 devices in general.
4. **Starting guess (unverified)**: reasoning only. A preset can never claim more than its weakest
   value, and today every preset is at this level.

**Contribute tested values**: run a test session, then open a
["Tested settings" issue](../../issues/new?template=tested-settings.yml) with the emulator and
version, the game and its ID, the settings, the measured FPS and your performance/fan mode. Reports
like these are how presets move up the ladder.

## Install

1. Open the [Releases](../../releases) page of this repository and download
   `thor-emu-tuner-0.1.0.apk` (or the newest version). Optionally compare its checksum with
   `SHA256SUMS.txt` from the same release (`sha256sum thor-emu-tuner-0.1.0.apk`).
2. On the Thor, allow your browser or file manager to install unknown apps
   (Settings > Apps > Special app access > Install unknown apps).
3. Open the APK and install it. **Google Play Protect may warn about or flag the APK**, because it is
   signed with the public test key described below; this is expected for this project. Updates
   install over older versions as long as they are signed with the same key.

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

- **Welcome**: what the app does and does not do. Press *Get started*.
- **1/3 ROM folders**: press *Add folder* and pick your ROMs root (for example `ROMs` containing
  `gc`, `ps2`, `psp`, `3ds`, `switch` ...) or one folder per system. Folder names follow ES-DE
  conventions. Android does not allow picking the storage root, `Download` or `Android/data`.
- **2/3 Scan**: the app reads only small parts of each file (about 256 KiB, up to ~400 KiB for disc images; never the whole ROM) to find game IDs.
  Unreadable files are still listed with a note.
- **3/3 Emulator config folders** (optional): grant the folders the app may write to, then *Finish*.
  - *Dolphin*: in the folder picker open the menu (≡) and choose **Dolphin** (Dolphin exposes its
    user folder through its own document provider). If your Dolphin build does not show a
    "Dolphin" entry, older installs may use `/storage/emulated/0/dolphin-emu` instead; otherwise use
    *Copy text* on the Apply screen and enter the values in Dolphin's own per-game settings
    (long-press the game in Dolphin), or *Export instead* and copy the file with a file manager that
    can reach Dolphin's folder.
  - *PPSSPP*: the memory-stick folder that contains `PSP` (must be in shared storage).
  - *Azahar*: the user folder you chose in Azahar (contains `config/config.ini`).
  - *RetroArch*: `/storage/emulated/0/RetroArch` (contains `config`). In RetroArch check
    *Settings > Directory > Config Files*: if it points into `Android/data`, change it to a folder in
    shared storage (for example `/storage/emulated/0/RetroArch/config`), or use *Export instead* and
    copy the override file there yourself.
  - *Eden* needs no folder: its settings travel inside the launch intent.

  You can skip this and grant later in Settings, or use *Export instead* to write files to a
  folder of your choice and copy them by hand.

**Gamepad**: the D-pad moves focus (a bright yellow ring), **A** activates, **B** goes back.

## Tune one game, step by step

1. **Library**: select the game.
2. Check the ID tag (for example `GMSE01 · from header`). If it says *No ID*, press **Edit ID** and
   type it (the app shows the expected format).
3. Pick the emulator chip under **Emulator**. For RetroArch, also pick the **RetroArch core**.
4. **Choose baseline**: read the badge and the values, then press **Use**. This saves rev 1.
5. **Tweak**: change one thing (each setting explains what it does and shows its impact), press
   **Save**, add a note ("3x to 2x") and **Save revision**. This saves rev N.
6. **Apply** shows the target file and old → new values; press **Write**. Or just press
   **Launch**, which applies the latest revision first.
7. **Run test**: go through the pre-flight list (unplug the charger, pick the performance and fan
   mode, pick a duration), press **Start test and launch**, play, then return to the app, press
   **End test**, enter the average FPS from the emulator's overlay (and the rest of the form) and
   press **Save result**.
8. Change **one** setting in Tweak, save it as a new revision and run the same test again.
9. **History**: select two sessions and press **Compare**. The better value of each metric is
   highlighted. A **Not comparable** badge means the two tests differ in something that affects
   power or speed (duration by more than 20%, starting battery temperature by more than 5 °C,
   emulator version, performance or fan mode, or the current-unit heuristic applied to only one of
   them; see [Benchmark method and its limits](#benchmark-method-and-its-limits)): repeat the test
   under the same conditions.

For reference-only emulators the **Tweak** and **Apply** buttons become **Manual settings** and
**Checklist**: you record what you set inside the emulator, and nothing is written.

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
- If Android closes the app during a session, the test ends when you reopen it. If you are back
  within 5 minutes of the planned end and not charging, average power is estimated from the battery
  charge counter; otherwise the result is marked "power data incomplete".

## Privacy

- Permissions requested: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` (the test-session
  service that samples battery power and temperature), `POST_NOTIFICATIONS` (its notification and
  the "test time reached" alert; sampling works without it) and `VIBRATE` (that alert).
- Package visibility is limited to the emulator packages listed in the manifest's `<queries>`; the
  app cannot see your other apps.
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
files for round-trip tests. They are **not** MIT-licensed; see the `NOTICE.md` there for their origin
and licenses.

## License

[MIT](LICENSE), copyright Thor Emu Tuner contributors.

Exception: the test fixtures in `core/src/test/resources/fixtures/` are third-party excerpts that stay
under their original licenses (GPL-2.0-or-later or GPL-3.0-or-later), not MIT. See
`core/src/test/resources/fixtures/NOTICE.md` and the license texts next to it. They are used only by
unit tests and are not part of the app.

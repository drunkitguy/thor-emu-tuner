# Thor Emu Tuner: Implementation Plan

Status: planning baseline for the Coder, the Harsh Critic and the Manager. The Critic enforces §15 (Acceptance criteria) item by item.

Target device: AYN Thor. It has a Snapdragon 8 Gen 2 (Adreno 740) and runs Android 13. The top display is 6" 1920x1080 at 120 Hz and the bottom display is 3.92" 1080x1240 at 60 Hz. The battery is 6000 mAh. Odin 2 devices use the same SoC and serve as the fallback evidence class.

---

## 0. What the app does (one paragraph)

The app is a single-activity Compose app. On first run it asks for ROM folders through the Storage Access Framework (SAF) and scans them. The scan identifies each game's system and game ID (from file headers when possible, otherwise from the filename). For each game the user picks an emulator and a community/sourced baseline preset (e.g. "Thor Balanced"), tweaks settings with plain-English controls, and saves the result as a profile revision. The app then applies that revision to the emulator's per-game config and launches the game. It runs a timed test session that samples battery power and thermals, and asks the user for the FPS and stutter they saw in the emulator's own overlay. Results are stored per game and per revision so two revisions can be compared A/B.

## 1. Constraints discovered during research (these drive the design)

1. **Android/data is off limits.** On Android 11+, `ACTION_OPEN_DOCUMENT_TREE` cannot select the storage root, `Download/`, or anything in `Android/data/` or `Android/obb/` ([Android docs](https://developer.android.com/training/data-storage/shared/documents-files)). Most emulators keep configs in `Android/data/<pkg>/files`. Only these routes work:
   - **Emulator-provided DocumentsProvider.** Dolphin and Eden expose their user dir through a DocumentsProvider with `FLAG_SUPPORTS_IS_CHILD`, so the user can pick "Dolphin" in the system picker's side menu.
   - **Emulators whose user dir is in shared storage.** Azahar's user folder is chosen by the user via SAF. PPSSPP's memstick folder is user-selectable. RetroArch uses `/storage/emulated/0/RetroArch/config` when shared storage is writable.
   - **Config passed inside the launch intent.** Eden's `LAUNCH_WITH_CUSTOM_CONFIG` needs no file access at all.
   - **Anything else is "reference only".** The app shows a manual checklist, and optionally exports the file to a user-chosen shared folder for manual copying.
2. **FPS cannot be read from another app without root.** The benchmark is therefore semi-manual (§9).
3. **No Android SDK in the dev sandbox.** `dl.google.com` is blocked, so AGP cannot resolve. All logic that can be unit-tested lives in the pure-JVM `:core` module. `:app` is compiled only in GitHub Actions.
4. **Emulators move fast.** Eden enums change between versions (e.g. `GpuAccuracy` is now `Low, High`). Presets therefore carry `keySource` links, and the app records the emulator `versionName` with every applied revision and test session.
5. **Built-in per-game fixes must not be overridden.** Dolphin's read-only `Sys/GameSettings/*.ini` holds required hacks (e.g. `GMS.ini` sets `EFBToTextureEnable = False`), and a user INI overrides them. Generic presets therefore never contain `Video_Hacks` keys. PPSSPP (`compat.ini`) and Eden (`overrides.ini`) apply their fixes independently.

## 2. Emulator support matrix

| Emulator | Systems | v1 level | Config mechanism | Launch |
|---|---|---|---|---|
| **Dolphin** | gc, wii | **FULL** | Per-game INI `GameSettings/<ID6>.ini` via Dolphin's DocumentsProvider tree | TvMainActivity, MAIN + LEANBACK_LAUNCHER, data=URI + `AutoStartFile` |
| **PPSSPP** | psp | **FULL** | Per-game INI `PSP/SYSTEM/<DISCID>_ppsspp.ini` in the memstick tree | VIEW + data |
| **Eden** | switch | **FULL** | INI text passed in `LAUNCH_WITH_CUSTOM_CONFIG` intent (Eden writes `config/custom/<TID>.ini`) | custom-config action; VIEW fallback |
| **Azahar** | n3ds | **FULL** (global-merge) | Managed keys merged into `config/config.ini` right before launch; backups + restore | VIEW + data, CLEAR_TASK/CLEAR_TOP |
| **RetroArch** | nes, snes, gb/gbc/gba, genesis family, n64, psx | **FULL** (6 cores) | Game override `config/<library_name>/<rom base name>.cfg` | explicit extras ROM/LIBRETRO/CONFIGFILE (file paths) |
| NetherSX2 | ps2 | reference | manual checklist | MAIN + `bootPath` |
| ARMSX2 | ps2 | reference | manual checklist | VIEW + data |
| DuckStation | psx | reference | manual checklist | `bootPath` + `resumeState=false` |
| melonDS / MelonDualDS | nds | reference | manual (SharedPreferences) | `<pkg>.LAUNCH_ROM` + `uri` |
| Vita3K | psvita | reference | manual | `AppStartParameters=["-r", TITLEID]` |
| Winlator Cmod | windows | reference | manual | `shortcut_path` (.desktop path) |
| Flycast | dreamcast | reference | manual | VIEW + data |
| Mupen64Plus FZ | n64 | reference | manual (built-in profiles) | VIEW + data |

"Reference" means: presets are shown as a checklist with `uiPath`/`uiValue`, launch and test sessions work, and nothing is written. Launch, test sessions and history work identically for all 13 emulators.

Reference emulators are still tinkerable: the user saves **manual revisions** (a free-form list of setting label + value, plus a note), seeded from the preset's `uiPath`/`uiValue`, stored in `ProfileRevision.values` (`section` = UI path, `key` = setting label, `value` = what to pick). Nothing is written to the emulator, but test sessions reference these revisions, so PS2, DS, etc. get the same per-game settings history and A/B comparison.

Package lists may repeat a package with different activities (armsx2, flycast). Launch targets are resolved as ordered, de-duplicated `(package, activity)` pairs; `<queries>` and the UI de-duplicate by package.

All launch specs, package names and config targets live in `core/src/main/resources/presets/*.json` (index: `presets/index.json`). Nothing emulator-specific is hard-coded in Kotlin except the five writers in §7.

## 3. Architecture

### 3.1 Modules and packages

```
thor-emu-tuner/
  settings.gradle.kts        # includes :core always, :app only if an Android SDK is detected
  build.gradle.kts           # root: Kotlin plugins (apply false) + AGP on buildscript classpath if :app included
  gradle/libs.versions.toml
  gradle/wrapper/...         # Gradle 8.14.3
  core/                      # pure Kotlin/JVM, no Android imports (enforced by acceptance check)
    src/main/kotlin/dev/thoremutuner/core/
      model/        Models.kt (SystemId, Game, DetectedId, IdKind, DetectionMethod ...)
      preset/       EmulatorDef.kt (serializable schema of §5), PresetRepository.kt, PresetResolver.kt
      scan/         ByteSource.kt, Iso9660.kt, CueParser.kt, DiscProbes.kt (gc/wii/ps2/ps1/psp),
                    CartProbes.kt (nds/gba/n64/3ds), SwitchProbe.kt, SfoParser.kt, CsoReader.kt,
                    GczReader.kt, FilenameIds.kt, SystemClassifier.kt, Scanner.kt
      config/       IniDocument.kt, DolphinWriter.kt, PpssppWriter.kt, AzaharWriter.kt,
                    EdenIniBuilder.kt, RetroArchOverrideWriter.kt, ConfigWriters.kt
      launch/       IntentSpec.kt, LaunchPlanner.kt, SafPaths.kt (documentId -> /storage path)
      profile/      GameProfile.kt, ProfileRevision.kt, ProfileService.kt
      bench/        Sample.kt, TestSession.kt, SessionMetrics.kt, Comparison.kt
      store/        JsonStore.kt (interface), Repositories.kt, Json.kt (kotlinx.serialization config)
    src/main/resources/presets/*.json
    src/test/kotlin/...     # JUnit 5 + kotlin-test; synthetic binary fixtures built in code
    src/test/resources/golden/...  # expected writer outputs
  app/                       # Android application (Compose, Material 3)
    src/main/kotlin/dev/thoremutuner/app/
      MainActivity.kt, ThorApp.kt (Application + AppContainer manual DI)
      saf/          SafTree.kt (list children via DocumentsContract), SafByteSource.kt (ParcelFileDescriptor +
                    FileChannel random access), SafWriter.kt ("wt" mode, create missing dirs)
      data/         FileJsonStore.kt (filesDir/tuner, atomic tmp+rename)
      emu/          InstalledEmulators.kt (PackageManager + <queries>), IntentFactory.kt (IntentSpec -> Intent)
      bench/        TestSessionService.kt (foreground service), BatterySampler.kt, ThermalSampler.kt
      ui/           theme/, nav/, onboarding/, library/, game/, preset/, tweak/, apply/, test/, history/, settings/
      vm/           ViewModels (one per screen), StateFlow based
```

- Kotlin package / applicationId: `dev.thoremutuner` (no personal names). App label: "Thor Emu Tuner".
- `:core` has **zero** Android dependencies, so it compiles and tests with plain Gradle on JDK 17+.
- DI: a hand-written `AppContainer` (no Hilt, no KSP), which keeps the build simple and fast.
- Concurrency: coroutines. Scanning runs on `Dispatchers.IO` with a bounded parallelism of 4.

### 3.2 Gradle setup (must work offline for `:core` in the sandbox)

- `settings.gradle.kts`:
  - `pluginManagement.repositories`: `gradlePluginPortal()`, `mavenCentral()`, then `google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }`.
  - `dependencyResolutionManagement.repositories`: `mavenCentral()` first, then `google {...}` with the same content filter. Non-Android artifacts must never be requested from google(), because the sandbox cannot reach it.
  - `val androidEnabled = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()`. Skip Android if `-Pthor.skipAndroid=true` (read via `providers.gradleProperty`).
  - `include(":core")`, and `if (androidEnabled) include(":app")`.
- Root `build.gradle.kts`:
  - `plugins { kotlin("jvm") version V apply false; kotlin("plugin.serialization") version V apply false; kotlin("plugin.compose") version V apply false }`.
  - A `buildscript { repositories { google { <same content filter> }; mavenCentral() }; dependencies { if (<:app included>) classpath("com.android.tools.build:gradle:$AGP") } }` block. The buildscript classpath needs its **own** `repositories` (settings-level repositories do not apply to it); `google()` is added only when `:app` is included. This puts AGP and KGP in the same root classloader, and `:app` applies `com.android.application` / `org.jetbrains.kotlin.android` **without versions**.
- Versions (pin in `libs.versions.toml`): Kotlin **2.0.21**, AGP **8.7.3**, Gradle wrapper **8.14.3**, Compose BOM **2024.12.01**, activity-compose 1.9.3, navigation-compose 2.8.5, lifecycle 2.8.7, androidx core-ktx 1.13.1 (for `ServiceCompat`), kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0, JUnit Jupiter 5.11.3. The Coder may bump a version only if CI proves the pin broken, and must record why in the commit message.
- JVM target: `compilerOptions.jvmTarget = JVM_17` and Java `sourceCompatibility/targetCompatibility = 17` for both modules. **Do not** use `jvmToolchain(17)`: the sandbox has only JDK 21 and toolchain download is not guaranteed.
- Android: `compileSdk 35`, `targetSdk 35`, `minSdk 30`. R8 on for release (`isMinifyEnabled = true`) with keep rules for kotlinx.serialization models.

### 3.3 Manifest essentials

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `VIBRATE`. **No** `INTERNET`, **no** `MANAGE_EXTERNAL_STORAGE`, **no** `READ/WRITE_EXTERNAL_STORAGE`, **no** `QUERY_ALL_PACKAGES`.
- `<queries>`: one `<package android:name=.../>` per package in all preset JSONs, because package visibility is filtered on Android 11+. Write the list by hand in the manifest. A `:core` unit test reads `../app/src/main/AndroidManifest.xml` as plain text (resolved from the project root, so it runs without the Android SDK) and asserts that every preset package appears inside `<queries>`.
- Service: `.bench.TestSessionService`, `android:foregroundServiceType="specialUse"`, with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Samples battery power and thermal status during a user-started emulator benchmark session"/>`.

## 4. Core domain model (Kotlin, `@Serializable`)

```kotlin
enum class SystemId(val folderNames: List<String>) {  // ES-DE folder names first
  GC(listOf("gc","gamecube")), WII(listOf("wii")), PS2(listOf("ps2")), PSX(listOf("psx","ps1","playstation")),
  PSP(listOf("psp")), N3DS(listOf("n3ds","3ds")), NDS(listOf("nds")), SWITCH(listOf("switch")),
  PSVITA(listOf("psvita","vita")), NES(listOf("nes","famicom")), SNES(listOf("snes","sfc")),
  GB(listOf("gb")), GBC(listOf("gbc")), GBA(listOf("gba")), N64(listOf("n64")),
  GENESIS(listOf("genesis","megadrive")), MASTERSYSTEM(listOf("mastersystem")), GAMEGEAR(listOf("gamegear")),
  DREAMCAST(listOf("dreamcast")), WINDOWS(listOf("windows","pc")), UNKNOWN(emptyList())
}
// JSON uses the lowercase ES-DE id: "gc","wii","ps2","psx","psp","n3ds","nds","switch","psvita","nes","snes",
// "gb","gbc","gba","n64","genesis","megadrive"(alias of GENESIS),"mastersystem","gamegear","dreamcast","windows".

enum class IdKind { GC_WII_ID6, PS2_SERIAL, PSX_SERIAL, PSP_DISC_ID, N3DS_TITLE_ID, SWITCH_TITLE_ID,
                    NDS_GAME_CODE, GBA_GAME_CODE, N64_GAME_CODE, VITA_TITLE_ID, NONE }
enum class DetectionMethod { HEADER, CONTAINER_METADATA, FILENAME, MANUAL }

data class DetectedId(val value: String, val kind: IdKind, val method: DetectionMethod, val extra: Map<String,String> = emptyMap())
// extra examples: "productCode" (3DS), "discRevision" (GC), "headerTitle"

data class Game(
  val key: String,               // first 16 hex chars of SHA-256(treeUri + "|" + documentId); stable, not reversible
  val title: String,             // cleaned filename (tags in () and [] removed) or header title
  val fileName: String,          // display name incl. extension
  val system: SystemId,
  val treeUri: String, val documentId: String,   // never exported (§10 privacy)
  val sizeBytes: Long, val lastModified: Long,
  val id: DetectedId?,           // null = unknown; user can set MANUAL
  val relatedDocumentIds: List<String> = emptyList()  // .bin tracks for .cue, discs for .m3u
)

data class ProfileRevision(
  val rev: Int, val emulatorId: String, val basePresetId: String?,
  val values: List<SettingValue>,       // full resolved list written by the writer
  val note: String, val createdAt: Long,
  val appliedAt: Long? = null, val appliedEmulatorVersion: String? = null
)
data class SettingValue(val section: String, val key: String, val value: String)
data class GameProfile(val gameKey: String, val emulatorId: String, val revisions: List<ProfileRevision>)
```

Rules:
- Revisions are immutable. "Save" always creates `rev = max + 1`.
- A test session references `(gameKey, emulatorId, rev)`.
- Preset resolution: start from the preset's `values`, then apply the user's edits. A value equal to the emulator default is still written, so behaviour is explicit, except for Eden, where `\default` is computed (§7.4).

## 5. Preset JSON schema (already populated for 13 emulators)

Top level: `schemaVersion`, `emulatorId`, `name`, `systems[]`, `supportLevel` (`full|reference`), `packages[]`, `launch`, optional `alternateLaunch`, `configTarget`, `settings[]`, `presets[]`, `gameSpecific[]`, `manualNotes[]`, optional `globalRecommendations[]`.

- `packages[]`: `packageName`, optional `activity` (overrides `launch.activity` when `launch.activity == "{packageActivity}"`), `label`, `source`. The list order is the preference order.
- `launch`: `packageName` (always `{package}`), `activity` (fully qualified class or `{packageActivity}`), `action` (nullable), `categories[]`, `data` (nullable template), `mimeType` (nullable), `extras[] {name,type(string|bool|int|stringArray),value}`, `flags[]` (`NEW_TASK|CLEAR_TASK|CLEAR_TOP|GRANT_READ_URI`), `source`, `notes`.
  - Placeholders: `{package}`, `{packageActivity}`, `{romUri}`, `{romPath}`, `{titleId}`, `{customSettingsIni}`, `{coreFile}`.
  - `stringArray` values are comma-separated after substitution.
- `configTarget`: `mode` (`perGameIni|intentInlineIni|globalIniMerge|retroarchOverride|manual`), `folderToGrant` (user-facing text), `folderValidation[]` (relative paths that must exist in the granted tree), `pathTemplate` (placeholders `{gameId}`, `{libraryName}`, `{romBaseName}`), `gameIdKind`, `iniStyle {keyValueSeparator, boolTrue, boolFalse, quoteValues?}`, `source`, `notes`, and optional `cores[]` (RetroArch: `coreFile`, `libraryName`, `systems[]`, `source`).
- `settings[]` (the Tweak-screen catalog): `section`, `key`, `label`, `type` (`enum|bool|int|float|string`), `group`, `impact` (`high|medium|low`), `options[] {value,label}`, `min`, `max`, `step`, `default` (nullable), `advanced` (bool, hidden behind "Show advanced"), `description`, `keySource`. `min`/`max` are UI limits chosen by the app unless the description says otherwise.
- `presets[]`: `id`, `name`, `evidence` (`thor|odin2|sd8g2-general|inferred`), `description`, `values[]`.
  - Each value has `section`, `key`, `value` (the literal string written), `description`, `source` (fetched URL or `"inferred"`), `reason` (required when `inferred`), and `keySource`.
  - Reference emulators also have `uiPath` and `uiValue`.
- `gameSpecific[]`: `gameId` or `gameIdPrefix`, `title`, `appliedBy` (`emulator|app`), optional `condition`, `note`, and `values[]` (each with `source`). v1 only **displays** these, and warns when a user edit would override one.

Evidence badge shown in the UI: "Thor-tested" (`thor`), "Odin 2 (same chip)" (`odin2`), "SD 8 Gen 2 general" (`sd8g2-general`), "Starting guess (unverified)" (`inferred`). Honesty matters more than looks: never show "tested" for `inferred`.

**Evidence rule:** a preset's `evidence` can never be stronger than its weakest value. Any value with `"source": "inferred"` makes the whole preset `inferred` (unit test). Because the community guide sites were unreachable during research, every shipped preset is currently `inferred`.

## 6. ROM scanning and game-ID detection (`:core/scan`)

### 6.1 I/O abstraction

```kotlin
interface ByteSource { val size: Long; fun read(offset: Long, length: Int): ByteArray }  // short read = EOF
interface DirectoryLister { fun children(dirDocId: String): List<Entry> }  // app implements with DocumentsContract
```

- The app implementation uses `contentResolver.openFileDescriptor(uri, "r")` → `FileInputStream(fd).channel`, keeping one channel open per probe.
- Tests implement `ByteSource` over `ByteArray`.
- **Read budget per file: at most 256 KiB**, with one exception: an ISO9660 directory read may take up to 64 more sectors. Never read a whole ROM.

### 6.2 Traversal

1. For each granted ROM root tree, list children with `DocumentsContract.buildChildDocumentsUriUsingTree`. The projection is `_id, _display_name, mime_type, _size, last_modified`. Do **not** use `DocumentFile.listFiles()`, which is slow.
2. Recurse up to depth 4. Skip hidden files (starting with `.`) and folders named `bios`, `media`, `images`, `videos`, `manuals`, `downloaded_media`.
3. System detection order:
   - (a) The nearest ancestor folder name matching `SystemId.folderNames`, case-insensitive.
   - (b) Otherwise, extension mapping from §6.4.
   - (c) For ambiguous extensions (`.iso .bin .chd .cso .img .zip .7z`), a header probe (§6.3).
   - (d) Otherwise `UNKNOWN`, listed in an "Unrecognized" section.
4. Multi-file grouping:
   - `.m3u` becomes the game entry, and the files it lists (resolved relative to the m3u's folder) are hidden.
   - `.cue` hides the `.bin` files named in its `FILE` lines.
   - `.gdi` hides track files.
   - Vita: `.psvita` files are the entries.
5. Scan cache: `(documentId, size, lastModified)` → previous result. Re-probe only on change. The "Rescan" button forces a full probe.

### 6.3 Header probes (all little-endian unless stated; offsets are bytes)

| Format | Detection | ID extraction | Source |
|---|---|---|---|
| GC/Wii ISO/GCM | BE u32 @0x18 == `0x5D1C9EA3` (Wii) or BE u32 @0x1C == `0xC2339F3D` (GC) | ID6 = ASCII @0x00..0x05 (must match `[A-Z0-9]{6}`); title = @0x20, 64 bytes, NUL-terminated; revision byte @0x07 | Dolphin DiscUtils.h |
| WBFS | "WBFS" @0 | shift = byte @8; hdSector = 1 shl shift; disc header copy @hdSector → ID6 @hdSector+0 | Dolphin WbfsBlob.cpp |
| WIA / RVZ | bytes @0 = `57 49 41 01` / `52 56 5A 01` | disc header copy @0x58 (= 0x48 header1 + 16 bytes of header2) → ID6 @0x58, magics @0x58+0x18/0x1C | Dolphin WIABlob.h |
| CISO (GC) | "CISO" @0 **and** u32 @4 != 0x18 (@4 is block_size) | if map byte @8 == 1: ID6 @0x8000 | Dolphin CISOBlob.h |
| CSO (PSP) | "CISO" @0 **and** u32 @4 == 0x18 (header_size) | total u64 @8, blockSize u32 @0x10, align u8 @0x15; index u32[n+1] @0x18; block i: idx=index[i], plain = idx & 0x80000000, pos = (idx & 0x7FFFFFFF) shl align, len = next - pos; non-plain = raw deflate (`Inflater(true)`). Feed ISO9660 reader (PSP rules below) | PPSSPP BlockDevices.cpp |
| GCZ | u32 @0 == `0xB10BC001` | header 32 B: sub_type u32, compressed_size u64, disc_size u64, block_size u32 @0x18, num_blocks u32 @0x1C; ptr u64[n] @0x20; data @0x20+12n; block0: if ptr0 bit63 → stored, else zlib (`Inflater()`); ID6 from block0 | Dolphin CompressedBlob.h/.cpp |
| ISO9660 (PS2/PS1/PSP) | sector 16 (0x8000 for 2048-byte sectors): byte0==1, "CD001" @1 | root dir record @PVD+156: extent LBA u32 @+2, length u32 @+10. Walk records: len byte @0 (0 → next sector), flags @25 (bit1 = dir), name len @32, name @33 (strip `;1`) | ISO 9660 (standard; implemented as in PCSX2 CDVD.cpp usage) |
| Raw 2352 BIN (PS1/PS2 CD) | sync `00 FF×10 00` @0 | mode byte @15: 1 → user data @16, 2 → @24; sector stride 2352; then ISO9660 | CUE `MODE1/2352`, `MODE2/2352` |
| PS2 | ISO9660 file `SYSTEM.CNF` has `BOOT2` | `BOOT2 = cdrom0:\SLUS_203.12;1` → take the part after the last `\` or `:`, drop `;1` → `SLUS_203.12` → serial `SLUS-20312` (4 letters, `-`, digits with `_` and `.` removed) | PCSX2 CDVD.cpp |
| PS1 | `SYSTEM.CNF` has `BOOT` (and no BOOT2) | `BOOT = cdrom:\SCUS_941.63;1` → `SCUS-94163` | same |
| PSP ISO | root has `UMD_DATA.BIN` or dir `PSP_GAME` | UMD_DATA.BIN text up to the first `|` (`ULUS-10041`) → remove `-` → `ULUS10041`; else `PSP_GAME/PARAM.SFO` `DISC_ID` | PPSSPP PSPLoaders.cpp, GameInfoCache.cpp |
| PBP (PSP/PS1 eboot) | `00 50 42 50` @0 | offsets u32[8] @8; SFO = [off0, off1) → DISC_ID, TITLE | PPSSPP PBPReader.h, ParamSFO.cpp |
| PARAM.SFO | `00 50 53 46` @0 | keyTable u32 @8, dataTable u32 @12, count u32 @16; entries @20, 16 B each: keyOff u16, fmt u16, len u32, max u32, dataOff u32 | PPSSPP ParamSFO.cpp |
| 3DS .3ds/.cci | "NCSD" @0x100 | title ID = u64 @0x108 → `%016X`; partition0 offset u32 @0x120 × 0x200 → NCCH: "NCCH" @+0x100, program ID u64 @+0x118, product code ASCII @+0x150 (16 B) | Azahar ncch_container.h |
| 3DS .cxi | "NCCH" @0x100 | program ID @0x118, product code @0x150 | same |
| NDS | extension .nds/.dsi | title ASCII @0x00 (12), game code @0x0C (4), maker @0x10 (2) | melonDS NDS_Header.h |
| GBA | extension .gba | title @0xA0 (12), game code @0xAC (4) | mGBA gba.h |
| N64 | first 4 bytes: `80 37 12 40` z64, `37 80 40 12` v64 (swap 16-bit pairs), `40 12 37 80` n64 (reverse 32-bit words) | normalize the first 0x40 bytes; name @0x20 (20 B); game code = bytes @0x3B..0x3E | mupen64plus rom.c, m64p_types.h |
| Switch NSP | "PFS0" @0 | n u32 @4, strtab size u32 @8; entries @0x10, 0x18 B each (offset u64, size u64, nameOff u32, pad); strtab @0x10+0x18n. If a name matches `^[0-9a-fA-F]{32}\.tik$` → title ID = first 16 hex, uppercase (rights ID prefix; **community convention, verify on real dumps**) | Eden partition_filesystem.h |
| Switch (all) | filename `\[(01[0-9A-Fa-f]{14})\]` | Normalize to the base application ID: `tid and 0xFFFFFFFFFFFFF000` when the low 12 bits == 0x800 (update); ignore DLC | Eden submission_package.cpp masks |
| Vita | `.psvita` text file | trimmed content matches `^[A-Z]{4}\d{5}$` | ES-DE USERGUIDE.md |

### 6.4 Extension map (from ES-DE `es_systems.xml`)

gc/wii: `.iso .gcm .ciso .gcz .rvz .wia .wbfs .dol .elf .tgc .wad .m3u` · ps2: `.iso .chd .cso .bin .img .m3u` · psx: `.cue .bin .chd .pbp .m3u .img .iso .ecm` · psp: `.iso .cso .chd .pbp` · n3ds: `.3ds .cci .cxi .cia .3dsx .zcci .zcxi .z3dsx` · nds: `.nds .dsi` · switch: `.nsp .xci .nca .nro .nso` · psvita: `.psvita` · snes: `.sfc .smc` · nes: `.nes .fds .unf .unif` · gba: `.gba` · gb/gbc: `.gb .gbc` · n64: `.z64 .n64 .v64` · genesis: `.md .gen .smd .bin` · dreamcast: `.chd .gdi .cdi .cue` · windows: `.desktop`.

Archives (`.zip .7z`) are accepted by system folder only. The ID comes from the filename, never from extraction.

### 6.5 Filename fallbacks (`FilenameIds.kt`)

- GC/Wii: `\[([A-Z0-9]{6})\]` or `\(([A-Z0-9]{6})\)`
- PS2/PS1: `\b(S[CL][UEPAKC][SMD]|SL[EPU]S)[-_ ]?(\d{3})\.?(\d{2})\b` → `XXXX-NNNNN`
- PSP: `\b([UN][CLP][UEJAKS][SMBHDFG])[-_]?(\d{5})\b` → no dash
- 3DS: `\b(000400[0-9A-Fa-f]{10})\b`
- Switch: see above
- Vita: `\b(PCS[A-H]\d{5})\b`

Method = `FILENAME`. The user can always edit the ID (method = `MANUAL`). The ID is validated per kind with the regex above before saving.

### 6.6 Explicitly out of scope for v1

CHD decompression, ECM, NKit, XCI title IDs (filename only), CIA/TMD parsing, Dreamcast IP.BIN, PS2 ELF CRC (needed only for NetherSX2 files; reference only).

## 7. Config writers (`:core/config`)

### 7.1 IniDocument

- Parse and preserve line order, comments (`#` or `;`), blank lines, unknown sections and unknown keys. Keys may contain `\` and `.`.
- `get(section, key)` and `set(section, key, value)` use case-sensitive keys. A missing section is appended at the end. A missing key goes after the last key of its section.
- Serialize with the emulator's separator. Write `\n` line endings, except that a file which consistently uses CRLF keeps CRLF (PPSSPP's own `compat.ini` does), so untouched files round-trip byte for byte; files with mixed endings are normalized to `\n`.
- An empty section name `""` means top-level lines (RetroArch).
- Round-trip tests: parse(x).serialize() == x for fixture files taken verbatim from the emulators' default configs, trimmed to 50 lines.

### 7.2 Common apply pipeline (app side, `SafWriter`)

1. **Validate the tree.** Check that each `folderValidation` path exists under the granted tree; if not, show the "wrong folder" dialog with `folderToGrant` text.
2. **Read and back up.** Read the existing file if present. Copy it to app-private `filesDir/tuner/backups/<emulatorId>/<yyyyMMdd-HHmmss>/<relativePath>`. Keep the last 20 backups per emulator, plus the first-ever backup of `config.ini` (Azahar), which is never deleted.
3. **Render.** Call `ConfigWriter.render(existingText, values, context)` in `:core`. It returns the new text.
4. **Write.** Create missing directories and the file (`DocumentsContract.createDocument`). Write with mode **"wt"** (truncate); a plain "w" can leave stale tail bytes on some providers.
5. **Verify.** Read the file back and compare. On mismatch, restore the backup and show an error.
6. **Record.** Stamp `appliedAt` and `appliedEmulatorVersion` (`PackageManager.getPackageInfo(pkg,0).versionName`) on the revision.
7. **Fall back.** If no tree is granted or validation fails, offer "Export instead": the user picks any shared folder, the app writes `<emulatorId>/<relativePath>` there and shows copy instructions. Also offer "Copy text".

### 7.3 Dolphin (`perGameIni`)

- Target: `<Dolphin user tree>/GameSettings/<ID6>.ini`.
- Merge into the existing file. Sections use the legacy names (`Core`, `Video_Settings`, `Video_Enhancements`, `Video_Hardware`, `Video_Hacks`). Separator ` = `. Booleans `True/False`.
- Refuse (with an explanation) to write any `Video_Hacks` key that comes from a generic preset. The user may set hacks manually in "Advanced", and the UI then shows the `gameSpecific` warning if one applies to that ID prefix.

### 7.4 Eden (`intentInlineIni`)

`EdenIniBuilder.build(values, defaults)` returns the full INI text. Group sections in the order `Core, Cpu, Renderer, System`. For each value:
```
<key>\use_global=false
<key>\default=<"true" if value == catalog default else "false">
<key>=<value>
```
- If the catalog default is null, write `\default=false`.
- Booleans are `true/false`. Enums are integer strings.
- Optional `[GpuDriver]` with `driver_path=<file name>` only when the user picked one.
- No `use_global`/`default` lines for `[GpuDriver]`. That is inferred from `extractDriverPath`, which reads the raw `driver_path=` line.
- The app also shows the INI in the Apply screen. Golden-file tested.

### 7.5 PPSSPP (`perGameIni`)

- Target: `<memstick tree>/PSP/SYSTEM/<DISCID>_ppsspp.ini`.
- Merge. Separator ` = `. Booleans `True/False`. Sections `[Graphics]` and `[CPU]`.
- Only keys present in the catalog may be written; the catalog contains only PER_GAME keys. `GraphicsBackend` is shown as a global recommendation, never written.
- If the file does not exist, create it with the header comment `# Game config for <DISCID> - written by Thor Emu Tuner`.

### 7.6 Azahar (`globalIniMerge`)

- Target: `<Azahar user tree>/config/config.ini`.
- Before every launch of a 3DS game whose active profile revision differs from the last-applied state, merge that revision's keys. Separator ` = `. Booleans `true/false` (**never** `1/0`). Integers as-is.
- Maintain `azahar_state.json`: the last applied `(gameKey, rev)` and the original values of every key the app ever touched, captured on first touch.
- "Restore my Azahar settings" writes those original values back.
- The UI says clearly: "Azahar has no per-game settings; Thor Emu Tuner swaps these keys in before each launch. Close Azahar first."

### 7.7 RetroArch (`retroarchOverride`)

- Target: `<RetroArch tree>/config/<libraryName>/<romBaseName>.cfg`.
- `libraryName` comes from `configTarget.cores[]` for the core chosen for that system. `romBaseName` is the filename without its last extension.
- Lines are `key = "value"`. The whole file is owned by the app: overwrite it, but back it up first.

## 8. Launching (`:core/launch` + `app/emu/IntentFactory`)

1. **Resolve the package.** Take the first package in `packages[]` that is installed (via `PackageManager.getPackageInfo`) and has a resolvable activity. The user can override it per system.
2. **Resolve the ROM.**
   - `{romUri}` = `DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)` as a string.
   - `{romPath}` = `SafPaths.toFilePath(documentId)`: `primary:<p>` → `/storage/emulated/0/<p>`; `<UUID>:<p>` → `/storage/<UUID>/<p>`; anything else → unsupported. When unsupported, show "This emulator needs a normal storage path; move the ROM folder to internal storage or SD card".
3. **Build the intent.** `IntentSpec` → `Intent`: `setClassName(package, activity)`, action, categories, `setDataAndType` or `setData`, and typed extras.
   - Flags: `NEW_TASK` always; `CLEAR_TASK`/`CLEAR_TOP` as listed; `GRANT_READ_URI` → `FLAG_GRANT_READ_URI_PERMISSION`. When data is null but an extra carries `{romUri}`, also set `clipData = ClipData.newRawUri("", uri)` so the grant applies.
4. **Eden.** Primary = custom-config intent with `title_id` (16 uppercase hex) and `custom_settings` (§7.4). If the game has no title ID, use `alternateLaunch` (VIEW) and warn that settings were not applied.
5. **Handle failures.** Catch `ActivityNotFoundException` and `SecurityException`. Show "Emulator not installed or its launch activity changed". Link to the emulator's info in the Settings app via `ACTION_APPLICATION_DETAILS_SETTINGS`.
6. **Folder-access hint.** Always show a one-time hint per emulator: "If the emulator says it cannot open the file, add this ROM folder inside the emulator too". ES-DE documents that many emulators need their own folder grant.

## 9. Benchmark procedure (test session)

The app **cannot** read another app's FPS without root. A test session combines automatic device telemetry with user-reported emulator metrics.

### 9.1 Pre-flight (shown as a checklist; blocking items marked *)

- \* Device is not charging (`ACTION_BATTERY_CHANGED` `EXTRA_PLUGGED == 0`). Power numbers are meaningless while charging.
- Battery at 20% or more (warning only).
- No external display attached (`DisplayManager.displays.size` ≤ 2; the Thor has 2 internal displays). This is a warning, because of Azahar issue #1437.
- The user selects the device performance mode and fan mode as set in AYN's quick settings. These are free text with suggestions, because they cannot be read programmatically.
- The applied profile revision matches the one being tested. If not, offer "Apply now".
- Duration: 3, 5, 10 (default) or 20 min. Warm-up excluded from averages: 60 s.

### 9.2 During the session

- `TestSessionService` (foreground, specialUse) starts **before** the emulator launch and records a start snapshot. It calls `ServiceCompat.startForeground` and passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` only on API 34+ (the Thor runs Android 13 / API 33). `POST_NOTIFICATIONS` is requested at runtime before the first test; sampling works even if it is denied (the notification is then simply hidden). It then samples every **2 s**:
  - `BATTERY_PROPERTY_CURRENT_NOW` (µA)
  - `EXTRA_VOLTAGE` (mV, sticky intent)
  - `EXTRA_TEMPERATURE` (tenths of °C)
  - `BATTERY_PROPERTY_CAPACITY` (%)
  - `PowerManager.currentThermalStatus` (0-6)
  - ~~`getThermalHeadroom(0)`~~ (deferred to after v0.1, §17)
- Samples are kept in memory and flushed to disk every 30 s.
- **Current normalization** (`:core/bench`), because OEMs disagree on units and sign:
  - Docs define positive = charging and negative = discharging. When unplugged, use `abs(value)`.
  - If the median `|I|` over the first 10 samples is below 20 000 (i.e. < 20 mA if µA), treat the values as mA and multiply by 1000. This heuristic is inferred, so record `unitHeuristicApplied=true` in the session.
  - Discard samples of `Integer.MIN_VALUE` or `0`.
- Power W = `|I_µA| × V_mV / 1e9`.
- At the end of the duration: notification + vibration "Test time reached - return to Thor Emu Tuner". Sampling continues until the user ends the session, capped at duration + 5 min.
- **Fallback:** start and end snapshots of `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh) give the average current = ΔµAh × 3600 / Δs. It is stored as `avgPowerFromCounterW` and used if the service was killed (fewer than 50% of the expected samples).

### 9.3 After the session: result form (user-entered)

- Average FPS read from the overlay (number, required unless the result is "crash"). Target FPS: 30/50/60/other; the default comes from the game's system (PAL detection not attempted).
- Lowest FPS seen (optional).
- Stutter 1-5 (1 = none, 5 = constant).
- Audio: none/minor/major. Graphics issues: none/minor/major.
- Outcome: pass / playable-with-issues / fail / crash.
- Notes (free text, max 500 chars).

### 9.4 Derived metrics (`SessionMetrics`)

avgW and p95W (post-warm-up), peakW, energyWh (whole session), estimated runtime h = `22.2 Wh / avgW` (6000 mAh × 3.7 V; the 3.7 V nominal voltage is inferred, not sourced, so label the result "estimate"), battery temp start/max/delta, worst thermal status, fpsPerWatt = avgFps / avgW, speedRatio = avgFps / targetFps. (Min headroom is deferred, §17.)

### 9.5 Comparison (A/B)

- Pick any two sessions of the same game. The table shows each metric with its delta, and the better value is highlighted. The rules: higher speedRatio, then lower avgW, then lower maxTemp, lower stutter, and better outcome.
- **Not comparable** badge if: duration differs by >20%, start battery temp differs by >5 °C, different emulator version, different performance/fan mode, or either session has `unitHeuristicApplied` set differently.
- History list per game: sessions grouped by revision. Each row shows rev, preset name, date, avg FPS/target, avgW, maxTemp and outcome.

## 10. Data storage: JSON files (not Room)

Everything is stored as JSON files under `filesDir/tuner/` via `kotlinx.serialization`. Each file has `schemaVersion`.

- `settings.json`: ROM roots, emulator folder grants per emulatorId, preferred package per emulator, preferred emulator per system, preferred RetroArch core per system, export folder, flags for one-time hints.
- `library.json`: games plus the scan cache.
- `profiles/<gameKey>.json`: `List<GameProfile>`.
- `sessions/<gameKey>.json`: `List<TestSession>` (samples included, about 300 per 10 min session).
- `azahar_state.json` and `backups/...` (§7).

Why JSON instead of Room:

1. **Testable in `:core`.** Repositories and serialization run in `:core` and are unit-tested without Android. Room needs KSP and Android, and would push this logic into the untestable module.
2. **Small data.** Hundreds of games and tens of sessions each; no queries beyond "by gameKey".
3. **Human-readable export and import.** Backup is a copy.
4. **No migration framework needed.** `schemaVersion` plus an explicit migrate function.

Writes are atomic: write `name.tmp`, then `renameTo`. All repositories are guarded by a `Mutex`.

**Privacy (§15 enforces):**
- Exports contain title, system, game ID, emulator, package versionName, revision values, sessions and metrics.
- Exports do **not** contain tree URIs, document IDs, file paths, device serials, account names, or `Build.SERIAL`/Android ID.
- The app never reads the Android ID or IMEI. No analytics, no crash reporters, no network.

## 11. Screens and UX flow (Compose + Material 3)

Dark theme by default (OLED true black), with dynamic color off for a consistent look. All controls are reachable with a gamepad: D-pad focus order and A = click, B = back (handle `KeyEvent.KEYCODE_BUTTON_B` → back). Minimum touch target 48 dp.

1. **Onboarding** (first run only; re-runnable from Settings).
   1. Welcome: what the app does and does not do; no internet; data stays on device.
   2. ROM folders: "Add folder" (OPEN_DOCUMENT_TREE, `takePersistableUriPermission` read), shown as a list with remove. Tip: pick your ROMs root or per-system folders. The storage root cannot be picked on Android 11+.
   3. Scan: progress with per-system counts, cancellable.
   4. ~~Emulators: choose the default per system~~ (deferred, §17; the first installed FULL emulator is the default and the game screen can switch it).
   5. Config folders (optional, one card per FULL emulator). Each card has its `folderToGrant` text, a "Grant" button (OPEN_DOCUMENT_TREE with read+write persistable), and a live validation tick. For Dolphin: "In the picker, open the menu (≡) and choose Dolphin". "Skip" is always allowed.
2. **Library.** Tabs per system with counts and search. Filters: has profile, tested, unknown ID. Each row shows title, ID chip (method icon: header/filename/manual), emulator icon and last result badge.
3. **Game detail.**
   - Header: title, system, file name, ID (tap to edit).
   - Emulator selector.
   - Card "Current profile": rev N, base preset, and N changes from baseline.
   - Buttons: **Choose baseline**, **Tweak**, **Apply**, **Launch**, **Run test**, **History**.
   - A known-fixes section if `gameSpecific` matches.
4. **Choose baseline.** A list of presets with evidence badge, description and a value table. "Use" creates a new revision with the note "Baseline: <preset>".
5. **Tweak.**
   - Settings grouped by `group`. Enum → dropdown with labels; bool → switch; int/float → slider plus number field; string → text field.
   - Each row shows the description, an impact chip and a "changed" dot versus baseline. Per-row reset. "Show advanced" reveals `advanced` settings.
   - "Save as revision" asks for a note.
   - For reference emulators this screen is read-only and shows `uiPath` → `uiValue`.
6. **Apply.**
   - FULL emulators: target path (relative), a diff preview (old → new for managed keys), and **Write**. For Eden, the INI preview plus "Will be sent when you press Launch".
   - Reference emulators: a checklist the user ticks off.
7. **Launch.** Applies automatically if the active revision is not applied (FULL only), then fires the intent.
8. **Run test.** Pre-flight (§9.1), then a running screen (timer, live W and temp, "End test"), then the result form (§9.3), then a summary.
9. **History and compare.** Sessions grouped by revision; select two to open **Compare** (§9.5), shown as a delta table with the better value highlighted (Canvas bars deferred, §17).
10. **Settings.** Manage ROM folders and emulator folder grants, rescan, preferred packages and cores, export data (JSON via CREATE_DOCUMENT; import deferred, §17), restore Azahar settings, "About & sources" (docs/SOURCES.md bundled as an asset and shown as plain text), licenses.
11. **Both Thor screens.** Every screen must work on the 1920x1080 landscape top display and on the 1080x1240 bottom display: scrollable content, no fixed heights, two-pane layouts only when wide.

## 12. Error handling essentials

- A lost SAF permission (for example after an emulator update, or the user revoked it) is detected with `contentResolver.persistedUriPermissions`. The affected card shows "Re-grant".
- Scanner exceptions per file are caught and logged into `library.json` as `scanError` (message only, no path). One bad file never aborts a scan.
- Every write verifies by read-back (§7.2).
- Log messages must contain no URIs or paths (use `gameKey`).

## 13. Milestones (build in this order; each ends green)

- **M0 Skeleton.** Gradle setup (§3.2), `:core` with one passing test, `.gitignore`, `README.md` (usage, privacy, keystore note), LICENSE (MIT), CI workflow (§14). The `:app` "Hello" screen builds in CI.
- **M1 Presets.** `EmulatorDef` models; `PresetRepository` loads `index.json` and all 13 files. Validation tests (§15 items 6-9).
- **M2 Scanner.** Everything in §6 with synthetic fixtures per format and filename fallbacks. `Scanner` works over a fake `DirectoryLister`.
- **M3 Writers.** `IniDocument` plus the 5 writers, with golden files and the Azahar state and restore logic.
- **M4 Launch planner.** `IntentSpec` resolution for all 13 emulators (unit-tested placeholder substitution), and `SafPaths`.
- **M5 Bench core.** Sample normalization, metrics, comparability rules, comparison ordering.
- **M6 App data layer.** FileJsonStore, SAF lister/byte source/writer, installed-emulator detection, `<queries>`.
- **M7 UI.** Onboarding, then Library, then Game detail, then Baseline, then Tweak, then Apply, then Launch.
- **M8 Test sessions.** Foreground service, pre-flight, result form, history and compare.
- **M9 Polish and release.** Gamepad focus, export/import, About & sources, versioning from the tag, a `v0.1.0` tag producing a GitHub Release.

## 14. GitHub Actions (`.github/workflows/build.yml`)

- **Triggers:** `push` to `main`, `pull_request`, `push` tags `v*`, `workflow_dispatch`.
- **Job `core-tests`** (ubuntu-latest):
  - `actions/checkout@v4`
  - `actions/setup-java@v4` (temurin, 17)
  - `gradle/actions/setup-gradle@v4`
  - `./gradlew :core:test --no-daemon -Pthor.skipAndroid=true`
  - Upload the test reports on failure.
- **Job `privacy-check`** (ubuntu-latest): run `scripts/check-no-pii.sh`. It fails if tracked files match any of:
  - email regex `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`, with allow-list `noreply@github.com` and `@users.noreply.github.com`
  - `/home/[a-z]` or `/Users/` or `C:\\Users`
  - `ANDROID_ID|Settings.Secure.ANDROID_ID|getSerial\(|Build.SERIAL|getImei`
  - Exclude `.git/`, binary files, the script itself and `docs/PLAN.md` (this spec quotes the patterns literally).
  - Third-party handles inside cited source URLs (e.g. a GitHub owner in `docs/SOURCES.md`) are attribution, not user PII, and are allowed.
- **Job `apk`** (needs both):
  - checkout; setup-java 17; `android-actions/setup-android@v3`; setup-gradle.
  - Signing:
    - If secret `SIGNING_KEYSTORE_B64` exists, decode it to `app/release.jks` and use `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
    - Otherwise use the committed **public** keystore `keystore/thor-emu-tuner-public.jks` (alias `thoremutuner`, store/key password `thoremutuner-public`, documented in README as NOT secret). The Coder generates it once, locally, with `keytool -genkeypair -keystore keystore/thor-emu-tuner-public.jks -alias thoremutuner -keyalg RSA -keysize 2048 -validity 10000 -storepass thoremutuner-public -keypass thoremutuner-public -dname "CN=Thor Emu Tuner (public test key)"`. The dname contains no personal data.
    - A committed key keeps signatures stable, so updates install over older builds.
  - `versionName` = tag without `v` (or `0.0.0-dev+<short sha>` when untagged); `versionCode` = `github.run_number`. Pass them via `-PversionName= -PversionCode=`.
  - `./gradlew :app:assembleRelease --no-daemon`, then rename the output to `thor-emu-tuner-<versionName>.apk`, then upload the artifact.
- **Job `release`** (needs apk, `if: startsWith(github.ref, 'refs/tags/v')`, `permissions: contents: write`):
  - download the artifact
  - `softprops/action-gh-release@v2` with `files: thor-emu-tuner-*.apk` and `generate_release_notes: true`
- Only the default `GITHUB_TOKEN` is used; no secrets are required.

## 15. Acceptance criteria (Harsh Critic checklist)

**Build & structure**
1. `./gradlew :core:test` passes in the sandbox with no Android SDK present and without network access to dl.google.com. `settings.gradle.kts` includes `:app` only when an SDK is detected.
2. `:core` has no `android.*`/`androidx.*` imports (checked by a test that greps `core/src/main`).
3. CI workflow has the four jobs in §14. Pushing tag `v0.1.0` produces a GitHub Release with a signed, installable `thor-emu-tuner-0.1.0.apk`. minSdk 30, targetSdk 35 and compileSdk 35 can be verified in `app/build.gradle.kts`.
4. The manifest declares none of: INTERNET, MANAGE_EXTERNAL_STORAGE, READ/WRITE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES. `<queries>` lists every package in the presets (unit test).
5. The privacy check passes. No personal names, emails, local paths or device identifiers exist anywhere in the repo, including git author metadata of the commits (verify `git log --format='%an %ae'`).

**Presets**
6. `index.json` lists 13 files; all parse into `EmulatorDef`; ids are unique; `supportLevel` is `full` for exactly dolphin, ppsspp, eden, azahar, retroarch.
7. Every preset value has `source` and `keySource`. `source` is either `"inferred"` (then `reason` is non-empty) or a URL that appears in `docs/SOURCES.md`. Every `keySource` URL appears in `docs/SOURCES.md` (unit test reads the file).
8. For FULL emulators, every preset value's (section, key) exists in `settings[]`. Enum values must be one of the declared options; ints and floats must be within min/max when set.
9. No generic preset for Dolphin contains a `Video_Hacks` key (test).

**Scanner**
10. Unit tests with synthetic fixtures detect correct system and ID for: GC ISO, Wii ISO, WBFS, RVZ, GC CISO, PSP ISO (UMD_DATA.BIN and PARAM.SFO paths), PSP CSO, PBP, PS2 ISO (BOOT2), PS1 raw BIN via CUE (MODE2/2352), 3DS CCI (title ID + product code), NDS, GBA, N64 in all 3 byte orders, Switch filename, `.psvita`. (GCZ decoding and NSP `.tik` parsing are deferred, §17; GCZ files fall back to filename IDs.)
11. PSP CSO vs GC CISO disambiguation is tested (same magic).
12. The filename fallback regexes are tested with at least 3 positive and 2 negative cases each.
13. Probes never read more than 256 KiB (+64 dir sectors). A counting `ByteSource` test asserts this.
14. `.cue`/`.m3u`/`.gdi` grouping hides member files (test).

**Writers**
15. `IniDocument` round-trips fixtures byte-identically; `set` preserves comments and unrelated keys.
16. Golden-file tests for each of the 5 writers, from the "Thor Balanced" (RetroArch: "Thor Low Latency") preset.
17. Eden output: every key has `\use_global=false` and a correct `\default`; enum values are integers; `use_docked_mode` is under `[System]` and `resolution_setup` under `[Renderer]`.
18. Azahar output writes booleans as `true/false`, never `1/0`, and "restore" returns config.ini to the original values for touched keys (test).
19. PPSSPP file name is `<DISCID>_ppsspp.ini` (not `_game.ini`) and never contains `GraphicsBackend`.
20. Writers are pure functions of `(existingText, values)`, with no I/O in `:core`.

**Launch**
21. `LaunchPlanner` tests compare against **hand-written** expected intents (component, action, categories, data, extras, flags) for Dolphin, PPSSPP, Eden, RetroArch and NetherSX2, and check that every `(package, activity)` target of all 13 emulators resolves with no leftover placeholders. `{romPath}` conversion is tested for `primary:` and `XXXX-XXXX:` ids. An unsupported id yields a typed error.
22. Eden launch uses action `dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG` with extras `title_id` and `custom_settings`, and falls back to VIEW when there is no title ID.

**Bench**
23. Unit tests: µA vs mA heuristic, sign handling, Integer.MIN_VALUE filtering, warm-up exclusion, energy integration (trapezoid), charge-counter fallback, and each comparability rule.
24. The app records the emulator versionName and the revision in every session. The result form enforces required fields.

**App behaviour (verified by reading code, plus the Manager's on-device checklist)**
25. First run shows onboarding, persists ROM tree grants across restarts, and scan results appear in Library grouped by system.
26. Every write goes through backup → write "wt" → read-back verify. The fallback export works when no tree is granted.
27. Reference-only emulators never trigger a file write.
28. Gamepad: every screen is fully operable with D-pad + A/B.
29. No UI text claims "tested on Thor" for presets whose evidence is `inferred`.

**Added at Manager checkpoint 1**
30. A `:core` user-journey test: a fake lister with a synthetic PSP ISO and a Dolphin ISO; IDs detected; baseline applied; one user edit; rev 2 saved; writer output matches a golden file; `LaunchPlanner` intent matches.
31. Evidence rule enforced by a unit test (§5).
32. Reference emulators support manual revisions (§2) and never write files.
33. The UI works on both Thor screens (§11.11).

## 16. Uncertainties to verify on a real Thor (Manager's device checklist)

1. **URI delegation.** Can a SAF document URI from our tree grant be delegated with `FLAG_GRANT_READ_URI_PERMISSION` to Dolphin, PPSSPP and Azahar? If it cannot, the hint in §8.6 applies; ES-DE notes most emulators need their own folder grant anyway.
2. **Dolphin picker.** Dolphin's DocumentsProvider root may not appear in the tree picker on the Thor's Android 13 build.
3. **Eden prompts.** Eden's `LAUNCH_WITH_CUSTOM_CONFIG` path requires Eden's own game list to contain the title. The confirmation and overwrite prompts are expected, not bugs.
4. **Azahar settings reload.** Azahar re-reads config.ini on each EmulationActivity creation (`loadSettings()`), but a still-running process might keep native settings. If the first apply shows stale values, instruct the user to swipe Azahar away first.
5. **Azahar layout values.** `secondary_display_layout = 4` (Opposite of primary) needs Azahar 2126.0 or later; use 2 on older builds. v0.1 lets the user pick 4 or 2 in the Tweak screen (version parsing deferred, §17).
6. **RetroArch override location.** On the retroarch.com build, overrides live in `/storage/emulated/0/RetroArch/config` only if RetroArch can write shared storage; otherwise they are in Android/data and need export plus manual copy.
7. **melonDS action.** MelonDualDS's action string is `me.magnum.melondualds.LAUNCH_ROM` or `me.magnum.melonds.LAUNCH_ROM`; try both.
8. **Current sensor.** Check the Thor's `CURRENT_NOW` unit and sign; the heuristic in §9.2 should be confirmed with one plugged/unplugged reading.
9. **Switch rights ID.** The NSP `.tik` → title ID rule is a community convention and was not verified from fetched source.
10. **Community-derived values.** All community-derived numeric choices (Dolphin 3x, PPSSPP 4x, NetherSX2 3x) come from search snippets of blocked sites. They are marked `inferred` and must be validated with the app's own test sessions.

## 17. Deferred to after v0.1 (Manager checkpoint 1)

Listed as "Planned" in the README; not required by §15:

- GCZ decoding (files are recognized; IDs come from the filename) and NSP `.tik` title IDs (filename IDs only).
- A rendered in-app SOURCES.md viewer (v0.1 shows the bundled text as plain text).
- Data import (export stays).
- Canvas charts in Compare (v0.1 shows a delta table).
- Thermal headroom sampling and "min headroom".
- Azahar versionName parsing for the layout value (the user picks 4 or 2).
- The onboarding "default emulator per system" step.

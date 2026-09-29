# Sources

Every `source` / `keySource` URL in `core/src/main/resources/presets/*.json` appears in the "Fetched" lists below. Every URL in those lists was downloaded and read during planning (September 2026). `raw.githubusercontent.com` and GitLab raw URLs were read with curl; `github.com` pages were read through a web-fetch tool.

Values that come only from community guides we could not open are marked `"source": "inferred"` with a `reason`. Where a reason names a community page, that page appears under "Search-result snippets only". We saw those pages only as search-engine snippets because the research sandbox's egress policy blocked the sites.

## Fetched: frontend and launch commands (ES-DE)

| URL | Note |
|---|---|
| https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_systems.xml | Exact Android launch commands (actions, extras, flags) per emulator and file extensions per system. |
| https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_find_rules.xml | Package/activity names for every emulator variant. |
| https://gitlab.com/es-de/emulationstation-de/-/raw/master/ANDROID-DEV.md | Storage model: FileProvider vs SAF URIs, and that emulators often need their own folder grants. Also covers where to get each emulator. |
| https://gitlab.com/es-de/emulationstation-de/-/raw/master/USERGUIDE.md | `.psvita` files contain the Vita title ID. |
| https://gitlab.com/es-de/emulationstation-de/-/raw/master/es-app/src/FileData.cpp | %INTERNALDATA% = /data/user/<id>, %EXTERNALDATA% = /storage/emulated/<id>. |

## Fetched: Dolphin

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/Core/Config/GraphicsSettings.cpp | GFX keys: InternalResolution, ShaderCompilationMode, MaxAnisotropy, VSync, ShowFPS, DriverLibName, hacks. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/Core/Config/MainSettings.cpp | Core keys: GFXBackend, CPUCore, CPUThread, Overclock, SyncOnSkipIdle. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/Core/ConfigLoaders/GameConfigLoader.cpp | Game INI filenames and legacy section-name mapping. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/VideoCommon/VideoConfig.h | Enums: ShaderCompilationMode, AspectMode, AnisotropicFilteringMode. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/Core/PowerPC/PowerPC.h | CPUCore enum (JITARM64 = 4). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/VideoBackends/Vulkan/VideoBackend.h | Backend config name "Vulkan". |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/VideoBackends/OGL/VideoBackend.h | Backend config name "OGL". |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/Common/CommonPaths.h | GameSettings dir name. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Android/app/src/main/AndroidManifest.xml | Activities; DocumentsProvider authority `${applicationId}.user`. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/features/DocumentProvider.kt | The provider exposes the user dir with FLAG_SUPPORTS_IS_CHILD, so it can be picked as a SAF tree. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/DirectoryInitialization.kt | The user dir is getExternalFilesDir(null) (or legacy /sdcard/dolphin-emu). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/StartupHandler.kt | Launch intent parsing order: clipData, data URI, AutoStartFiles, AutoStartFile. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/GMS.ini | Built-in required hacks (Super Mario Sunshine). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/GZL.ini | Built-in required hacks (Wind Waker). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/RMG.ini | Built-in required hacks (Mario Galaxy). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/GZ2.ini | Built-in required hacks (Twilight Princess GC). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/WbfsBlob.cpp | WBFS header: disc header copy at hd_sector_size. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/WIABlob.h | WIA/RVZ magic; header1 is 0x48 bytes; header2 embeds the 0x80-byte disc header. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/CISOBlob.h | GC CISO: "CISO", block_size, map; data at 0x8000. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/CompressedBlob.h | GCZ header (magic 0xB10BC001). |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/CompressedBlob.cpp | GCZ block pointers, hashes, uncompressed flag bit 63. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/DiscUtils.h | GC magic 0xC2339F3D, Wii magic 0x5D1C9EA3. |
| https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Source/Core/DiscIO/VolumeDisc.cpp | NKit marker. |

## Fetched: PPSSPP

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/Config.cpp | Keys, sections ([Graphics], [CPU]) and which settings are PER_GAME. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/ConfigValues.h | Enums (CPUCore, GPUBackend, TextureFiltering, SkipGPUReadbackMode, ShowStatusFlags). |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/ConfigSettings.cpp | Missing per-game keys keep global values. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/Util/PathUtil.cpp | Per-game file = `<gameId>_ppsspp.ini` in PSP/SYSTEM. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/UI/NativeApp.cpp | The memstick dir is user-selectable (memstick_dir.txt). |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/UI/GameSettingsScreen.cpp | UI value lists (resolution, AF, MSAA, readback modes); MSAA warning on tiling GPUs. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/UI/GameInfoCache.cpp | Game ID = PARAM.SFO DISC_ID. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/android/AndroidManifest.xml | VIEW intent filter (file/content). |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/assets/compat.ini | Built-in per-game compatibility fixes. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/FileSystems/BlockDevices.cpp | CSO header/index format, raw deflate, 0x80000000 plain flag. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/ELF/ParamSFO.cpp | PARAM.SFO header and index-table layout. |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/ELF/PBPReader.h | PBP header (magic, version, 8 offsets). |
| https://raw.githubusercontent.com/hrydgard/ppsspp/master/Core/PSPLoaders.cpp | UMD_DATA.BIN holds the disc ID line. |

## Fetched: Eden (Switch)

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/frontend_common/config.cpp | Per-game file `config/custom/<name>.ini`; `\use_global` / `\default` key format. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/common/settings.h | Setting labels, categories, defaults (Android defaults: Handheld, GPU accuracy Low). |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/common/settings_enums.h | Enum orders (ResolutionSetup, ScalingFilter, VSyncMode, GpuAccuracy, ...). |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/common/settings_setting.h | Enums are serialized as integers, bools as true/false. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/common/settings.cpp | Category to section name mapping. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/AndroidManifest.xml | EmulationActivity filters (TECH_DISCOVERED, VIEW, LAUNCH_WITH_CUSTOM_CONFIG); DocumentProvider. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/java/org/yuzu/yuzu_emu/utils/CustomSettingsHandler.kt | Custom-config launch: action, `title_id`, `custom_settings`, [GpuDriver] driver_path. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/java/org/yuzu/yuzu_emu/fragments/EmulationFragment.kt | handleEmuReadyIntent flow (confirmation, overwrite prompt). |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/java/org/yuzu/yuzu_emu/activities/EmulationActivity.kt | Intent handling. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/java/org/yuzu/yuzu_emu/features/DocumentProvider.kt | User dir exposed as a document provider. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/java/org/yuzu/yuzu_emu/utils/DirectoryInitialization.kt | User dir = getExternalFilesDir(null). |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/jni/native_config.cpp | Per-game config name = program ID `%016X`. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/android/app/src/main/jni/android_config.cpp | [GpuDriver] category on Android. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/core/file_sys/partition_filesystem.h | PFS0 header (0x10) and entry (0x18) layout. |
| https://raw.githubusercontent.com/eden-emulator/mirror/master/src/core/file_sys/submission_package.cpp | NSP contents and title-ID masks. |
| https://raw.githubusercontent.com/eden-emulator/eden-overrides/master/overrides.ini | Curated game-specific overrides. |
| https://github.com/eden-emulator/eden-overrides/blob/master/README.md | overrides.ini format and conditions. |

## Fetched: Azahar (3DS)

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/jni/default_ini.h | All config.ini keys with value docs. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/jni/config.cpp | Config path = <user>/config/config.ini. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/common/settings.h | Enums: GraphicsAPI, LayoutOption, SecondaryDisplayLayout, TextureFilter. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/AndroidManifest.xml | EmulationActivity VIEW filter. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/activities/EmulationActivity.kt | loadSettings() on every launch. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/fragments/EmulationFragment.kt | Opens intent URI via ContentResolver. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/utils/DirectoryInitialization.kt | User dir is a SAF tree URI chosen by the user. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/features/settings/utils/SettingsFile.kt | Kotlin INI parsing (`toBoolean()` for bools). |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/features/settings/model/BooleanSetting.kt | Which keys are booleans, with sections and defaults. |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/java/org/citra/citra_emu/features/settings/model/IntSetting.kt | Int keys and defaults (secondary_display_layout default 4). |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/core/file_sys/ncch_container.h | NCSD/NCCH header layouts (title ID, product code). |
| https://raw.githubusercontent.com/azahar-emu/azahar/master/src/core/file_sys/cia_container.h | CIA header size (title ID parsing deferred). |
| https://github.com/azahar-emu/azahar/pull/1385 | "Opposite of primary" secondary layout, recommended for two-screen devices; merged for 2126.0. |
| https://github.com/azahar-emu/azahar/issues/38 | Android per-game settings: still open. |
| https://github.com/azahar-emu/azahar/issues/2128 | Duplicate of #38. |
| https://github.com/azahar-emu/azahar/issues/1437 | Thor plus external display quirk; closed as not planned. |

## Fetched: RetroArch / libretro

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/libretro/RetroArch/master/configuration.c | Override lookup (core library_name / game name); run-ahead key names. |
| https://raw.githubusercontent.com/libretro/RetroArch/master/frontend/drivers/platform_unix.c | Android default dirs: /storage/emulated/0/RetroArch/config when writable. |
| https://raw.githubusercontent.com/libretro/RetroArch/master/retroarch.cfg | Key names and defaults for video/audio keys. |
| https://raw.githubusercontent.com/libretro/RetroArch/master/file_path_special.h | `.cfg` extension constant. |
| https://raw.githubusercontent.com/libretro/docs/master/docs/guides/overrides.md | Override hierarchy and paths. |
| https://raw.githubusercontent.com/libretro/docs/master/docs/guides/runahead.md | Run-ahead semantics and cost. |
| https://raw.githubusercontent.com/libretro/snes9x/master/libretro/libretro.cpp | library_name "Snes9x". |
| https://raw.githubusercontent.com/libretro/Genesis-Plus-GX/master/libretro/libretro.c | library_name "Genesis Plus GX". |
| https://raw.githubusercontent.com/libretro/pcsx_rearmed/master/frontend/libretro.c | library_name "PCSX-ReARMed". |
| https://raw.githubusercontent.com/libretro/mupen64plus-libretro-nx/develop/libretro/libretro.c | library_name "Mupen64Plus-Next". |
| https://raw.githubusercontent.com/libretro/libretro-core-info/master/mgba_libretro.info | corename "mGBA". |
| https://raw.githubusercontent.com/libretro/libretro-core-info/master/fceumm_libretro.info | corename "FCEUmm". |

## Fetched: PS2 / PS1 / others

| URL | Note |
|---|---|
| https://raw.githubusercontent.com/Trixarian/NetherSX2-patch/main/README.md | NetherSX2 tips: Vulkan, Disable Readbacks, 4248 vs 3668 variants. |
| https://raw.githubusercontent.com/PCSX2/pcsx2/master/pcsx2/Pcsx2Config.cpp | Key names (EmuCore/GS Renderer, upscale_multiplier, EmuCore/Speedhacks). |
| https://raw.githubusercontent.com/PCSX2/pcsx2/master/pcsx2/Config.h | GSRendererType and GSHardwareDownloadMode enums (upstream). |
| https://raw.githubusercontent.com/PCSX2/pcsx2/master/pcsx2/Elfheader.cpp | ELF CRC = XOR of 32-bit words. |
| https://raw.githubusercontent.com/PCSX2/pcsx2/master/pcsx2/CDVD/CDVD.cpp | SYSTEM.CNF BOOT2 parsing. |
| https://raw.githubusercontent.com/PCSX2/pcsx2/master/pcsx2/VMManager.cpp | gamesettings/<SERIAL>_<CRC>.ini naming. |
| https://raw.githubusercontent.com/stenzek/duckstation/master/src/core/settings.cpp | DuckStation core keys. |
| https://raw.githubusercontent.com/stenzek/duckstation/master/src/core/system.cpp | gamesettings/<SERIAL>.ini naming. |
| https://raw.githubusercontent.com/rafaelvcaetano/melonDS-android/master/app/src/main/AndroidManifest.xml | `${applicationId}.LAUNCH_ROM` action. |
| https://raw.githubusercontent.com/rafaelvcaetano/melonDS-android/master/app/src/main/java/me/magnum/melonds/ui/emulator/EmulatorActivity.kt | Extra key "uri". |
| https://raw.githubusercontent.com/rafaelvcaetano/melonDS-android/master/app/src/main/res/xml/pref_video.xml | SharedPreferences keys and defaults. |
| https://raw.githubusercontent.com/rafaelvcaetano/melonDS-android/master/app/src/main/res/values/arrays.xml | Allowed values. |
| https://raw.githubusercontent.com/melonDS-emu/melonDS/master/src/NDS_Header.h | NDS header: title 0x00, game code 0x0C. |
| https://raw.githubusercontent.com/mgba-emu/mgba/master/include/mgba/internal/gba/gba.h | GBA cartridge header: title 0xA0, id 0xAC. |
| https://raw.githubusercontent.com/mupen64plus/mupen64plus-core/master/src/main/rom.c | N64 z64/v64/n64 signatures. |
| https://raw.githubusercontent.com/mupen64plus/mupen64plus-core/master/src/api/m64p_types.h | N64 header offsets (Name 0x20, IDs 0x38-0x3E). |
| https://raw.githubusercontent.com/mupen64plus-ae/mupen64plus-ae/master/app/src/main/assets/mupen64plus_data/profiles/emulation.cfg | Built-in emulation profiles and their target device classes. |
| https://raw.githubusercontent.com/Vita3K/Vita3K/master/vita3k/config/include/config/config.h | Vita3K config.yml keys and defaults. |
| https://raw.githubusercontent.com/flyinghead/flycast/master/core/cfg/option.cpp | Flycast option names and defaults. |
| https://raw.githubusercontent.com/flyinghead/flycast/master/core/cfg/option.h | Per-game sections keyed by game ID. |
| https://raw.githubusercontent.com/brunodev85/winlator/main/README.md | Winlator stability and audio tips. |
| https://github.com/Desage56/ayn-thor-claude-skill/blob/main/thor-handheld/references/emulators.md | Third-party Thor software stack notes (packages, BIOS needs). |
| https://raw.githubusercontent.com/hacan359/odin-compatibility/main/README.md | Points to the community Odin 2 compatibility spreadsheet (docs.google.com, not reachable from the sandbox). |

## Fetched: Android platform

| URL | Note |
|---|---|
| https://developer.android.com/training/data-storage/shared/documents-files | On Android 11+, OPEN_DOCUMENT_TREE cannot select the storage root, Download, Android/data or Android/obb. |
| https://developer.android.com/reference/android/os/BatteryManager | CURRENT_NOW is in µA (positive = charging, negative = discharging); getIntProperty returns Integer.MIN_VALUE when unsupported (targetSdk >= 28); ENERGY_COUNTER in nWh. |
| https://developer.android.com/reference/android/os/PowerManager | getCurrentThermalStatus (API 29), THERMAL_STATUS_* values 0-6, getThermalHeadroom (API 30, at most about 1 call/s, else NaN). |

## Search-result snippets only (sites blocked from the research sandbox)

We saw these only as search-engine snippets. They motivate some `"inferred"` values, and each such `reason` field names the site. The Harsh Critic should treat them as unverified.

- https://retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide/: Thor dual-screen guide.
- https://retrogamecorps.com/2022/05/28/ayn-odin-starter-guide/: Odin 2 guide. Snippets: most GC/Wii games run at 3x; switching Dolphin to Vulkan can help; PPSSPP 4x (1080p).
- https://retrohandhelds.gg/ayn-thor-setup-guide/: Thor setup guide. Snippet: MelonDualDS recommended; Azahar is the 3DS choice.
- https://www.joeysretrohandhelds.com/guides/ayn-thor-setup-guide/: Thor guide. Snippets: ARMSX2 vs NetherSX2; Azahar wants decrypted .cci.
- https://www.joeysretrohandhelds.com/resources/best-custom-drivers-for-snapdragon-devices/: Snippet: custom drivers show no clear benefit on the Thor.
- https://www.joeysretrohandhelds.com/guides/eden-android-setup-guide/: Snippet: per-game Docked/Handheld and async shaders.
- https://aliteq.com/how-to-set-up-azahar-3ds-emulator-2026 and https://droix.net/blogs/ayn-thor-dual-screen-gaming-handheld/: Snippets: Azahar on Thor uses Single Screen plus Secondary Display = Bottom Screen.
- https://retroresolve.com/guides/ayn-odin-2-emulation-performance-and-results/: Snippet: PPSSPP God of War: Ghost of Sparta at 60 FPS even at 10x on Odin 2.
- https://nethersx2.org/settings-nethersx2/ and https://gamehelptech.com/aethersx2-best-settings/: Snippets: 3x-4x on SD8G2, Disable Readbacks, EE cycle rate 100%.
- Amazon / Retro Catalog spec listings: Thor = Snapdragon 8 Gen 2, Adreno 740, Android 13, 6" 1920x1080 120 Hz top panel, 3.92" 1080x1240 60 Hz bottom panel, 6000 mAh.

## Hosts that were blocked or unreachable

reddit.com, retrogamecorps.com, retrohandhelds.gg, joeysretrohandhelds.com, adinwalls.com, onyxretrocorner.com, aliteq.com, droix.net, handheldrank.com, ppsspp.org, dolphin-emu.org, wiki.dolphin-emu.org, docs.libretro.com, pcsx2.net, vita3k.org, git.eden-emu.dev, 3dbrew.org, gbatek, n64brew, psdevwiki, switchbrew, wiibrew, en.wikipedia.org, docs.google.com, dl.google.com.

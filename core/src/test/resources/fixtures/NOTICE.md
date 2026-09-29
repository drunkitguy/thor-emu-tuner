# Test fixtures (third-party excerpts)

These files are used only by `:core` unit tests to prove that the INI model round-trips real emulator
config files byte for byte. They are not shipped in the app.

| File | Origin | License of origin |
|---|---|---|
| `dolphin_GMS.ini` | Verbatim copy of https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/GMS.ini | GPL-2.0-or-later (Dolphin Emulator Project) |
| `ppsspp_compat_head.ini` | First 50 lines of https://raw.githubusercontent.com/hrydgard/ppsspp/master/assets/compat.ini | GPL-2.0-or-later (PPSSPP Project) |
| `retroarch_cfg_head.cfg` | First 50 lines of https://raw.githubusercontent.com/libretro/RetroArch/master/retroarch.cfg | GPL-3.0 (libretro / RetroArch) |
| `azahar_config.ini` | Hand-assembled `config.ini` excerpt: comment text copied from https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/jni/default_ini.h, values filled in as Azahar writes them | GPL-2.0-or-later (Citra / Azahar Emulator Project) |

Copyright remains with the respective projects.

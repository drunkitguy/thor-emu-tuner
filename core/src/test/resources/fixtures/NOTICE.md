# Test fixtures (third-party excerpts)

**The fixture files in this folder are NOT covered by this repository's MIT license.** Each one is an
excerpt of an emulator's configuration file and stays under its original license and copyright,
listed below. The full license texts are in `LICENSE-GPL-2.0.txt` and `LICENSE-GPL-3.0.txt` (copied
from https://github.com/spdx/license-list-data).

They are used only by `:core` unit tests, to prove that the INI model round-trips real emulator config
files byte for byte. They are not shipped in the app. The license files and this notice are not
fixtures and no test reads them as such.

| File | Origin | License (SPDX) |
|---|---|---|
| `dolphin_GMS.ini` | Verbatim copy of https://raw.githubusercontent.com/dolphin-emu/dolphin/master/Data/Sys/GameSettings/GMS.ini (Dolphin Emulator Project) | `GPL-2.0-or-later` |
| `ppsspp_compat_head.ini` | First 50 lines of https://raw.githubusercontent.com/hrydgard/ppsspp/master/assets/compat.ini (PPSSPP Project) | `GPL-2.0-or-later` |
| `retroarch_cfg_head.cfg` | First 50 lines of https://raw.githubusercontent.com/libretro/RetroArch/master/retroarch.cfg (libretro / RetroArch) | `GPL-3.0-or-later` |
| `azahar_config.ini` | Hand-assembled `config.ini` excerpt: comment text copied from https://raw.githubusercontent.com/azahar-emu/azahar/master/src/android/app/src/main/jni/default_ini.h, values filled in as Azahar writes them (Citra / Azahar Emulator Project) | `GPL-2.0-or-later` |

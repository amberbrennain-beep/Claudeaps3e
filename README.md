# OneLineage

 **NOTE: OneLineage is just a proof of concept, this repo should provide you with enough to port other roms to bootloader locked devices, the lineageos with this repo is just a demo of this capability, and therefore does not fully work. Have fun!**

Hot-switch between stock **One UI** and a **LineageOS GSI** (LineageOS 23.x on Android 16, or LineageOS 24 on Android 17) on a rooted Samsung phone, with no reflashing and no second partition. OneLineage stages the GSI under `/data/local/tmp`, pivots into it as a container with its own zygote and system_server, and can hand the screen back to One UI.

> ## Disclaimer
> **This has been tested on only a handful of devices. The bundled framework patches were made for a LineageOS 23.x (Android 16) guest on a One UI 8.x (Android 16) host; the Android 17 path (see below) is wired up but has not been exercised on hardware yet.**
> It may not work on other Samsung models, other One UI versions, or other devices at all. It is experimental, it replaces core parts of a running system, and it can crash, hang or soft-brick your phone, fill `/data`, or break radio/SMS. **Back up everything first. Use at your own risk.**
> Also, I did use AI a fair bit in this project, it would have taken a LOT longer without it.

## Requirements
- A rooted Samsung phone from the Galaxy S26 series (the original testing was on an S26 Ultra on One UI 8.5; the S26 / S26+ Snapdragon share the same vendor layout and need no script changes)
- Enough free space on `/data` for the extracted GSI
- Internet access for the GSI download (MisterZtr or Andy Yan LineageOS GSI builds from SourceForge, or any direct URL)

## Android 17 / One UI 9 (Galaxy S26, S26+, S26 Ultra)

One UI 9 moves the S26 series to Android 17 (SDK 37). The app and scripts now handle that in three ways:

1. **Release line selector.** The image card offers *LineageOS 23 • Android 16*, *LineageOS 24 • Android 17* and *Custom URL*. The default follows the phone: an Android 17 host preselects the LineageOS 24 line. The newest build for the chosen line and variant is looked up on SourceForge at download time (MisterZtr `LineageOS/Android 17` and `Android 17(native)` folders, then Andy Yan `lineage-24*` folders), so a new upload is picked up without rebuilding the app.
2. **No Android 17 LineageOS GSI exists yet.** LineageOS 24 was released on 11 Oct 2026, but as of 26 Oct 2026 neither MisterZtr nor Andy Yan has published a LineageOS 24 GSI (MisterZtr's newest is 23.2 from 24 May 2026). Until one appears the Android 17 line reports exactly that, and *Custom URL* accepts any direct link to an arm64 system-as-root GSI (`.7z`, `.img.xz`, `.zip` or raw `.img`; ext4 or erofs, not sparse).
3. **Generation-gated patches.** `master_prepare.sh` reads the host SDK (`getprop`) and the guest SDK (`system/build.prop` inside the image). The bundled `services_patched.jar`, `framework_patched.jar` and `PermissionController_patched.apk` were built for SDK 36 and are only bound when the matching side is SDK 36. For another generation, drop in files named with the SDK suffix (`services_patched_37.jar`, `framework_patched_37.jar`, `PermissionController_patched_37.apk`) and they are picked up automatically. The bundled `clean_apex_info_list.xml` (a One UI 8.x snapshot) is likewise only used on an SDK 36 host; otherwise the host's live `/apex/apex-info-list.xml` is used. Extra GSI-side APEX payloads for a newer guest can be listed in `/data/local/tmp/lineage_stage/extra_payloads.txt` (`<name> <module>` per line).

What this means in practice on a One UI 9 S26: the container will mount and pivot into an Android 17 GSI, but whatever the Android 16 jar patches fixed (the services/framework edits were never documented upstream beyond "patched") is not fixed there until someone produces the SDK 37 equivalents. Expect to iterate with `takeover.log`, `boot_failures.log` and logcat exactly as the original port did.

The container also records what it is running in `/data/local/tmp/lineage_stage/image_info` (name, Android version, filesystem, source URL) and `runtime_info` (host/guest SDK, filesystem), which the app shows in the image status line.

## What's in here
| Path | What it is |
| --- | --- |
| `src/`, `res/`, `AndroidManifest.xml` | The Android app (`com.wewaffles.onelineage`) |
| `assets/master_prepare.sh` | Stages the rootfs (ext4 or erofs), binds the generation-matched patched files, fixes labels/ownership |
| `assets/master_inner.sh` | Runs inside the container, starts the guest zygote |
| `assets/switch_to_lineage.sh` / `return_to_oneui.sh` | Switch in and back out |
| `assets/zygote_exec` | Static aarch64 launcher for the guest zygote |
| `build_apk.sh` | Builds and signs the APK |

## Patches

### Patched system files (bind-mounted over the GSI, only when the SDK matches)
- `services_patched.jar` over `system/framework/services.jar` (guest SDK 36)
- `framework_patched.jar` over `system/framework/framework.jar` (guest SDK 36)
- `PermissionController_patched.apk`: removes the `android.permission.RANGING` reference so runtime-permission dialogs stop crashing (Samsung's One UI 8.x PermissionController needs a permission the LineageOS 23.2 platform doesn't define; host SDK 36)
- Optional bind-mounts when present: `framework-res_patched.apk`, `libaudio_aidl_conversion_common_cpp_patched.so`, `libaudioclient_aidl_conversion_patched.so`
- Any of the above with a `_<sdk>` suffix (for example `services_patched_37.jar`) is used for that guest/host SDK instead

### Boot and environment fixes
1. **APEX paths in the container.** Host `/system/apex` and `/system_ext/apex` are bound into the rootfs after the GSI payloads are extracted, so PackageManagerService stops dying in `scanApexPackages`.
2. **SELinux labels on the staged `/data`.** The tree is relabeled like a real `/data` (`system_data_file`, per-package `app_data_file`) instead of `shell_data_file`. Also `setenforce 0` during the takeover and `setenforce 1` on return.
3. **Audio hang.** Host `audioserver`/`cameraserver` are left running (they used to be stopped/frozen, which deadlocked `AudioService`). Only the host zygote is suppressed.
4. **Runaway `sf.log`.** SurfaceFlinger output goes to `/dev/null` (it was growing to hundreds of MB in minutes).
5. **App data ownership.** `fix_app_dirs.sh` re-owns app data dirs to the uid PackageManager assigned, strips write bits from dex/apk/jar files (ART refuses writable dex), and creates missing dirs such as `/data/misc/bluedroid`.
6. **No installd.** `ensure_app_storage.sh` plus a 15 s sweeper create `/data/user/0`, `/data/user_de/0` and shared-storage dirs for new installs, so apps installed inside Lineage can launch.
7. **Gesture navigation.** Overlay idmaps are generated with `idmap2 --ignore-overlayable` before zygote starts.
8. **Bluetooth crash loop** fixed by creating `/data/misc/bluedroid` before boot.
9. **adb.** `Settings.Global.ADB_ENABLED` is pre-seeded, host `adb_keys` are copied into the guest, and `adb_watchdog.sh` keeps adbd and the USB gadget alive.
10. **Networking.** The preferred APN is switched to `IPV4V6`, and `net_fix.sh` maintains a private routing table (4242) because the guest's ConnectivityService can't program the host's netd.
11. **SMS.** `ims_watchdog.sh` keeps One UI's `imsd` running and `switch_to_lineage.sh` waits for voice registration before pivoting.
12. **Boot diagnostics.** Each boot writes `/data/boot_failures.log`.

## Known issues
- Returning to One UI may still need a manual reboot.
- The guest binds the host's `media.codec`, whose AIDL hash differs from the GSI's, which can cause high CPU during codec enumeration.
- No IMS stack in the guest, so **voice calls don't work** (data and SMS depend on One UI's `imsd`).
- Developer options crash (the guest registers no `adb` service).
- DTMF tones glitch, and deep-buffer audio hangs, so it stays disabled.
- Never kill the host `netd` while Lineage is running.

## Building
`./build_apk.sh` needs a JDK 17+ on `PATH` (or `JAVA_HOME`) and an Android SDK. It finds the SDK through `ANDROID_HOME` (falling back to the default macOS/Linux locations), uses the newest installed build-tools, and compiles against `android-36` or the newest installed platform. Override with `ANDROID_HOME`, `BUILD_TOOLS_VER`, `PLATFORM_API`, `KEYSTORE` and `OUT_APK`. A debug keystore is generated under `keystore/` on first run; the APK lands in `build/OneLineage.apk`.

## Credits
LineageOS GSI builds by MisterZtr and Andy Yan (SourceForge). LineageOS and Android are trademarks of their respective owners. Not affiliated with Samsung, Google or LineageOS.

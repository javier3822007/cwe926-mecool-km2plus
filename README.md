# CWE-926: Exported BootService in MECOOL KM2 Plus OTA System App

## Vulnerability Summary

| Field | Value |
|---|---|
| **CWE** | [CWE-926](https://cwe.mitre.org/data/definitions/926.html) — Improper Export of Android Application Components |
| **Product** | MECOOL KM2 Plus (Amlogic S905X4-B, Android TV 14) |
| **Vulnerable component** | `com.sdt.ota.BootService` in `SdtUpdate.apk` (version 2.5.1, versionCode 20501) |
| **Affected firmware** | C1.2.7_20250822 (confirmed), C1.3.5_20261111 (confirmed, latest available) |
| **Impact** | Integrity / Availability — forced OTA update, recovery mode, or factory reset (complete user data loss) from an unprivileged app |
| **Fix available** | No |
| **Discovered by** | Javier Aguilar |

## Description

The `BootService` component (`com.sdt.ota.BootService`) in the SdtUpdate system application on MECOOL KM2 Plus devices is declared as `exported="true"` in the Android manifest **without a `permission` attribute**. This allows any application installed on the device — including one declaring **zero permissions** — to send an intent that starts the privileged OTA update chain.

`BootService.onStartCommand` processes the caller's intent without verifying the caller's identity or permissions, and forwards it to `CoreService`, which runs as a foreground service holding:

- `android.permission.RECOVERY`
- `android.permission.REBOOT`
- `android.permission.INSTALL_PACKAGE_UPDATES`

The SdtUpdate app is a priv-app signed with the SKYWORTH platform key (`CN=skydigital, O=SKYWORTH`, SHA-1 `1C:6A:0B:B5:70:10:A7:7C:BF:29:A4:95:FF:55:F7:36:2F:E3:32:A3`). The firmware and apps are developed by Skyworth Digital Technology (SDT).

### What this is NOT

This is **not remote code execution**. The OTA package signature verification in `update_engine` prevents installation of unsigned firmware. However, the vulnerability does allow:

- Triggering a system update dialog without user consent
- Forcing the device into recovery mode
- Initiating a **factory reset** (complete user data loss) using a legitimately signed vendor OTA package that includes the `ota-wipe=yes` flag
- Repeated reboots / recovery cycles (denial of service)

### Developer awareness

The developer demonstrably knows how to protect components. In the same APK manifest:

| Component | `exported` | `permission` |
|---|---|---|
| **BootService** | `true` | *(none)* |
| CoreService | `false` | — |
| ProfileInstallReceiver | `true` | `android.permission.DUMP` |

## Attack Chain

```
Unprivileged app (UID 10014, 0 granted permissions)
  → startForegroundService(Intent to BootService)
    → BootService.onStartCommand (no caller verification)
      → m777b() / m778c() (depending on A/B slot)
        → BootManager.m785d(OtaOrigin.Local)
          → startForegroundService(CoreService)
            → update_engine
```

## Execution Evidence

Confirmed on a physical MECOOL KM2 Plus (firmware C1.2.7_20250822) on 2026-11-24.
The unprivileged PoC app (`com.poc.cwe926.bootservice`, UID 10014, zero declared
permissions) reached `BootService` in `com.sdt.ota` (UID 10094), which launched its
privileged `CoreService` and the `SysBootupDialog`.

- [`evidence/logcat_chain_key.txt`](poc_bootservice_cwe926/evidence/logcat_chain_key.txt) — key logcat lines (cross-UID foreground-service launch: `callingUid:10014` → `com.sdt.ota` UID 10094).
- [`evidence/logcat_chain_complete.txt`](poc_bootservice_cwe926/evidence/logcat_chain_complete.txt) — full capture.

## Proof of Concept

The PoC is a self-contained Android application (`poc_bootservice_cwe926/`) that declares **zero `uses-permission` elements** in its manifest. It was installed via USB on a physical MECOOL KM2 Plus device running firmware C1.2.7_20250822.

The only non-standard manifest declaration is `<queries><package android:name="com.sdt.ota" /></queries>`, which is required by Android 11+'s [package visibility filtering](https://developer.android.com/training/package-visibility). This is **not a permission** — it does not grant any capability to the app and does not require user approval.

### Test 1: Safe PoC (typeSelectable=true)

The app sends an intent with `typeSelectable=true`. BootService opens the system OTA dialog (`SysBootupDialog`), which then launches `CoreService`:

```
# PoC app (UID 10014) triggers BootService → SysBootupDialog opens
11-24 12:23:22.874 ActivityTaskManager: Displayed com.sdt.ota/.common.dialog.SysBootupDialog

# CoreService launched as foreground service (callingUid: 10014 = our PoC app)
11-24 12:23:32.483 ActivityManager: Background started FGS: Allowed
  [callingPackage: com.sdt.ota; callingUid: 10094;
   intent: Intent { act=com.skw.ota.update.CoreService pkg=com.sdt.ota (has extras) };
   tempAllowListReason:<fg-service-launch,...,callingUid:10014>]

11-24 12:23:32.506 SdtSysUpdate: CoreService onCreate
11-24 12:23:32.507 SdtSysUpdate: CoreService onStartCommand
```

### Test 2: Direct chain (no file)

The app sends an intent without `typeSelectable` and without a file path. BootService directly invokes the privileged chain through CoreService. Without a valid OTA file, `update_engine` fails safely — the chain stopped because no OTA package was provided, **not because of any access control check**:

```
# PoC app (UID 10014) triggers BootService directly
11-24 12:25:57.028 ActivityManager: Background started FGS: Allowed
  [callingPackage: com.poc.cwe926.bootservice; callingUid: 10014;
   intent: Intent { act=com.skw.ota.update.BootService cmp=com.sdt.ota/.BootService }]

11-24 12:25:57.052 SdtSysUpdate: CoreService onStartCommand
```

Static analysis of the decompiled code confirms the path from `CoreService` through `BootManager.m785d` to `update_engine` and `RecoverySystem` contains no caller verification at any step.

### Permission evidence

**PoC app** (`com.poc.cwe926.bootservice`, appId 10014):

```
# dumpsys package com.poc.cwe926.bootservice
versionCode=1 minSdk=28 targetSdk=34
forceQueryable=false
queriesPackages=[com.sdt.ota]
runtime permissions:
    (none)
```

**Target** (`com.sdt.ota`, appId 10094):

```
# dumpsys package com.sdt.ota (relevant permissions only)
android.permission.RECOVERY: granted=true
android.permission.REBOOT: granted=true
android.permission.INSTALL_PACKAGE_UPDATES: granted=true
```

## Real-world attack scenario

The PoC uses `startForegroundService()` from an on-screen button for demonstration purposes. In a real attack:

1. A malicious app is installed via USB, a third-party app store, or social engineering
2. The app declares `android.permission.INTERNET` (a **normal** permission, granted automatically without user approval)
3. On first run (or silently via a broadcast receiver), it downloads a legitimately signed vendor OTA package — the vendor's own OTA C1.2.7 (971 MB, `ota-wipe=yes`) is still served by Google's update infrastructure as of November 2026
4. It calls `startForegroundService()` targeting `BootService` with the downloaded file path
5. The device factory-resets, destroying all user data

## Affected Firmware Versions

| Firmware | `SdtUpdate.apk` SHA-256 | BootService |
|---|---|---|
| C1.2.7_20250822 | `179575c5b96012317f0a3e84eb1b87959170bfa0c32a2c141a4b3e51f8b07ed6` | exported, no permission |
| C1.3.5_20261111 | `8636a6a191f83c8c5e3d5001b37092a2dc9c6d8cea38d8f63ae53e450587e386` | exported, no permission |

Both versions ship `SdtUpdate` 2.5.1 (versionCode 20501) with an identical `BootService` declaration. The vulnerability likely affects all firmware versions of the KM2 Plus and potentially other MECOOL/Skyworth models using the same SdtUpdate OEM package.

## Mitigation

Disable the vulnerable package via ADB:

```bash
adb shell pm disable-user --user 0 com.sdt.ota
```

This prevents `BootService` from being reachable but also disables the legitimate OTA update functionality. The mitigation survives reboots but not a factory reset. ADB debugging must be enabled temporarily to apply it.

## Building the PoC

1. Open `poc_bootservice_cwe926/` in Android Studio
2. Wait for Gradle sync
3. Build > Build APK(s)
4. Install via USB: copy the APK to a USB drive and install from a file manager on the TV, or use `adb install app/build/outputs/apk/debug/app-debug.apk`

Requirements: Android SDK with compileSdk 34, Gradle 9.0+, Java 17+.

## Project Structure

```
poc_bootservice_cwe926/
├── app/src/main/
│   ├── AndroidManifest.xml            ← zero uses-permission
│   ├── java/.../MainActivity.java     ← 2 buttons, ~139 lines
│   └── res/
│       ├── layout/activity_main.xml   ← TV-optimized layout
│       └── values/
│           ├── strings.xml
│           └── themes.xml
├── app/build.gradle                   ← compileSdk 34, minSdk 28
├── build.gradle                       ← AGP 8.5.2
├── settings.gradle
└── gradle.properties
```

## Timeline

| Date | Event |
|---|---|
| 2026-11-04 | Vendor (MECOOL) contacted about firmware security issues; replied with generic template |
| 2026-11-10 | Formal technical report sent to vendor with evidence; same generic reply, no acknowledgement |
| 2026-11-23 | Vulnerability identified through static analysis of `com.sdt.ota` BootService |
| 2026-11-24 | Proof of concept confirmed on physical MECOOL KM2 Plus (firmware C1.2.7_20250822) |
| 2026-11-24 | CVE requested |

## License

This research is published for defensive and educational purposes. The PoC source code is provided as-is for security research and responsible disclosure.

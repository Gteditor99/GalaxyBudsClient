# BudsFirmwareUpdater: Issues, Fixes, and Main Project Migration Guide

## Overview

The `BudsFirmwareUpdater` Android companion app had several critical issues preventing it from functioning on modern Android devices (tested on Android 16 / API 36). This document covers each issue, the fix applied, and how the same fixes should be applied to the main GalaxyBudsClient desktop/cross-platform project.

---

## Issue 1: Firmware API is Dead (403 Forbidden)

### Problem

Both the Android app and the main project use the firmware API at:
```
https://fw.timschneeberger.me/v3
```

This API now returns **HTTP 403** for all requests. This means:
- `GET /firmware/{Model}` (firmware search) fails
- `GET /firmware/download/{BuildName}` (firmware download) fails

No firmware can be discovered or downloaded through the app's "Check Updates" flow.

### Fix Applied (Android App)

Rewrote `FirmwareRemoteClient.kt` to use the **GitHub firmware archive** at [`timschneeb/galaxy-buds-firmware-archive`](https://github.com/timschneeb/galaxy-buds-firmware-archive):

- **Firmware listing**: `GET https://api.github.com/repos/timschneeb/galaxy-buds-firmware-archive/contents/{ModelFolder}`
  - Returns JSON array with `name`, `download_url`, and `size` for each `.bin` file
  - Model folder mapping: `Buds2Pro` -> `R510`, `BudsPro` -> `R190`, etc.

- **Firmware download**: Direct binary download from `https://raw.githubusercontent.com/timschneeb/galaxy-buds-firmware-archive/main/{ModelFolder}/FOTA_{BuildName}.bin`

### How to Transfer to Main Project

**File to modify**: `GalaxyBudsClient/Model/Firmware/FirmwareRemoteClient.cs`

Replace the API constants and rewrite the two methods:

```csharp
// Old (broken):
private const string ApiBase = "https://fw.timschneeberger.me/v3";
private const string ApiGetFirmware = ApiBase + "/firmware";
private const string ApiDownloadFirmware = ApiBase + "/firmware/download";

// New:
private const string GitHubApiBase = "https://api.github.com/repos/timschneeb/galaxy-buds-firmware-archive/contents";
```

**SearchForFirmware changes:**
1. Map `BluetoothImpl.Instance.CurrentModel` to the GitHub folder name using `ModelMetadataAttribute.FwPattern` (e.g., `Models.Buds2Pro` -> `"R510"`)
2. GET `{GitHubApiBase}/{FolderName}` with header `Accept: application/vnd.github.v3+json`
3. Parse the JSON array, extracting `name` (strip `FOTA_` prefix and `.bin` suffix for `BuildName`) and `download_url`
4. Construct `FirmwareRemoteBinary` objects from parsed filenames. The build name format (e.g., `R510XXU0AYF1`) encodes: model, region (`XX`), carrier (`U0`), year (`A`=2021, `Y`=2025), month (`A`-`L`), and revision

**DownloadFirmware changes:**
1. Store the `download_url` from the search step (add a field to `FirmwareRemoteBinary` or use a lookup)
2. Download directly from the `raw.githubusercontent.com` URL instead of the old API endpoint

**FirmwareRemoteBinary.cs note:**
The GitHub API doesn't return structured metadata (model, region, year, month, revision). These must be parsed from the build name string. The existing `FirmwareRemoteBinaryFilters.FilterByVersion` logic works as-is since it operates on `BuildName` string comparison.

### GitHub API Rate Limits

The GitHub Contents API allows **60 requests/hour** for unauthenticated requests. For higher limits, add a `User-Agent` header (required by GitHub) and optionally a GitHub token via `Authorization: Bearer {token}` for 5,000 requests/hour.

---

## Issue 2: Missing Kotlin Runtime in APK

### Problem

The Android build script (`build.sh`) compiled Kotlin source with `kotlinc` but did not include the Kotlin standard library in the output. The `kotlinc` command outputs only the app's own `.class` files by default; the Kotlin runtime (802 classes including `kotlin.jvm.internal.Intrinsics`, `kotlin.Unit`, etc.) must be explicitly bundled.

This caused an immediate crash on launch: `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics` — because every Kotlin function begins with `Intrinsics.checkParameterIsNotNull()` calls.

The APK was only ~50KB (app classes only) instead of the expected ~545KB (app + Kotlin runtime).

### Fix Applied

Added `-include-runtime` flag to `kotlinc` and changed output to a JAR:

```bash
# Before (broken):
kotlinc -cp "$PLATFORM" -d "$OUT/classes" -jvm-target 1.8 ...

# After (fixed):
kotlinc -cp "$PLATFORM" -include-runtime -d "$OUT/classes.jar" -jvm-target 1.8 ...
```

### Relevance to Main Project

**Not applicable.** The main GalaxyBudsClient project is a C#/.NET application built with standard .NET tooling (MSBuild/dotnet CLI), which handles runtime dependencies automatically. This issue is specific to the Android app's manual `kotlinc` + `dx` build pipeline.

---

## Issue 3: Missing Bluetooth Runtime Permissions (Android 12+)

### Problem

On Android 12+ (API 31+), `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` are **runtime permissions** that must be explicitly requested and granted by the user at runtime, even if declared in `AndroidManifest.xml`. Without the runtime grant, `BluetoothAdapter.bondedDevices` throws `SecurityException`, which the code caught silently and returned an empty device list.

The app showed "No Buds found" with no indication that permissions were the issue.

### Fix Applied

Added runtime permission request flow in `MainActivity.kt`:

```kotlin
private fun requestBluetoothPermissions() {
    if (Build.VERSION.SDK_INT >= 31) {
        val needed = mutableListOf<String>()
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED)
            needed.add("android.permission.BLUETOOTH_CONNECT")
        if (checkSelfPermission("android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED)
            needed.add("android.permission.BLUETOOTH_SCAN")
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), RC_BT_PERMS)
            return
        }
    }
    refreshDeviceList()
}
```

### Relevance to Main Project

**Not directly applicable.** The main project runs on desktop platforms (Windows, macOS, Linux) where Bluetooth permissions are handled at the OS level, not via Android's runtime permission model. However, if the main project ever adds Android support via .NET MAUI or similar, the same permission request pattern would be needed.

---

## Issue 4: Device Name Filter Too Restrictive

### Problem

The Android app's `getPairedBuds2Pro()` only matched devices containing "Buds2 Pro", "Buds 2 Pro", or "Galaxy Buds2 Pro". Real-world Bluetooth device names vary by region, carrier firmware, and user customization.

### Fix Applied

Broadened the filter to match any device with "Buds" in the name:

```kotlin
fun getPairedBudsDevices(): List<BluetoothDevice> {
    return adapter.bondedDevices?.filter { d ->
        val n = d.name ?: ""
        n.contains("Buds", true) || n.contains("Galaxy Buds", true)
    } ?: emptyList()
}
```

Also added debug logging of ALL paired devices to help diagnose discovery issues.

### How to Transfer to Main Project

The main project already handles this well in `DeviceSpecHelper.cs` with a two-tier discovery system:

1. **UUID-based detection** (primary): Checks for the `SppNew` UUID (`2e73a4ad-332d-41fc-90e2-16bef06523f2`) and parses the custom device ID UUID to determine exact model
2. **Name-based fallback**: Uses `DeviceBaseName` from each device spec (e.g., `"Buds2 Pro"`, `"Buds3"`, etc.)

The main project's approach is already more robust than the Android app's original filter. No changes needed here.

---

## Issue 5: Install Button Never Enabled

### Problem

After loading firmware (via sideload or GitHub download), the "Install" button remained greyed out with no feedback. The button state was set by:

```kotlin
installBtn.isEnabled = btClient.isConnected
```

This failed silently when:
- The user loaded firmware before connecting (expected workflow)
- The Bluetooth connection dropped during the file picker activity
- The `onStateChanged` callback from `FirmwareTransferManager` overrode the button state

There was no visual feedback explaining WHY the button was disabled.

### Fix Applied

Centralized all install button logic into `updateInstallButton()`:

```kotlin
private fun updateInstallButton() {
    val hasFirmware = loadedBinary != null
    val connected = btClient.isConnected
    installBtn.isEnabled = hasFirmware && connected
    installBtn.text = when {
        !hasFirmware && !connected -> "Install (load firmware & connect)"
        !hasFirmware -> "Install (load firmware first)"
        !connected -> "Install (connect first)"
        else -> "Install Firmware"
    }
}
```

Called from every state-changing action: connect, disconnect, firmware load, firmware download, cancel, and transfer state changes.

### How to Transfer to Main Project

The main project's UI is built with Avalonia (XAML + MVVM), so the pattern differs, but the principle applies: **firmware update readiness should be a computed property that considers both connection state and firmware load state**, and the UI should communicate what's missing.

Check the firmware update view model in the main project to ensure it handles these edge cases:
- Firmware loaded before device connected
- Device disconnects while firmware is loaded
- Clear messaging about prerequisites

---

## Summary of All Changes

| File | Change |
|------|--------|
| `build.sh` | Added `-include-runtime` to `kotlinc`, output to JAR |
| `FirmwareRemoteClient.kt` | Rewrote to use GitHub archive API instead of dead `fw.timschneeberger.me` |
| `MainActivity.kt` | Added runtime BT permission request, debug device logging, `updateInstallButton()` |
| `BluetoothSppClient.kt` | Broadened device filter from "Buds2 Pro" to any "Buds" |

## Priority for Main Project

**Critical — must fix immediately:**
1. **Firmware API migration** (`FirmwareRemoteClient.cs`): The `fw.timschneeberger.me` API returns 403. All firmware discovery and download is broken for all users of the main project.

**No action needed:**
2. Kotlin runtime — N/A (C# project)
3. Android permissions — N/A (desktop platforms)
4. Device name filter — Already handled well
5. Install button UX — Verify in Avalonia UI, but likely already handled by MVVM bindings

# AdvanceHotspot

[Overview](../../README.md) · [繁體中文](../zh-TW/README.md)

AdvanceHotspot is an Android app that extends the system Wi-Fi hotspot on a rooted device. It keeps the system SoftAP for the radio and uses root to add the controls Android does not expose.

## Planned features

- Hotspot settings: SSID, passphrase, security type, band, channel, maximum client count, hidden SSID
- Route hotspot traffic through the tunnel interface of any VPN app already running on the phone
- Connected device list with name, IP address and MAC address
- Per-device internet switch and egress interface (Wi-Fi, mobile data, VPN)
- Kill switch: a device loses connectivity instead of falling back to another interface when its egress goes down
- IPv6 leak protection
- Intranet: custom subnet and gateway, static IP binding by MAC, local DNS names, client isolation switch
- Per-device rate limit and traffic statistics

## Requirements

- Pixel 9 Pro XL on Android 17 (API 37). Other devices are not supported yet because the app relies on hidden APIs of one Android release.
- Root through KernelSU
- To build: JDK 17 or newer and Android SDK platform 37

## Build and install

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

KernelSU does not prompt for root. Open the KernelSU manager, enable superuser for AdvanceHotspot, then tap Refresh in the app.

## Checks

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest
```

## Architecture

| Path | Role |
| --- | --- |
| `app/src/main/kotlin/.../root` | Single entry point for root commands (libsu) |
| `app/src/main/kotlin/.../helper` | Command-line helper run with `app_process` as the shell user; calls hidden SoftAP and tethering APIs |
| `app/src/main/kotlin/.../hotspot` | Hotspot model, validation, and the controller that invokes the helper |
| `app/src/main/kotlin/.../system` | Device and network probing, command output parsers |
| `app/src/main/kotlin/.../ui` | Jetpack Compose screens and view models |

## How hotspot control works

Hidden SoftAP and tethering APIs verify that the caller's package name belongs to the calling uid. Root (uid 0) has no package, so those calls are rejected. The app therefore uses root only to drop to the shell user (uid 2000) and runs a small helper from its own APK with `app_process`. The `com.android.shell` package holds `NETWORK_SETTINGS` and `TETHER_PRIVILEGED`, which is enough to write the SoftAP configuration and start tethering.

A forced channel takes effect even while the phone is connected to Wi-Fi on a different channel (verified on the Pixel 9 Pro XL). Saving while the hotspot is on restarts it, so connected devices drop briefly.

## Status

Phases 1 and 2 are done. The app reads and writes SSID, security type, passphrase, band, channel, maximum client count, hidden SSID and auto shutdown, and turns the hotspot on and off. The connected device list, routing, intranet and rate limiting are not implemented yet. See the phase table in the [overview](../../README.md#status).

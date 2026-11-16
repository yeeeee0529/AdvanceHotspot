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
| `app/src/main/kotlin/.../system` | Device and network probing, command output parsers |
| `app/src/main/kotlin/.../ui` | Jetpack Compose screens and view models |

## Status

Phase 1 is done: the project builds, installs, and shows root state, kernel, required network tools and active interfaces. Hotspot control is not implemented yet. See the phase table in the [overview](../../README.md#status).

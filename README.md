# AdvanceHotspot

Advanced Wi-Fi hotspot control for one rooted Pixel: route hotspot clients through a VPN, choose each device's egress with a kill switch, run a custom intranet with local DNS names, and see per-device traffic with optional rate limits.

進階 Wi-Fi 熱點控制，需要 root。完整說明：[繁體中文](docs/zh-TW/README.md) · [English](docs/en/README.md)

## Requirements

- Pixel 9 Pro XL on Android 17 (API 37), rooted with KernelSU
- JDK 17 or newer, Android SDK platform 37

## Build and install

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After the first install, enable superuser for AdvanceHotspot in the KernelSU manager, then tap Refresh in the app.

## Features

- Hotspot settings: band, channel, maximum client count, hidden SSID, security type
- Hotspot traffic through any VPN app already running on the phone
- Connected device list with nicknames, blocking and temporary disconnect
- Per-device internet switch and egress (Wi-Fi, mobile data, VPN, Ethernet), kill switch, IPv6 leak protection
- Custom subnet, static IP binding by MAC, `.lan` names, client isolation
- Per-device traffic statistics and rate limits
- Public IP and total hotspot speed on the Hotspot tab

## Status

All planned features are implemented. Only the Pixel 9 Pro XL on Android 17 is supported. Known limits, such as IPv4-only rate limits and unhandled 464XLAT, are listed in the full READMEs.

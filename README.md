# AdvanceHotspot

Advanced Wi-Fi hotspot control for rooted Android: route hotspot clients through a VPN, pick band and channel, cap client count, and control each connected device's internet access and egress interface.

進階 Wi-Fi 熱點控制（需要 root）。完整說明：[繁體中文](docs/zh-TW/README.md) · [English](docs/en/README.md)

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

Grant the app root in the KernelSU manager after the first install.

## Status

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project skeleton, root check, environment screen | Done |
| 2 | Hotspot configuration (band, channel, max clients) and on/off | Done |
| 3 | Connected device list, nicknames, MAC blocking | Done |
| 4 | Per-device routing, kill switch, IPv6 leak protection | Planned |
| 5 | Intranet: custom subnet, static leases, local DNS, isolation | Planned |
| 6 | Per-device rate limit and traffic statistics | Planned |

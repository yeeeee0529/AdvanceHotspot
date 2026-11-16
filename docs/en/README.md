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
| `app/src/main/kotlin/.../devices` | Per-device annotations stored by MAC address |
| `app/src/main/kotlin/.../routing` | Routing policy, rule script generator, egress resolution and the foreground service |
| `app/src/main/kotlin/.../system` | Device and network probing, command output parsers |
| `app/src/main/kotlin/.../ui` | Jetpack Compose screens and view models |

## How hotspot control works

Hidden SoftAP and tethering APIs verify that the caller's package name belongs to the calling uid. Root (uid 0) has no package, so those calls are rejected. The app therefore uses root only to drop to the shell user (uid 2000) and runs a small helper from its own APK with `app_process`. The `com.android.shell` package holds `NETWORK_SETTINGS` and `TETHER_PRIVILEGED`, which is enough to write the SoftAP configuration and start tethering.

A forced channel takes effect even while the phone is connected to Wi-Fi on a different channel (verified on the Pixel 9 Pro XL). Saving while the hotspot is on restarts it, so connected devices drop briefly.

## How per-device routing works

Turn on "per-device routing" in the Devices tab. A foreground service then keeps a set of rules in sync with the connected devices and the phone's networks. It listens for client and network changes through a long-running helper process, so there is no polling.

Each device has an internet switch and an egress: the global default, follow the system, Wi-Fi, mobile data, VPN or Ethernet. Egress is chosen by network type, not interface name, because a VPN interface can come back under a different name after reconnecting.

For a device bound to an egress the service installs:

- an `ip rule` that sends its traffic to that interface's routing table, followed by an `unreachable` rule
- filter rules that accept only traffic leaving through that interface and drop everything else from the device
- a DNAT rule that redirects its DNS queries to the egress network's DNS server, because the phone's own DNS forwarder would otherwise resolve them over the system upstream
- IPv6 drop rules, because IPv6 cannot be steered to the chosen egress

The kill switch is the filter rules: when the egress disappears the device is dropped entirely and never falls back to another network. The rules stay in the kernel if the service is killed.

The service also removes the system's tethering BPF offload program from the hotspot interface. Offloaded connections bypass netfilter, so without this an existing connection would keep flowing after a device is blocked.

Known limits:

- Devices with a non-default policy have no IPv6 connectivity.
- A device that uses its own encrypted DNS (DoH or DoT) is still routed through the chosen egress, but the DNS redirect does not apply to it.
- Mobile data as an egress is untested, and carriers that provide IPv4 through 464XLAT are not handled yet.
- Turning routing off does not re-attach the BPF offload program; it returns the next time the hotspot starts.

## Status

Phases 1 to 4 are done. The app configures and toggles the hotspot, lists connected devices with nickname and MAC blocking, and controls each device's internet access and egress with a kill switch. Custom subnet, static leases, local DNS, client isolation, rate limiting and traffic statistics are not implemented yet. See the phase table in the [overview](../../README.md#status).

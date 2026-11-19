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

## Interface

The UI follows Material 3 Expressive (Compose Material 3 `1.5.0-rc01`, dynamic color, expressive motion). A bottom navigation bar has four tabs:

| Tab | Contents |
| --- | --- |
| Hotspot | Status, on/off button, and hotspot settings. Unsaved changes bring up a floating save toolbar. |
| Devices | Connected, offline and blocked devices. Connected and offline devices are sorted by total traffic (upload plus download), highest first. Tap a device to open a sheet with its traffic, routing, details and actions (rename, static IP, rate limit, disconnect temporarily, block). "Disconnect temporarily" kicks a connected device by blocking it for a three-minute cooldown and then removing only that device from the blocklist, so it can reconnect afterwards. |
| Network | Switches and global settings for per-device routing, custom intranet and traffic statistics. |
| Environment | Root status, system information, network tools and active interfaces. |

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
| `app/src/main/kotlin/.../traffic` | Counting and rate limit rules, counter parsing, stored limits and totals |
| `app/src/main/kotlin/.../intranet` | Custom subnet settings and the script generator that manages dnsmasq and the DHCP redirect |
| `app/src/main/kotlin/.../system` | Device and network probing, command output parsers |
| `app/src/main/kotlin/.../ui` | Jetpack Compose screens (Material 3 Expressive), shared components and view models |

## How hotspot control works

Hidden SoftAP and tethering APIs verify that the caller's package name belongs to the calling uid. Root (uid 0) has no package, so those calls are rejected. The app therefore uses root only to drop to the shell user (uid 2000) and runs a small helper from its own APK with `app_process`. The `com.android.shell` package holds `NETWORK_SETTINGS` and `TETHER_PRIVILEGED`, which is enough to write the SoftAP configuration and start tethering.

A forced channel takes effect even while the phone is connected to Wi-Fi on a different channel (verified on the Pixel 9 Pro XL). Saving while the hotspot is on restarts it, so connected devices drop briefly.

## How per-device routing works

Turn on "per-device routing" in the Network tab. A foreground service then keeps a set of rules in sync with the connected devices and the phone's networks. It listens for client and network changes through a long-running helper process, so there is no polling.

The service runs whenever the hotspot is on, even with all three features off, because it owns the persistent notification: "Hotspot is on", the current egress interface, and a "Turn off hotspot" button. The egress is the default egress when per-device routing binds one, otherwise the phone's default network interface. Turning the hotspot off in the app stops the service and clears its notification; turning it on in the app, or opening the app while it is on, starts the service again. A stopped service does not watch the hotspot, so after turning the hotspot on from Quick Settings, open the app once to apply the rules. After turning it off from Quick Settings, the notification stays until you use the app's switch or open the app again.

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

## How the custom intranet works

Turn on "custom intranet" in the Network tab. The same foreground service then takes over address assignment on the hotspot interface.

- It adds a second address to the hotspot interface as the gateway, for example `10.77.0.1/24`. The hotspot can still be started from anywhere, including the system quick settings.
- It runs the phone's own `/system/bin/dnsmasq` twice: one instance for DHCP only, one for DNS only on the gateway address.
- The system DHCP server holds UDP port 67 and cannot be turned off. dnsmasq therefore listens on port 1067, and NAT rules redirect DHCP to it. Broadcast requests keep their broadcast destination and only the port is rewritten; rewriting the address makes the kernel drop requests whose source is `0.0.0.0`.
- NAT rules apply only to the first packet of a connection, and every device's DHCP broadcast shares one conntrack entry. If devices are already retrying DHCP when the rules change, the old entry never times out. The app temporarily drops DHCP requests in the raw table until the old entry expires (up to about 30 seconds), then lets them through.
- While the phone is on a VPN, the system routes packets from root processes into the VPN. The app adds an `ip rule` at priority 10500 so that packets from the phone to the intranet always leave through the hotspot interface; otherwise DHCP and DNS replies never reach the devices.
- Static IP bindings are written to a dnsmasq hosts file keyed by MAC address.
- Local names are generated from each device's nickname, or its own hostname when there is no usable nickname, and resolve as `name.lan`. The gateway is `hotspot.lan`. Only letters, digits and hyphens are kept, so a nickname written entirely in Chinese falls back to the hostname. A device with neither (many phones send no hostname in DHCP) gets `device-<last octet of its IP>.lan`, for example `device-24.lan`.
- Client isolation is a hotspot setting in the Hotspot tab. It takes effect the next time the hotspot starts.

Known limits:

- The subnet is always a /24.
- Devices that are already connected keep their old address until they reconnect or their lease runs out. The same applies after turning the intranet off, so reconnect devices then.
- A device bound to a specific egress has its DNS redirected to that network's DNS server and cannot resolve `.lan` names.
- Client isolation was verified as a stored setting only. That devices are actually isolated from each other was not tested.

## How traffic statistics and rate limits work

Turn on "traffic statistics and rate limit" in the Network tab. The foreground service adds one counting rule per device and direction and reads the counters every two seconds to show live rates and running totals. Totals are stored per MAC address and survive restarts until you reset them.

A rate limit is a second rule in front of the counting rule that drops packets above the configured rate (`hashlimit`). This is policing, not queueing: TCP slows down to roughly the limit, and the measured speed fluctuates around it rather than sitting exactly on it.

The system's tethering BPF offload programs are removed from every interface while this is on, because offloaded packets skip netfilter and would be neither counted nor limited.

Known limits:

- Limits apply to IPv4 only. IPv6 traffic is neither counted nor limited.
- Only connected devices can be given a limit.
- The limiter was verified to drop packets above the rate, but not measured against a real download on a client.

## Status

All six planned phases are done. The app configures and toggles the hotspot, lists connected devices with nickname and MAC blocking, controls each device's internet access and egress with a kill switch, provides a custom subnet with static IP bindings, local DNS names and client isolation, and shows per-device traffic with optional rate limits. See the phase table in the [overview](../../README.md#status).

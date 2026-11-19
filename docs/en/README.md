# AdvanceHotspot

[Overview](../../README.md) · [繁體中文](../zh-TW/README.md)

AdvanceHotspot is an Android app that extends the system Wi-Fi hotspot on a rooted phone. It keeps the system SoftAP for the radio and uses root to add the controls Android does not expose: routing hotspot traffic through a VPN, choosing each device's egress, a custom intranet, and per-device traffic statistics and rate limits.

## Requirements

- Pixel 9 Pro XL on Android 17 (API 37). Other devices are not supported, because the app relies on hidden APIs of one Android release
- Root through KernelSU
- To build: JDK 17 or newer and Android SDK platform 37

## Build and install

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

KernelSU does not prompt for root. Open the KernelSU manager, enable superuser for AdvanceHotspot, then tap Refresh (重新整理) in the app.

## Features

- Hotspot settings: SSID, passphrase, security type, band, channel, maximum client count, hidden SSID
- Route hotspot traffic through the tunnel interface of any VPN app already running on the phone
- Connected device list with name, IP address and MAC address, with nicknames, blocking and temporary disconnect
- Per-device internet switch and egress interface (Wi-Fi, mobile data, VPN, Ethernet)
- Kill switch: a device loses connectivity instead of falling back to another interface when its egress goes down
- IPv6 leak protection
- Intranet: custom subnet and gateway, static IP binding by MAC, local DNS names, client isolation
- Per-device traffic statistics and rate limits
- Public IP and total hotspot speed on the Hotspot tab

## Usage

The UI uses Material 3 Expressive (Compose Material 3 `1.5.0-rc01`, dynamic color). A floating navigation bar at the bottom has four tabs; tap one, or press and drag across the bar to switch.

The UI text is Traditional Chinese. Labels below are translated.

| Tab | Contents |
| --- | --- |
| Hotspot | Status and on/off switch, public IP (IP, organization, country), the hotspot's total upload and download rate and totals, hotspot settings. Unsaved changes bring up a save toolbar at the bottom |
| Devices | Connected, offline and blocked devices. Connected and offline devices are sorted by total traffic, highest first. Tap a device to open a sheet with its traffic, routing, details and actions |
| Network | Switches and global settings for per-device routing, custom intranet, and traffic statistics and rate limits, plus the traffic total reset |
| Environment | Root status, system information, network tools and active interfaces |

Saving while the hotspot is on restarts it, so connected devices drop briefly.

Rates and totals appear only when traffic statistics are on in the Network tab.

### Temporary disconnect

"Disconnect temporarily" in the device sheet adds the device to the blocklist for three minutes so the system kicks it, then removes only that device from the blocklist so it can reconnect. Other blocklist changes made during those three minutes are kept. If the app process is killed during the three minutes, the device stays blocked until you unblock it by hand.

### Background service and notification

While the hotspot is on, the app runs a foreground service that applies the routing, intranet and traffic rules and shows a persistent notification: "Hotspot is on", the current egress interface, and a "Turn off hotspot" button. The egress is the default egress when per-device routing binds one, otherwise the phone's default network interface.

Turning the hotspot off in the app stops the service and clears the notification. Turning it on in the app, or opening the app while it is on, starts the service. A stopped service does not watch the hotspot:

- After turning the hotspot on from Quick Settings, open the app once to apply the rules
- After turning it off from Quick Settings, the notification stays until you use the app's switch or open the app again

### Public IP

The public IP comes from public lookup services, tried in order: ident.me, ipwho.is, api.ip.sb. The request uses the phone's current default network, so with a VPN up it shows the VPN's exit. It is looked up again when the app opens, when the default network changes, and on refresh. This is the app's only outbound connection and the reason it holds the `INTERNET` permission.

## Known limits

- Only the Pixel 9 Pro XL on Android 17 is supported
- Traffic statistics and rate limits cover IPv4 only. IPv6 traffic is neither counted nor limited
- Devices with a non-default routing policy have no IPv6 connectivity
- A device bound to a specific egress has its DNS redirected to that network's DNS server and cannot resolve `.lan` names
- A device that uses its own encrypted DNS (DoH or DoT) is still routed through the chosen egress, but the DNS redirect does not apply to it
- Mobile data as an egress is untested, and carriers that provide IPv4 through 464XLAT are not handled yet
- The intranet subnet is always a /24
- Devices that are already connected keep their old address until they reconnect or their lease runs out. The same applies after turning the intranet off, so reconnect devices then
- Turning routing or traffic statistics off does not re-attach the system's tethering BPF offload programs. They return the next time the hotspot starts
- Only connected devices can be given a rate limit
- Not yet verified on the phone: that client isolation actually isolates devices (only the stored setting was checked), and the effect of a rate limit on a real download (only the dropping of packets above the rate was checked)

## Development

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Sources live under `app/src/main/kotlin/me/yeeeee/advancehotspot/`:

| Package | Role |
| --- | --- |
| `root` | Single entry point for root commands (libsu) |
| `helper` | Command-line helper run with `app_process` as the shell user; calls hidden SoftAP and tethering APIs |
| `hotspot` | Hotspot model, validation, and the controller that invokes the helper |
| `devices` | Nicknames and last-seen hostnames stored by MAC address |
| `routing` | Routing policy, rule script generator, egress resolution and the foreground service |
| `intranet` | Custom subnet settings and the script generator that manages dnsmasq and the DHCP redirect |
| `traffic` | Counting and rate limit rules, counter parsing, stored limits and totals |
| `system` | Device and network probing, command output parsers, public IP lookup |
| `ui` | Jetpack Compose screens, shared components and view models |

Unit tests cover only the pure script generators and parsers. When a change touches the helper, routing, intranet or traffic code, test it on the phone.

## How it works

### Hotspot control

Hidden SoftAP and tethering APIs verify that the caller's package name belongs to the calling uid. Root (uid 0) has no package, so those calls are rejected. The app therefore uses root only to drop to the shell user (uid 2000) and runs a small helper from its own APK with `app_process`. The `com.android.shell` package holds `NETWORK_SETTINGS` and `TETHER_PRIVILEGED`, which is enough to write the SoftAP configuration and start tethering.

A forced hotspot channel takes effect even while the phone is connected to Wi-Fi on a different channel (verified on the Pixel 9 Pro XL).

### Per-device routing

The foreground service keeps a set of rules in sync with the connected devices and the phone's networks. It listens for client and network changes through a long-running helper process, so there is no polling.

Each device has an internet switch and an egress: the global default, follow the system, Wi-Fi, mobile data, VPN or Ethernet. Egress is chosen by network type, not interface name, because a VPN interface can come back under a different name after reconnecting. For a device bound to an egress the service installs:

- an `ip rule` that sends its traffic to that interface's routing table, followed by an `unreachable` rule
- filter rules that accept only traffic leaving through that interface and drop everything else from the device
- a DNAT rule that redirects its DNS queries to the egress network's DNS server, because the phone's own DNS forwarder would otherwise resolve them over the system upstream
- IPv6 drop rules, because IPv6 cannot be steered to the chosen egress

The kill switch is the filter rules: when the egress disappears the device is dropped entirely and never falls back to another network. The rules stay in the kernel if the service is killed.

The service also removes the system's tethering BPF offload program from the hotspot interface. Offloaded connections bypass netfilter, so without this an existing connection would keep flowing after a device is blocked.

### Custom intranet

With the custom intranet on, the same foreground service takes over address assignment on the hotspot interface:

- It adds a second address to the hotspot interface as the gateway, for example `10.77.0.1/24`
- It runs the phone's own `/system/bin/dnsmasq` twice: one instance for DHCP only, one for DNS only on the gateway address
- The system DHCP server holds UDP port 67 and cannot be turned off, so dnsmasq listens on port 1067 and NAT rules redirect DHCP to it. Broadcast requests keep their broadcast destination and only the port is rewritten; rewriting the address makes the kernel drop requests whose source is `0.0.0.0`
- NAT rules apply only to the first packet of a connection, and every device's DHCP broadcast shares one conntrack entry. If devices are already retrying DHCP when the rules change, the old entry never times out, so the app temporarily drops DHCP requests in the raw table until the old entry expires (up to about 30 seconds), then lets them through
- While the phone is on a VPN, the system routes packets from root processes into the VPN. The app adds an `ip rule` at priority 10500 so that packets from the phone to the intranet always leave through the hotspot interface; otherwise DHCP and DNS replies never reach the devices
- Static IP bindings are written to a dnsmasq hosts file keyed by MAC address
- Client isolation is a hotspot setting in the Hotspot tab. It takes effect the next time the hotspot starts

Local names resolve as `name.lan`, and the gateway is `hotspot.lan`. A name comes from the device's nickname, or from its own hostname when the nickname has no usable characters. Only letters, digits and hyphens are kept, so a nickname written entirely in Chinese falls back to the hostname. A device with neither (many phones send no hostname in DHCP) gets `device-<last octet of its IP>.lan`, for example `device-24.lan`.

### Traffic statistics and rate limits

The foreground service adds one counting rule per device and direction and reads the counters every two seconds to show live rates and running totals. Totals are stored per MAC address and survive restarts until you reset them.

A rate limit is a second rule in front of the counting rule that drops packets above the configured rate (`hashlimit`). This is policing, not queueing: TCP slows down to roughly the limit, and the measured speed fluctuates around it rather than sitting exactly on it.

The system's tethering BPF offload programs are removed from every interface while this is on, because offloaded packets skip netfilter and would be neither counted nor limited.

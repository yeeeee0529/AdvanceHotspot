# Agent Handoff

This file is the single place where agents hand work over to each other. Read it before doing anything else, and update it before you stop. The rules for maintaining it are in [AGENTS.md](AGENTS.md).

Last updated: 2026-11-16

## Current state

All six planned phases are implemented and committed on the local `main` branch. There is no git remote and nothing has been pushed; the owner declined a push for now, so do not push or create a remote unless asked.

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project skeleton, root check, environment screen | Done |
| 2 | Hotspot configuration and on/off | Done |
| 3 | Connected device list, nicknames, MAC blocking | Done |
| 4 | Per-device routing, egress selection, kill switch, IPv6 leak protection | Done |
| 5 | Custom intranet: subnet, static leases, local DNS, client isolation | Done |
| 6 | Per-device traffic statistics and rate limits | Done |
| UI | Material 3 Expressive rewrite, four tabs | Done; on-device check partial (see Verification status) |

Last check results: `assembleDebug` passes, 29 unit tests pass, `lintDebug` reports 0 errors and 2 accepted warnings (`AndroidGradlePluginVersion`, `DataExtractionRules`).

The UI was rewritten to Material 3 Expressive on 2026-11-16. Only the `ui` package, `MainActivity`-adjacent theme, resources and docs changed; no view model, script generator or root-side code changed.

## Target and tooling

- Only one device is supported: Pixel 9 Pro XL, Android 17 (API 37), rooted with KernelSU. `minSdk` is 37 on purpose because the app calls hidden APIs of a single Android release.
- Kotlin with Jetpack Compose, AGP 9.5.1 with built-in Kotlin, Gradle wrapper 9.8.2, libsu 6.0.0. There is no core-ktx dependency; do not add one for syntax sugar.
- Compose Material 3 is pinned to `1.5.0-rc01` in `gradle/libs.versions.toml`, overriding the BOM. The owner chose this pre-release on purpose because the M3 Expressive components do not exist in the stable 1.4.0. Move to 1.5.0 stable once it is released.
- Icons are hand-written vector drawables in `res/drawable` (Material Symbols paths). There is no `material-icons` dependency; add new icons the same way.
- UI strings are Traditional Chinese (`zh-TW`) in `app/src/main/res/values/strings.xml`. Source comments are Traditional Chinese. Identifiers, diagnostics and this file are English.
- Human documentation is three files kept in sync: `README.md`, `docs/zh-TW/README.md`, `docs/en/README.md`.

## Commands

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Lint results are easiest to read from `app/build/reports/lint-results-debug.sarif`.

## Architecture

| Package (under `app/src/main/kotlin/me/yeeeee/advancehotspot`) | Role |
| --- | --- |
| `root` | `RootShell`, the single entry point for root commands through libsu |
| `helper` | `HelperMain` and `SoftApBridge`: a command-line helper run with `app_process` as the shell user |
| `hotspot` | Hotspot model, validation, and `HotspotController`, which invokes the helper |
| `devices` | Nicknames and last-seen hostnames stored by MAC address |
| `routing` | Routing policy, rule script generator, egress resolution, and `RoutingService` |
| `intranet` | Custom subnet settings and the script generator for dnsmasq and the DHCP redirect |
| `traffic` | Counting and rate limit rules, counter parsing, stored limits and totals |
| `ui` | Compose screens and view models: `hotspot`, `devices`, `network`, `environment`; shared M3 Expressive building blocks in `ui/components/Expressive.kt` |

`RoutingService` is one foreground service shared by routing, intranet and traffic. It runs when any of the three is enabled (`RoutingRuntime.sync`). On every trigger it applies intranet, then routing, then traffic. Traffic must be applied last so that its chain sits before the routing chain in `FORWARD`.

The UI has four tabs in `MainScreen` with a bottom `ShortNavigationBar`. Each tab owns its own `ScreenScaffold` (large flexible top app bar). The Devices and Network tabs share one `DevicesViewModel` (both call `viewModel()` with the activity as owner) and both run `DevicesPolling`; only one is visible at a time, so only one poll loop runs. Grouped settings use `SegmentedGroup`, which hands each item its position-based `ListItemShapes`.

All root-side state is produced by pure script generators (`RoutingPlan.toScript`, `IntranetConfig.toScript`, `buildTrafficScript`). Each one validates every external value against a strict pattern before it enters a shell script. Keep that property when you add inputs.

## Findings that cost time to discover

Read these before changing the helper, the DHCP path, or the firewall rules.

- **Stable Compose Material 3 1.4.0 lacks the M3 Expressive components.** Only `ShortNavigationBar` and `MotionScheme` are there. `MaterialExpressiveTheme`, `ButtonGroup`, `ToggleButton`, `LoadingIndicator`, `FloatingToolbar`, `MaterialShapes`, the flexible top app bars and `SegmentedListItem` need 1.5.0 (pinned to `1.5.0-rc01`). The source jar on Google Maven (`material3-android-1.5.0-rc01-sources.jar`) is the quickest reference for their signatures.
- **A toggleable `SegmentedListItem` paints its whole row in the selected container color when checked.** `SwitchItem` overrides the selected colors with the normal ones so a switch row does not look selected.
- **Reinstalling the app as a fresh install drops its KernelSU root grant and all stored settings.** The helper then fails with `su: inaccessible or not found` (exit 127). Check `dumpsys package me.yeeeee.advancehotspot | grep firstInstallTime` before assuming a code regression.

- **Hidden APIs must be called as the shell user, not root.** SoftAP and tethering services check that the caller's package belongs to the calling uid. Root has no package. The helper is started with `su 2000 -c 'CLASSPATH=<apk> app_process ...'` and presents itself as `com.android.shell`.
- **A context wrapper is not enough.** Managers are built on the underlying `ContextImpl`, so the op package name stays `android`. `SoftApBridge` overwrites each manager's `mContext` field with the wrapper. `setOuterContext` did not work.
- **The system DHCP server owns UDP port 67 and cannot be stopped.** dnsmasq cannot bind next to it. dnsmasq listens on port 1067 (`--dhcp-alternate-port`) and NAT redirects DHCP to it.
- **Broadcast DHCP must keep its broadcast destination.** `REDIRECT` rewrites the destination to a unicast address, and the kernel then drops requests whose source is `0.0.0.0`. The working rule is `DNAT --to-destination 255.255.255.255:1067`.
- **All clients' DHCP broadcasts share one conntrack entry, and NAT only sees the first packet of a flow.** Every DISCOVER is `0.0.0.0:68 -> 255.255.255.255:67`. If clients are already retrying DHCP when the DNAT rule is installed, the un-NATed entry is refreshed forever (30 s timeout), the rule counter stays at 0 and the system DHCP server keeps receiving the requests. The same happens in reverse after teardown. The phone has no `conntrack` tool, so the scripts insert a temporary `raw PREROUTING ... --dport 67 -j DROP` (raw runs before conntrack), a background subshell waits for the entry to leave `/proc/net/nf_conntrack`, then removes the drop. Diagnose with `grep 'dport=67 ' /proc/net/nf_conntrack`: reply tuple `sport=67` means not redirected, `sport=1067` means redirected.
- **With a VPN up, netd rule 13000 sends root-owned traffic into the VPN.** dnsmasq's unicast DHCP replies and its DNS replies left through `tun0` and never reached clients. The intranet script adds `ip rule add to <subnet> iif lo lookup 97 prio 10500`. `tcpdump -i any` shows which interface replies leave from; `tcpdump -i wlan2` alone only shows that they are missing.
- **The DHCP dnsmasq instance must use `--interface`, not `--listen-address`.** With only the alias address allowed, it ignores requests arriving on the hotspot interface.
- **Android's dnsmasq 2.51 spins at 100% CPU when stdin reaches EOF.** It reads control commands from stdin. Its stdin is connected to a FIFO opened read-write. It also produces no usable log output, so verify through the lease file and packet counters.
- **Tethering BPF offload bypasses netfilter.** Upload is offloaded on the hotspot interface and download on the upstream interface. Routing removes the hotspot-side program; traffic removes both. Tethering re-attaches them when the upstream changes, so the removal runs on every apply.
- **With the intranet on, the system's client list has no usable IP or hostname.** `IntranetStore.mergeLeases` overlays the dnsmasq lease file. There is no lease event, so the service polls the lease file every 4 seconds.
- **Per-chain packet counters reset on every apply**, because `iptables-restore --noflush` flushes the declared chains. Do not read a zero counter as proof that nothing matched.
- **libsu verbose logging must stay off.** Helper output contains the hotspot passphrase and would be written to logcat.
- **`adb shell input text` is unreliable on this phone.** The default keyboard is Zhuyin and swallows letters into its composing buffer. Fields with `KeyboardType.Decimal` or `Number` accept digits and dots correctly.
- **A blocked-then-unblocked client does not always redo DHCP.** It often keeps its lease. To force a fresh DHCP exchange, ask the owner to toggle Wi-Fi on the client.

## Verification status

| Area | Status |
| --- | --- |
| Hotspot on/off, band, forced channel, max clients | Verified on device |
| Device list | Verified on device |
| Internet switch, VPN egress, kill switch | Verified on device. On a real VPN drop only the rule swap was observed, not dropped packets |
| Custom subnet, static lease, local DNS, rebuild after hotspot restart | Verified on device |
| Intranet DHCP with clients already retrying and a VPN active on the phone | Verified on device 2026-11-16: three clients got leases and answered ping; DNS replies leave on the hotspot interface |
| Conntrack expiry on intranet teardown (turning the intranet off with clients connected) | Not tested on device |
| Traffic counters | Verified on device |
| Rate limit | Rule application verified; the `hashlimit` condition was shown to drop packets with a ping test. Not measured against a real client download |
| MAC blocking | Blocklist write verified and used to kick a client. Not confirmed that a blocked client stays out |
| Client isolation | Setting write and read-back only |
| Mobile data egress, start on boot | Not tested |
| Nickname entry in Chinese | Not tested |
| M3 Expressive UI: Hotspot (hotspot off), Network, Environment, empty Devices list, dark theme | Verified on device by screenshot |
| M3 Expressive UI: floating save toolbar, device rows and device sheet, light theme | Not verified on device |

## Known limits

- Rate limits and traffic counters cover IPv4 only.
- Devices with a non-default routing policy have no IPv6.
- A device bound to a specific egress cannot resolve `.lan` names, because its DNS is redirected to that network's DNS server.
- Mobile data through 464XLAT is not handled: rules match the base interface, not the stacked `v4-` interface.
- The intranet subnet is always a /24.
- Turning routing or traffic off does not re-attach BPF offload; it returns the next time the hotspot starts.

## State left on the test phone

- As found on 2026-11-16 (DHCP fix session), set by the owner and left unchanged: hotspot on with several clients, intranet on with a custom gateway, routing on, traffic on, and a VPN active on the phone. The routing chain currently drops all forwarding for every client; this is the owner's policy state, not something an agent set.
- The app was updated in place (`adb install -r`) with the DHCP fix; settings and the root grant were kept.
- `adb shell` has root.
- Dark mode is on (`cmd uimode night yes`). Auto-rotate is on; its earlier value is unknown.

## Working with the phone

- The hotspot is usually shared with several other people's devices. Changes to DHCP, firewall rules or hotspot state affect all of them. Aim tests at the owner's own client and ask which one it is.
- Mask the passphrase when printing helper `status` output.
- Ask before pushing, creating a remote, or changing the KernelSU allowlist.

## Suggested next steps

Nothing is in progress. Candidates, none of them started or agreed with the owner:

0. Finish the on-device check of the new UI: floating save toolbar, device rows and sheet with a real client, and light theme.

1. Close the verification gaps above, starting with a real speed test under a rate limit.
2. Let egress-bound devices resolve `.lan` names, for example with one DNS instance per egress.
3. Extend rate limits and counters to IPv6.
4. Handle 464XLAT for mobile data egress.
5. Replace the 4-second polling in the Devices screen with the service's watcher events.

## Handoff log

Newest entry first. Add one entry per working session.

### 2026-11-16 (DHCP fix)

- Owner report: devices connect but get no IP address. Two independent causes, both fixed in `intranet/Intranet.kt` and verified on the phone: a stale shared conntrack entry kept DHCP broadcasts away from the DNAT rule, and with a VPN active dnsmasq's replies were routed into `tun0`. See the two new entries under "Findings that cost time to discover".
- Added two unit tests for the generated script lines. README technical notes updated in both languages.
- Unfinished: the teardown direction of the conntrack expiry (intranet switched off while clients are connected) is implemented but not tested on the phone.

### 2026-11-16 (UI rewrite)

- Rewrote the UI to Material 3 Expressive: `MaterialExpressiveTheme` with dynamic color and expressive motion, bottom `ShortNavigationBar`, large flexible top app bars, segmented lists, connected toggle button groups, shape-morphing status badges, `LoadingIndicator`, a floating save toolbar on the Hotspot tab, and a bottom sheet per device.
- Owner decisions: split the old Devices tab into Devices (device list) and Network (routing, intranet, traffic switches); pin Compose Material 3 to `1.5.0-rc01`.
- Found the app uninstalled on the phone; the fresh install needed a new root grant, which the owner gave.
- Unfinished: on-device check of the save toolbar, device rows and sheet, and light theme. The phone was in use by the owner, and the owner chose to commit without those checks.

### 2026-11-16

- Built phases 1 to 6 from an empty directory; six feature commits on `main`.
- Added this file and `AGENTS.md`.
- Owner decisions: support this Pixel only; reuse existing VPN apps instead of a built-in client; include kill switch, IPv6 leak protection, rate limits and traffic statistics; no allowlist mode and no custom DNS blocking; do not push yet.

# Agent Handoff

This file is the single place where agents hand work over to each other. Read it before doing anything else, and update it before you stop. The rules for maintaining it are in [AGENTS.md](AGENTS.md).

Last updated: 2026-11-19

## Current state

All six planned phases are implemented and committed on the local `main` branch. A private GitHub remote `origin` (`yeeeee0529/AdvanceHotspot`) was created on 2026-11-19 at the owner's request and `main` was pushed the same day. Ask before pushing further changes unless the owner says otherwise. On 2026-11-19 the owner made the repository public, the package was renamed to `net.oh4ma.advancehotspot`, and GitHub Actions CI and tag releases were added (see Releases and CI).

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project skeleton, root check, environment screen | Done |
| 2 | Hotspot configuration and on/off | Done |
| 3 | Connected device list, nicknames, MAC blocking | Done |
| 4 | Per-device routing, egress selection, kill switch, IPv6 leak protection | Done |
| 5 | Custom intranet: subnet, static leases, local DNS, client isolation | Done |
| 6 | Per-device traffic statistics and rate limits | Done |
| UI | Material 3 Expressive rewrite, four tabs, FlClash-style liquid glass bottom bar, IP and speed cards on Hotspot | Done; on-device check partial (see Verification status) |

Last check results: `assembleDebug` passes, 29 unit tests pass, `lintDebug` reports 0 errors and 2 accepted warnings (`AndroidGradlePluginVersion`, `DataExtractionRules`).

On 2026-11-19 a Shizuku mode was added at the owner's request (not pushed). The owner chose a manual Root/Shizuku switch on the Environment tab (default Root) and to keep the foreground notification in Shizuku mode. Shizuku mode covers hotspot settings, on/off and device management only; routing, intranet and traffic need root and are disabled. See the 2026-11-19 (Shizuku mode) log entry.

The UI was rewritten to Material 3 Expressive on 2026-11-16. Only the `ui` package, `MainActivity`-adjacent theme, resources and docs changed; no view model, script generator or root-side code changed.

## Target and tooling

- Only one device is supported: Pixel 9 Pro XL, Android 17 (API 37), rooted with KernelSU. `minSdk` is 37 on purpose because the app calls hidden APIs of a single Android release.
- Kotlin with Jetpack Compose, AGP 9.5.1 with built-in Kotlin, Gradle wrapper 9.8.2, libsu 6.0.0. There is no core-ktx dependency; do not add one for syntax sugar.
- Compose Material 3 is pinned to `1.5.0-rc01` in `gradle/libs.versions.toml`, overriding the BOM. The owner chose this pre-release on purpose because the M3 Expressive components do not exist in the stable 1.4.0. Move to 1.5.0 stable once it is released.
- Icons are hand-written vector drawables in `res/drawable` (Material Symbols paths). There is no `material-icons` dependency; add new icons the same way.
- UI strings are Traditional Chinese (`zh-TW`) in `app/src/main/res/values/strings.xml`. Source comments are Traditional Chinese. Identifiers, diagnostics and this file are English.
- Human documentation is three files kept in sync: `README.md`, `docs/zh-TW/README.md`, `docs/en/README.md`. The full READMEs keep user-facing sections first and one consolidated "Known limits" list; add new limits there, not under the "How it works" sections.

## Commands

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Lint results are easiest to read from `app/build/reports/lint-results-debug.sarif`.

## Releases and CI

- `.github/workflows/ci.yml` runs the check command above on pushes to `main` and on pull requests and uploads the debug APK.
- `.github/workflows/release.yml` runs on `v*` tags: `assembleRelease` with `-PversionName=<tag without v>` and `-PversionCode=<run number>`, then `gh release create` with the APK attached.
- Signing reads `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` from the environment (`app/build.gradle.kts`); without them the release build is unsigned. The repository secrets hold the keystore as base64 plus the three values.
- The keystore and its password live only on the owner's Mac under `~/.android/` (`advancehotspot-release.jks` and a 600-mode text file next to it). Never print or commit them. Losing them means releases can no longer update an installed release build.
- Debug and release builds share the package name but not the signing key, so one cannot be installed over the other; uninstall first, which drops the root grant and settings.

## Architecture

| Package (under `app/src/main/kotlin/net/oh4ma/advancehotspot`) | Role |
| --- | --- |
| `root` | `RootShell`, the single entry point for root commands through libsu |
| `helper` | `HelperMain` and `SoftApBridge`: a command-line helper run with `app_process` as the shell user (Root mode). `HelperUserService` runs the same `HelperMain.execute` dispatch inside a Shizuku UserService (Shizuku mode); AIDL in `app/src/main/aidl/.../helper/` |
| `privilege` | `PrivilegeSettings` (stored mode, `rootFeaturesAvailable`), `ShizukuClient` (state, permission, UserService binding, shell), `PrivilegedShell` (read-only commands through root or Shizuku) |
| `hotspot` | Hotspot model, validation, and `HotspotController`, which invokes the helper |
| `devices` | Nicknames and last-seen hostnames stored by MAC address |
| `routing` | Routing policy, rule script generator, egress resolution, and `RoutingService` |
| `intranet` | Custom subnet settings and the script generator for dnsmasq and the DHCP redirect |
| `traffic` | Counting and rate limit rules, counter parsing, stored limits and totals |
| `ui` | Compose screens and view models: `hotspot`, `devices`, `network`, `environment`; shared M3 Expressive building blocks in `ui/components/Expressive.kt` |

`RoutingService` is one foreground service shared by routing, intranet and traffic. It runs when the hotspot is on and any of the three is enabled (`RoutingRuntime.sync`). `sync` reads the hotspot state from the sticky `android.net.conn.TETHER_STATE_CHANGED` broadcast (extra `tetherArray`), unless the caller passes `hotspotOn`; `HotspotViewModel` passes it after its own start or stop, because the sticky broadcast may lag the AP state. A stopped service does not watch the hotspot: turning the hotspot on from Quick Settings applies no rules until the app is opened (owner decision 2026-11-18). On every trigger it applies intranet, then routing, then traffic. Traffic must be applied last so that its chain sits before the routing chain in `FORWARD`.

The UI has four tabs in `MainScreen` with a floating liquid-glass navigation bar, `FloatingNavigationDock` in `ui/components/FloatingNavigationDock.kt`, modeled on FlClash's `NavigationDock` (sizes, springs and glass values taken from its `prism_ui` source). `MainScreen` records the tab content into a `GraphicsLayer` (`backdrop`) and draws it; the dock draws that layer again through an AGSL `RuntimeShader` (`ui/components/LiquidGlass.kt`) to refract and blur it, and draws the lifted lens by re-recording backdrop plus bar into a third layer. It floats over the content; `MainScreen` provides `LocalDockInset`, and `ScreenScaffold` drops the bottom system inset, adds the dock inset to the content's bottom padding and lifts its floating action button above the dock. Each tab owns its own `ScreenScaffold` (large flexible top app bar). The Devices and Network tabs share one `DevicesViewModel` (both call `viewModel()` with the activity as owner) and both run `DevicesPolling`; only one is visible at a time, so only one poll loop runs. Grouped settings use `SegmentedGroup`, which hands each item its position-based `ListItemShapes`.

The Hotspot tab also shows a public IP card (`PublicIpViewModel`, `system/PublicIpLookup.kt`) and the hotspot's total speed, which it reads from the shared `DevicesViewModel` and its `DevicesPolling`. The IP lookup is the app's only outbound connection (`INTERNET` permission added 2026-11-19); it tries ident.me, ipwho.is and api.ip.sb in order through the default network, and re-runs on a default-network callback.

All root-side state is produced by pure script generators (`RoutingPlan.toScript`, `IntranetConfig.toScript`, `buildTrafficScript`). Each one validates every external value against a strict pattern before it enters a shell script. Keep that property when you add inputs.

## Findings that cost time to discover

- **Shizuku started with root runs its server and UserService as uid 0**, so it cannot drive the hotspot APIs (same package-uid check as for root). To restart it as shell: stop it from the Shizuku app menu first (an ADB shell cannot kill the root `shizuku_server`, the starter fails with `can't kill`), then run `adb shell <shizuku apk dir>/lib/arm64/libshizuku.so` (the directory comes from `pm path moe.shizuku.privileged.api`). The app's Shizuku permission survives the restart and app reinstalls.
- **Granting KernelSU root to Shell (uid 2000) makes `adb shell` itself root** (`uid=0`, context `u:r:ksu:s0`). The adb-started `shizuku_server` (uid 2000) disappeared around the time of that grant; that KernelSU kills the uid's processes on a grant is a guess, not confirmed. Afterwards, running `libshizuku.so` from `adb shell` starts Shizuku as root; `su 2000 -c <libshizuku.so>` starts it as uid 2000 but in the `ksu` domain (`runcon u:r:shell:s0` fails with Permission denied on exec).
- **After Shizuku restarts, an already running AdvanceHotspot may not get the new binder** and shows Shizuku 未執行 until the app process is restarted. Seen twice when Shizuku was started from the `ksu` domain; once, when it was started from a plain shell-domain `adb shell`, the running app did get it. Not investigated further.
- **`uiautomator dump` fails with `could not get idle state` while the app is in front** (also noted earlier). Tap by coordinates taken from `screencap` instead; screenshots are 1344x2992.
- **Root-only features are gated in the stores**: `RoutingStore.enabled`, `IntranetStore.enabled` and `TrafficStore.enabled` return false in Shizuku mode while keeping the saved value. `RoutingService` captures the mode at `onCreate`; `RoutingRuntime.restart` stops and restarts it on a mode switch so the old instance tears down rules with root.
- **`HelperMain.createShellContext` uses `ActivityThread.currentActivityThread()` before `systemMain()`**, because the Shizuku UserService process already has an `ActivityThread`. Calling `systemMain()` a second time was avoided, not tried.
- **The renamed package `net.oh4ma.advancehotspot` was first installed on the phone on 2026-11-19 (Shizuku session)** and has no KernelSU grant. The old `me.yeeeee.advancehotspot` app is still installed, still running its service, and holds the owner's routing, intranet and traffic state.

Read these before changing the helper, the DHCP path, or the firewall rules.

- **Stable Compose Material 3 1.4.0 lacks the M3 Expressive components.** Only `ShortNavigationBar` and `MotionScheme` are there. `MaterialExpressiveTheme`, `ButtonGroup`, `ToggleButton`, `LoadingIndicator`, `FloatingToolbar`, `MaterialShapes`, the flexible top app bars and `SegmentedListItem` need 1.5.0 (pinned to `1.5.0-rc01`). The source jar on Google Maven (`material3-android-1.5.0-rc01-sources.jar`) is the quickest reference for their signatures.
- **A toggleable `SegmentedListItem` paints its whole row in the selected container color when checked.** `SwitchItem` overrides the selected colors with the normal ones so a switch row does not look selected.
- **Reinstalling the app as a fresh install drops its KernelSU root grant and all stored settings.** The helper then fails with `su: inaccessible or not found` (exit 127). Check `dumpsys package net.oh4ma.advancehotspot | grep firstInstallTime` before assuming a code regression.

- **FlClash's dock widget is not in the FlClash repository.** `NavigationDock`, `LiquidGlass` and the 22 KB `liquid_glass.frag` live in the `chen08209/prism_ui` submodule (`lib/navigation_dock.dart`, `lib/liquid_glass.dart`, `shaders/liquid_glass.frag`). FlClash's glass is an optional setting; the owner's phone shows the non-glass dock.
- **A Compose backdrop for glass works by drawing one `GraphicsLayer` in two places.** Record the content in `drawWithContent` (`layer.record { drawContent() }`, then `drawLayer(layer)`), and in the dock draw the same layer translated by `-positionInRoot` inside another layer whose `renderEffect` is the shader. Content changes reach the dock without re-recording it. `dumpsys gfxinfo` shows 0 frames while idle, so it does not redraw in a loop.
- **`adb shell input motionevent DOWN/MOVE/UP` drives press-and-drag gestures** and lets you screenshot mid-gesture. `sips` cannot crop at an offset here; `ffmpeg -vf crop=w:h:x:y` can.
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
- **The 10-client cap is the Wi-Fi firmware's `maxassoc`, confirmed 2026-11-17.** The stored config has `maxClients` 0 (unlimited), `SoftApCapability` and `config_wifiHardwareSoftapMaxClientCount` say 32 and the framework never rejected anyone, yet the count climbs to exactly 10 and then flips between 9 and 10. Reading the iovar from the bcm4390 firmware gives `maxassoc = 10` on both the hotspot and the client interface (`bss_maxassoc = 19`). The driver module `bcmdhd4390.ko` contains no `maxassoc` string, so nothing on the host ever raises the firmware default, and the generated hostapd config has no `max_num_sta`. The firmware map has `wlc_ap_set_maxassoc` and `wlc_ap_get_maxassoc_limit`, so a set path exists, but **10 is also the firmware's hard ceiling**: with the AP up, `WLC_SET_VAR maxassoc 10` succeeds while 11, 12, 16, 19, 24 and 32 all fail with `ERANGE` (firmware `BCME_RANGE`). The ceiling is compiled into the firmware image; the host-memory station extension the driver knows about (`max_scb_probe`, `host_scb`) is not supported by this firmware, and `dump tunables` does not list a station count. No iovar raises it. Do not spend more time on this path; the failed sets changed nothing and no client dropped.
- **The firmware iovar is the only place the real client limit can be detected.** nl80211 does not report it: a `GET_WIPHY` split dump has no `NL80211_ATTR_MAX_AP_ASSOC_STA` (attr 202), as shell or as root, and the framework only knows the overlay value 32. Java has no ioctl that takes a struct pointer, so detecting it inside the app needs a native executable. `maxassoc` is readable on the client interface `wlan0` too, so it can be read while the hotspot is off.
- **How the iovar was read.** The phone has no `wl`, `iw` or `hostapd_cli`. A static arm64 binary that sends `WLC_GET_VAR` (262) through `SIOCDEVPRIVATE` with the `dhd_ioctl` struct (`cmd, buf, len, set, used, needed, driver=0`) works as root. This Mac has no NDK; `zig cc -target aarch64-linux-musl -static` (zig installed with Homebrew on 2026-11-17) builds it. The set counterpart is `WLC_SET_VAR` (263) with `set=1` and the buffer `name\0` followed by a little-endian int32. Text dumps use `WLC_GET_VAR` with the buffer `dump\0<section>\0`.
- **Clients without a nickname or DHCP hostname now get `device-<last octet>.lan`.** Before 2026-11-17 `buildHostEntries` skipped them; 7 of 11 leases had hostname `*` and no nicknames were stored, so most clients had no name. Many phones send no hostname.
- **`RoutingService` used to start only when the Devices or Network tab was opened.** After `adb install -r` or a process kill it stayed down until then, so the hosts file and rules were not refreshed. `MainActivity.onCreate` now calls `RoutingRuntime.sync`. It is not in `App.onCreate` on purpose: a process started in the background may not start a foreground service.
- **Android requires a notification for a foreground service, so the only way to clear the routing notification is to stop the service.** Before 2026-11-18 it ran whenever routing, intranet or traffic was enabled, even with the hotspot off. Keeping it in the background without a notification would need the app on the device-idle allowlist (a system setting change), and restarting it when the hotspot comes back would need a root-side watcher that calls `am start-foreground-service`. The owner chose neither; see Architecture.
- **toybox `nc -u -w` never exits when the peer stays silent or after the reply.** Use `timeout 5 nc -u -q 2 <ip> 53 < query.bin`. A raw DNS query through it is a workable resolver test, since the phone has no `nslookup`.
- **Without root, `run-as net.oh4ma.advancehotspot` can still read `files/intranet/` (leases, hosts) and `shared_prefs`,** because the build is debuggable and the root-written files are world-readable. `dumpsys wifi` and `ip neigh` also work as the shell user.
- **A blocked-then-unblocked client does not always redo DHCP.** It often keeps its lease. To force a fresh DHCP exchange, ask the owner to toggle Wi-Fi on the client.

## Verification status

Shizuku mode (2026-11-19), checked on the phone with Shizuku restarted through ADB as the shell user:

| Area | Status |
| --- | --- |
| Mode switch, Network tab switches disabled with hint, Environment hero states | Verified on device by screenshot: Root (not granted), Shizuku not authorized with the Request permission button, Shizuku authorized |
| Shizuku permission request and UserService | Verified: the dialog appeared, after allowing it a `net.oh4ma.advancehotspot:helper` process ran as `shell`; kernel and SELinux values came through `PrivilegedShell` |
| Hotspot status, settings read, on and off through Shizuku | Verified: status and SSID loaded, the hotspot started (channel 149) and stopped from the app; the foreground service and notification came up and went away with it |
| Device list through Shizuku | Verified: one connected test client listed with IP and hostname |
| Device watch through the binder callback in `RoutingService` | Verified indirectly: with the hotspot on, the `:helper` process holds two persistent tethering callbacks (count drops by 2 when it dies). A one-off root `HelperMain watch` process also adds exactly 2, so this is the watch plus `TetheringManager`'s own per-process callback, not a leak. Event delivery to the app was not observed directly |
| `WRONG_UID` state (Shizuku started with root) | Verified on device by screenshot after a relaunch of the app: 以 root 啟動 with the hint |
| Saving settings, blocking and temporary disconnect through Shizuku | Not tested |
| Root mode after the refactor (`SoftApBridge.startWatching`, `HelperMain.execute`) | Verified after the owner granted KernelSU: root status, hotspot on through the root helper, `HelperMain watch` running as shell under `su 2000`, and `ah_traffic` rebuilt with the connected client's address (so watch events reached the service) |
| Rule teardown when switching from Root to Shizuku | Verified with the hotspot on and traffic statistics enabled: `ah_traffic` left `FORWARD` and was deleted, the root watch process ended, the service restarted in Shizuku mode and the `:helper` process took over |

Earlier areas:

| Area | Status |
| --- | --- |
| Hotspot on/off, band, forced channel, max clients | Verified on device |
| Device list | Verified on device |
| Internet switch, VPN egress, kill switch | Verified on device. On a real VPN drop only the rule swap was observed, not dropped packets |
| Custom subnet, static lease, local DNS, rebuild after hotspot restart | Verified on device |
| Fallback `device-<n>.lan` names and service start on app open | Verified on device 2026-11-17: all 8 connected clients listed in the hosts file, a raw DNS query for a fallback name returned the right address, dnsmasq was reloaded without a restart |
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
| Routing service stops with the hotspot (2026-11-18) | Verified on device: app open with hotspot off starts no service; in-app start brings the service and notification up; force-stop and reopen with the hotspot on restarts it through the sticky broadcast; in-app stop removes service and notification and leaves no dnsmasq or watcher process. Turning off from Quick Settings not tested |
| Persistent notification with egress and stop button (2026-11-19) | Verified on device: after turning the hotspot on in the app, the notification reads 熱點已開啟 / 目前出口 tun0 (VPN active) and carries one action. The stop button itself was not pressed (a client was connected) |
| Device sorting by usage and Hotspot auto refresh (2026-11-19) | Hotspot tab on device: the refresh button is gone and the status updated to on without a manual refresh. Device sorting not checked |
| Temporary disconnect button (2026-11-19) | Built only; not run on the phone |
| FlClash-style floating navigation bar (2026-11-19) | Verified on device in dark theme: tap switches tab, dragging the highlight from the last tab to the first switches tab, the end of the Network tab scrolls clear of the bar. Owner checked the light theme. The Hotspot save toolbar above the bar and large font scale not checked |
| Liquid glass, lens, jelly, haptics on the dock (2026-11-19) | Verified on device by screenshots mid-gesture: glass bar, lifted magnifying lens, drag across tabs and past the edge, release selects the tab, idle draws no frames. Haptic ticks and mouse hover not verified (cannot be observed through adb) |
| Public IP card and speed cards on the Hotspot tab (2026-11-19) | Verified on device: IP, country 台灣 and organization shown; speed cards show 0 with the hotspot off; the Network tab keeps only the reset button. Re-lookup on network change and non-zero speeds not checked |
| Max-clients hint without a number (2026-11-17) | Built and installed; not seen on screen. `uiautomator dump` fails with `could not get idle state` while the app is in front |

## Known limits

- Shizuku mode works only when Shizuku runs as the shell user (started with ADB or wireless debugging). Started with root, its UserService is uid 0 and the hidden APIs reject it; the app reports this as `WRONG_UID`.

- Rate limits and traffic counters cover IPv4 only.
- Devices with a non-default routing policy have no IPv6.
- A device bound to a specific egress cannot resolve `.lan` names, because its DNS is redirected to that network's DNS server.
- Mobile data through 464XLAT is not handled: rules match the base interface, not the stacked `v4-` interface.
- The intranet subnet is always a /24.
- The routing service does not start when the hotspot is turned on from Quick Settings or restored at boot while the app is closed; rules apply once the app is opened. Turning the hotspot off from Quick Settings leaves the service and its notification until the app is opened.
- Turning routing or traffic off does not re-attach BPF offload; it returns the next time the hotspot starts.

## State left on the test phone

- 2026-11-19 (Shizuku session): `net.oh4ma.advancehotspot` debug build installed for the first time. The owner then granted KernelSU superuser to it and to Shell (`com.android.shell`), so `adb shell` is root. The app is left in Root mode with routing, intranet and traffic off; it also holds a Shizuku permission. Shizuku was restarted several times and now runs as uid 2000 in the `ksu` domain (started with `su 2000`); it returns to root if the owner restarts it with root or the phone reboots with start-on-boot. Shizuku had 0 authorized apps before. The hotspot was turned on and off with the owner's consent and is left off, as found; while it was on, up to 9 remembered clients joined without the old app's rules. The old `me.yeeeee.advancehotspot` app was not touched; the two apps share iptables chain names and the `HelperMain watch` pkill pattern, so either app's teardown removes the other's rules.

- As found on 2026-11-16 (DHCP fix session), set by the owner and left unchanged: hotspot on with several clients, intranet on with a custom gateway, routing on, traffic on, and a VPN active on the phone. The routing chain currently drops all forwarding for every client; this is the owner's policy state, not something an agent set.
- The app was updated in place (`adb install -r`) with the DHCP fix; settings and the root grant were kept.
- `adb shell` has root again; the owner re-granted it on 2026-11-17 after it had gone missing earlier that day.
- The app was updated in place twice on 2026-11-17 (fallback names, service start). Clients stayed connected and both dnsmasq processes kept their pids.
- Probe files pushed to `/data/local/tmp` on 2026-11-17 were removed. Firmware `maxassoc` is at its original value of 10.
- 2026-11-19: hotspot left on with one connected client; latest build installed.
- 2026-11-18: the hotspot was found off. For the notification test the agent turned it on and off again in the app with the owner's consent; it is left off. `adb shell` has no root again (`su: inaccessible or not found`); the app's own root grant still works.
- Dark mode is on (`cmd uimode night yes`). Auto-rotate is on; its earlier value is unknown.

## Working with the phone

- The hotspot is usually shared with several other people's devices. Changes to DHCP, firewall rules or hotspot state affect all of them. Aim tests at the owner's own client and ask which one it is.
- Mask the passphrase when printing helper `status` output.
- Ask before pushing, creating a remote, or changing the KernelSU allowlist.

## Suggested next steps

Nothing is in progress. Candidates, none of them started or agreed with the owner:

000. Shizuku mode gaps: try saving settings and blocking through Shizuku; check whether a running app picks up a restarted Shizuku when it is started from a plain `adb shell` (consider `Shizuku.addBinderReceivedListener` to refresh the UI). The owner may want to revoke Shell's KernelSU grant and uninstall `me.yeeeee.advancehotspot`.

00. More than 10 clients needs a route around the firmware, since the limit cannot be raised (see findings). Candidates, none started or agreed: put a travel router or repeater in NAT/WISP mode behind the hotspot so it counts as one client; feed an external access point through USB or Ethernet tethering; detect the real limit with a native `maxassoc` reader (needs the NDK). The owner declined that on 2026-11-17 and chose to show no number instead.

0. Finish the on-device check of the new UI: floating save toolbar, device rows and sheet with a real client, and light theme.

1. Close the verification gaps above, starting with a real speed test under a rate limit.
2. Let egress-bound devices resolve `.lan` names, for example with one DNS instance per egress.
3. Extend rate limits and counters to IPv6.
4. Handle 464XLAT for mobile data egress.
5. Replace the 4-second polling in the Devices screen with the service's watcher events.

## Handoff log

Newest entry first. Add one entry per working session.

### 2026-11-19 (Shizuku mode)

- Owner request: add a Shizuku mode. Owner decisions: manual Root/Shizuku switch on the Environment tab, default Root; keep the foreground notification in Shizuku mode; allowed restarting Shizuku through ADB as shell, granting the app's Shizuku permission, and turning the hotspot on and off once.
- Change: Shizuku `api` and `provider` 13.1.5, `ShizukuProvider` in the manifest, AIDL enabled. New `privilege` package and `HelperUserService`. `SoftApBridge.watch` became `startWatching`, returning an unregister function; `HelperMain` keeps the stdin-EOF wait and exposes `execute`. `HotspotController` routes by mode and adds `watchViaShizuku`. Root-only stores read as disabled in Shizuku mode; the Network tab disables their switches with a hint. `EnvironmentProbe` uses `PrivilegedShell` and reports the mode; Hotspot tab refreshes on a mode switch. Three READMEs document the mode.
- Checks: `assembleDebug`, `lintDebug` (same 2 accepted warnings), 29 unit tests pass. On the phone, see Verification status (Shizuku mode): Shizuku mode, Root mode after the refactor, and the Root-to-Shizuku teardown were checked after the owner granted KernelSU to the app and to Shell.
- Unfinished: saving settings and blocking through Shizuku are untested.

### 2026-11-19 (public repository, package rename, CI and releases)

- Owner requests: make the repository public; add a GitHub workflow; publish release APKs; rename the package.
- Owner decisions: publish as is, although `IntranetTest.kt` holds what looks like a real device's MAC address and hostname in its lease fixture (in history since `20701ab`); package name all lower case, `net.oh4ma.advancehotspot`, for `applicationId`, `namespace` and Kotlin packages; the agent generates a new release key and stores it as repository secrets.
- Change: sources moved to `net/oh4ma/advancehotspot`; signing config and tag-driven version in `app/build.gradle.kts`; CI and release workflows; READMEs gained install-from-release and a CI/release paragraph. See Releases and CI.
- Checks: `assembleDebug`, `lintDebug` (same 2 accepted warnings), 29 unit tests, and a locally signed `assembleRelease` (apksigner verified, package `net.oh4ma.advancehotspot`). CI passed on GitHub. The repository was then made public. At the owner's request `v0.1.0` was tagged and pushed; the release workflow passed and the release has `AdvanceHotspot-v0.1.0.apk` (versionName 0.1.0, versionCode 1), whose signing certificate matches the local keystore. **Not run on the phone**: no device was connected. The renamed package is a new app there: it needs a fresh KernelSU grant, starts with no settings, and the old `me.yeeeee.advancehotspot` app is still installed and should be removed by the owner.

### 2026-11-19 (README rewrite)

- Owner request: clean up the human documentation without touching this file; then, separately, record it here and push.
- Change: the two full READMEs are reordered to Requirements, Build and install, Features, Usage, Known limits, Development, How it works. Known limits from three sections are merged into one list; the Devices tab cell is split into "Temporary disconnect", "Background service and notification" and "Public IP" subsections; the architecture table lists package names under one base path. The root README replaces the phase table with a feature summary and a one-line status.
- Owner decisions: do not describe the navigation bar as modeled on FlClash; the root status is a feature summary, not phases. The `#status` anchor in the root README is gone.
- Docs only; no build or test run. Committed as `edaf883`, then this entry, and both pushed to `origin`.

### 2026-11-19 (push)

- Owner request: pushed `main` to `origin` up to `f5d0c92` (FlClash-style dock, liquid glass, IP and speed cards). This note is committed after the push and is not pushed yet.

### 2026-11-19 (liquid glass dock, IP and speed on Hotspot tab)

- Owner requests: add FlClash's liquid glass, jelly stretch and hover to the dock; haptic ticks while sliding; move the network speed to the Hotspot tab; add FlClash-like IP detection showing only IP, organization and country.
- Owner decisions: speed is the hotspot total rate, moved from the Network tab (the reset button stays there); glass is a hand-written AGSL shader, no new dependency.
- Change: `LiquidGlass.kt` (shader: capsule SDF refraction, magnify, dispersion, saturation, tint, rim, touch glow, with a blur chained before it); `FloatingNavigationDock` rewritten with FlClash's lift, travel, follow, press, jelly, glow and hover springs, rubber-band overdrag and bar pull, fling projection, and `CLOCK_TICK` haptics when the lens crosses a tab (FlClash calls `HapticFeedback.selectionClick`). Glass values are approximations: FlClash's haze mix (72% at 15.6 sigma) became a single 10 dp blur, and its 60 dp refraction became 18 dp. New `PublicIpLookup`, `PublicIpViewModel`, `HotspotOverview.kt`, arrow icons, `INTERNET` permission.
- Checks: build, lint (same 2 accepted warnings), 29 unit tests pass, no Kotlin warnings. On-device checks in Verification status. No unit tests for the IP parsers: `org.json` is a stub in local unit tests.

### 2026-11-19 (FlClash-style bottom bar)

- Owner request: make the bottom bar look like FlClash's. FlClash (installed on the phone) defaults to a floating pill dock; its widget is `NavigationDock` in the `chen08209/prism_ui` submodule, not in the main FlClash repo.
- Change: replaced `ShortNavigationBar` with `FloatingNavigationDock` (new file under `ui/components`). Ported the non-glass look only: 62 dp pill, 4 dp padding, 21 dp edge margin, items up to 86 dp wide, 28 dp icons, 10 sp labels, a sliding highlight with the same spring values, a press bounce, two soft shadows, surface colors by FlClash's floating level. Not ported: liquid glass, jelly stretch, hover effects. Height is fixed at 62 dp, so very large font scales may clip labels.
- Checks: build, lint (same 2 accepted warnings) and 29 unit tests pass. Verified on the phone; see Verification status.

### 2026-11-19 (app name)

- Owner request: the app name is now "Advance Hotspot" (`app_name` in `strings.xml`; launcher label and nothing else). Build, lint and tests pass.

### 2026-11-19 (on-device test, partial)

- Installed the latest build in place (root grant kept). Found the hotspot off, turned it on in the app; one client joined. Left on. Not tested because it would disturb a connected client: the notification's stop button, the disconnect button, device sorting. Ask the owner which client is theirs first.
- Note: driving the phone with `adb shell input tap` worked for the in-app toggle (first tap right after install was lost while the app was still loading).

### 2026-11-19 (kick cooldown three minutes)

- Owner decision: the temporary disconnect cooldown is three minutes. `DevicesViewModel.disconnect` now blocks the device, returns at once (no busy state during the wait), and a `GlobalScope` coroutine removes only that MAC from the live blocklist after `KICK_HOLD_MS` (3 min). It no longer restores a saved copy of the old list, so blocks the owner changes meanwhile survive.
- Known limit: if the process is killed during the three minutes, the device stays blocked until unblocked by hand. A persisted deadline would fix that; not done.
- Checks: build, lint, unit tests pass. Not verified on the phone.

### 2026-11-19 (device sort and automatic hotspot refresh)

- Owner request: sort devices by total network usage; make the Hotspot tab refresh automatic.
- Change: connected and offline device lists sort by `upBytes + downBytes` descending in `DevicesScreen` (the traffic flow is separate from the device snapshot, so sorting is done at render time; ties keep the old order; usage only exists when traffic statistics are on). The Hotspot tab's refresh button is gone; `HotspotPolling` calls `HotspotViewModel.poll()` every 3 s while the screen is resumed. `poll` is silent: it skips while busy, ignores failures, and keeps an unsaved draft. The Environment tab keeps its refresh button.
- Checks: build, lint, unit tests pass. **Not verified on the phone.** The list may reorder while the owner is about to tap a row; not judged yet.

### 2026-11-19 (private remote)

- Owner request: create a private remote with `gh`. Created `yeeeee0529/AdvanceHotspot` (private) and added it as `origin`. Then, at the owner's request, pushed `main` and set it to track `origin/main`.

### 2026-11-19 (temporary disconnect button)

- Owner request: a button to kick one device without blocking it for good.
- Change: `DevicesViewModel.disconnect` writes the blocklist with the device added, waits 2 s, then restores the original blocklist (in `NonCancellable`, so an error still restores it). Button "暫時中斷連線" in the device sheet for connected devices. READMEs updated.
- Checks: build, lint, unit tests pass. **Not verified on the phone.** Open question: whether 2 s is enough for the system to drop the client, and whether the client's auto-reconnect is immediate.

### 2026-11-19 (persistent hotspot notification)

- Owner request: the persistent notification should read "熱點已開啟", the current egress interface, and a "關閉熱點" button.
- Change: `RoutingRuntime.sync` now runs the service whenever the hotspot is on (no feature needed), since the service owns the notification. The notification shows the default egress interface when routing binds one, else the phone's active network interface, refreshed on each apply; the action stops the hotspot through the helper, then syncs the service off. Channel name is now 熱點狀態. READMEs (zh-TW, en) updated.
- Checks: `assembleDebug`, `lintDebug`, `testDebugUnitTest` pass. **Not verified on the phone**: notification text, egress refresh, and the stop button (the hotspot is shared with other people's devices; ask the owner before toggling it).

### 2026-11-18 (routing notification after hotspot off)

- Owner report: the routing notification stays after the hotspot is turned off. Cause: `RoutingService` ran whenever any feature was enabled, independent of the hotspot.
- Owner decision: handle it only for the app's own hotspot switch; no device-idle allowlist and no root-side watcher.
- Change: `RoutingRuntime.sync` also requires the hotspot to be on; `HotspotViewModel` calls it after start, stop and a restarting save. READMEs (zh-TW, en) describe the lifecycle.
- Verified on the phone (see Verification status). Checks: `assembleDebug` passes, 29 unit tests pass, lint 0 errors and the same 2 accepted warnings.

### 2026-11-17 (10-client cap and missing local DNS names)

- Owner report: at most 10 devices can connect, and some devices have no local DNS name. The owner required that the live network not be interrupted.
- 10-client cap: confirmed as firmware `maxassoc = 10`. Owner decision: research a bypass, then try it live. Result: the firmware rejects every value above 10, so it cannot be raised from the host; see the findings. The live attempt changed nothing and dropped no client.
- Owner decision on the limit shown in the app: no hard-coded 10, and no native detection either (it needs the NDK); the max-clients hint no longer states a number. Validation still rejects values above the framework's 32, so 11 to 32 can be saved but the firmware stops at 10.
- Missing names: fixed with a fallback label in `buildHostEntries`, agreed by the owner, verified on the phone.
- Found and fixed along the way: the service did not come back after an app update until the Devices or Network tab was opened.
- Tooling change: installed `zig` with Homebrew to cross-compile the read-only probe, because the Mac has no NDK.
- Checks: `assembleDebug` passes, 29 unit tests pass, lint 0 errors and the same 2 accepted warnings.

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

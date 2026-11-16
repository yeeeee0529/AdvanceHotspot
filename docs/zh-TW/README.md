# AdvanceHotspot（進階熱點）

[總覽](../../README.md) · [English](../en/README.md)

AdvanceHotspot 是一個 Android app，在已 root 的裝置上擴充系統 Wi-Fi 熱點。無線電部分沿用系統 SoftAP，再透過 root 補上 Android 沒有開放的控制項。

## 規劃功能

- 熱點設定：SSID、密碼、加密方式、頻段、頻道、最大連線數、隱藏 SSID
- 讓熱點流量走手機上任何已連線 VPN app 的 tun 介面
- 連線裝置清單，顯示名稱、IP 位址與 MAC 位址
- 每台裝置的聯網開關與出口網卡（Wi-Fi、行動網路、VPN）
- Kill switch：出口斷線時裝置直接斷網，不會改走其他介面
- IPv6 防漏
- 內網：自訂網段與閘道、依 MAC 綁定固定 IP、本地 DNS 名稱、裝置間隔離開關
- 每台裝置的限速與流量統計

## 需求

- Pixel 9 Pro XL、Android 17（API 37）。app 依賴單一 Android 版本的隱藏 API，目前不支援其他裝置。
- 透過 KernelSU 取得 root
- 建置需要 JDK 17 以上與 Android SDK platform 37

## 建置與安裝

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

KernelSU 不會跳出授權提示。請開啟 KernelSU 管理器，替 AdvanceHotspot 開啟超級使用者權限，再回到 app 按「重新整理」。

## 檢查

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest
```

## 架構

| 路徑 | 職責 |
| --- | --- |
| `app/src/main/kotlin/.../root` | root 指令的單一出入口（libsu） |
| `app/src/main/kotlin/.../helper` | 以 `app_process` 用 shell 身分執行的指令列 helper，負責呼叫 SoftAP 與 tethering 的隱藏 API |
| `app/src/main/kotlin/.../hotspot` | 熱點資料模型、驗證，以及呼叫 helper 的控制器 |
| `app/src/main/kotlin/.../devices` | 以 MAC 位址保存的裝置標註 |
| `app/src/main/kotlin/.../routing` | 路由策略、規則腳本產生器、出口解析與前景服務 |
| `app/src/main/kotlin/.../system` | 裝置與網路探測、指令輸出解析 |
| `app/src/main/kotlin/.../ui` | Jetpack Compose 畫面與 view model |

## 熱點控制的運作方式

SoftAP 與 tethering 的隱藏 API 會檢查呼叫端的套件名稱是否屬於呼叫的 uid。root（uid 0）沒有對應套件，呼叫會被拒絕。因此 app 只用 root 來降權成 shell 使用者（uid 2000），再用 `app_process` 執行自己 APK 內的小型 helper。`com.android.shell` 擁有 `NETWORK_SETTINGS` 與 `TETHER_PRIVILEGED`，足以寫入 SoftAP 設定並啟動 tethering。

指定頻道在手機同時連著不同頻道的 Wi-Fi 時仍會生效（已在 Pixel 9 Pro XL 實測）。熱點開啟中儲存設定會重新啟動熱點，已連線的裝置會暫時斷線。

## 每裝置路由的運作方式

在「裝置」分頁開啟「每裝置路由控制」後，會有一個前景服務讓規則與連線裝置、手機的網路狀態保持同步。它透過一個常駐的 helper 行程接收裝置與網路的變動通知，不需要輪詢。

每台裝置有一個上網開關和一個出口：全域預設、跟隨系統、Wi-Fi、行動網路、VPN 或乙太網路。出口以網路類型而非介面名稱指定，因為 VPN 重連後介面名稱可能改變。

對於綁定出口的裝置，服務會安裝：

- 一條 `ip rule` 把它的流量送往該介面的路由表，後面接一條 `unreachable` 規則
- filter 規則，只放行從該介面出去的流量，該裝置其餘流量全部丟棄
- DNAT 規則，把它的 DNS 查詢導向出口網路的 DNS 伺服器；否則手機自己的 DNS 轉送器會經由系統上游解析
- IPv6 丟棄規則，因為 IPv6 無法導向指定出口

Kill switch 就是這組 filter 規則：出口消失時裝置會被完全擋下，不會改走其他網路。即使服務被終止，規則仍留在核心中。

服務也會移除系統掛在熱點介面上的 tethering BPF offload 程式。被 offload 的連線會繞過 netfilter，不移除的話，裝置被擋下後既有連線仍會繼續通。

已知限制：

- 設有非預設策略的裝置沒有 IPv6 連線。
- 裝置自行使用加密 DNS（DoH 或 DoT）時，流量仍會走指定出口，但 DNS 導向對它無效。
- 以行動網路為出口尚未實測，且電信商以 464XLAT 提供 IPv4 的情況還沒處理。
- 關閉路由控制後不會重新掛回 BPF offload 程式，下次開啟熱點時才會恢復。

## 狀態

階段 1 到 4 已完成：app 可設定與開關熱點、列出連線裝置並支援暱稱與 MAC 封鎖，也能控制每台裝置的上網權限與出口，並具備 kill switch。自訂網段、固定 IP、本地 DNS、裝置間隔離、限速與流量統計尚未實作。各階段進度見[總覽](../../README.md#status)。

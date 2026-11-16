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
| `app/src/main/kotlin/.../system` | 裝置與網路探測、指令輸出解析 |
| `app/src/main/kotlin/.../ui` | Jetpack Compose 畫面與 view model |

## 狀態

階段 1 已完成：專案可建置、安裝，並顯示 root 狀態、核心版本、必要網路工具與啟用中的網路介面。熱點控制尚未實作。各階段進度見[總覽](../../README.md#status)。

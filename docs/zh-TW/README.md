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

## 介面

介面遵循 Material 3 Expressive 設計規範（Compose Material 3 `1.5.0-rc01`、動態配色、expressive 動態效果）。底部導覽列有四個分頁：

| 分頁 | 內容 |
| --- | --- |
| 熱點 | 熱點狀態、開關按鈕與熱點設定。有未儲存的變更時，底部會出現浮動的儲存工具列。 |
| 裝置 | 已連線、未連線但有設定、已封鎖的裝置。點一下裝置會開啟面板，顯示流量、路由、詳細資料與操作（重新命名、固定 IP、限速、暫時中斷連線、封鎖）。「暫時中斷連線」會先封鎖該裝置兩秒讓系統踢除它，再還原封鎖名單，裝置之後可自行重連。 |
| 網路 | 每裝置路由控制、自訂內網、流量統計的總開關與全域設定。 |
| 環境 | Root 狀態、系統資訊、網路工具與啟用中的網路介面。 |

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
| `app/src/main/kotlin/.../traffic` | 計數與限速規則、計數器解析、上限與累計用量的儲存 |
| `app/src/main/kotlin/.../intranet` | 自訂網段設定，以及管理 dnsmasq 與 DHCP 轉向的腳本產生器 |
| `app/src/main/kotlin/.../system` | 裝置與網路探測、指令輸出解析 |
| `app/src/main/kotlin/.../ui` | Jetpack Compose 畫面（Material 3 Expressive）、共用元件與 view model |

## 熱點控制的運作方式

SoftAP 與 tethering 的隱藏 API 會檢查呼叫端的套件名稱是否屬於呼叫的 uid。root（uid 0）沒有對應套件，呼叫會被拒絕。因此 app 只用 root 來降權成 shell 使用者（uid 2000），再用 `app_process` 執行自己 APK 內的小型 helper。`com.android.shell` 擁有 `NETWORK_SETTINGS` 與 `TETHER_PRIVILEGED`，足以寫入 SoftAP 設定並啟動 tethering。

指定頻道在手機同時連著不同頻道的 Wi-Fi 時仍會生效（已在 Pixel 9 Pro XL 實測）。熱點開啟中儲存設定會重新啟動熱點，已連線的裝置會暫時斷線。

## 每裝置路由的運作方式

在「網路」分頁開啟「每裝置路由控制」後，會有一個前景服務讓規則與連線裝置、手機的網路狀態保持同步。它透過一個常駐的 helper 行程接收裝置與網路的變動通知，不需要輪詢。

服務在熱點開啟期間一律執行（即使三項功能都沒開），因為持久通知由它負責：顯示「熱點已開啟」、目前出口網卡，以及「關閉熱點」按鈕。出口為路由控制綁定的預設出口，否則為手機目前預設網路的網卡。在 app 內關閉熱點會停止服務並清除它的通知；在 app 內開啟熱點或開啟 app 時，若熱點已開啟就會把服務帶起來。服務停止期間不會偵測熱點狀態，所以從系統快速設定開啟熱點時，要開一次 app 規則才會套用；從快速設定關閉熱點時，通知會留著，直到在 app 內操作或下次開啟 app。

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

## 自訂內網的運作方式

在「網路」分頁開啟「自訂內網」後，同一個前景服務會接手熱點介面上的位址發放。

- 它在熱點介面加上第二組位址當作閘道，例如 `10.77.0.1/24`。熱點仍可從任何地方開啟，包含系統的快速設定。
- 它用手機內建的 `/system/bin/dnsmasq` 啟動兩個行程：一個只負責 DHCP，一個只在閘道位址上負責 DNS。
- 系統的 DHCP 服務佔住 UDP 67 埠且無法關閉，所以 dnsmasq 改聽 1067 埠，再用 NAT 規則把 DHCP 封包轉過去。廣播請求只改埠號、保留廣播位址；如果連位址一起改寫，來源為 `0.0.0.0` 的請求會被核心丟棄。
- NAT 規則只在一條連線的第一個封包上生效，而所有裝置的 DHCP 廣播共用同一個 conntrack（連線追蹤）項目。規則變動時若已有裝置在重試 DHCP，舊項目不會逾時；app 會暫時在 raw 表丟棄 DHCP 請求，等舊項目逾時（最多約 30 秒）後再放行。
- 手機連著 VPN 時，系統會把 root 行程的封包導向 VPN。app 加上一條優先序 10500 的 `ip rule`，讓手機送往內網的封包固定從熱點介面出去，DHCP 與 DNS 的回覆才到得了裝置。
- 固定 IP 綁定以 MAC 位址為鍵寫入 dnsmasq 的 hosts 檔。
- 本地名稱由裝置的暱稱產生，暱稱沒有可用字元時改用裝置自己回報的主機名稱，以 `名稱.lan` 解析；閘道本身是 `hotspot.lan`。名稱只保留英數字與連字號，所以全中文的暱稱會退回使用主機名稱。兩者都沒有的裝置（許多手機在 DHCP 時不送主機名稱）會得到 `device-<IP 最後一段>.lan`，例如 `device-24.lan`。
- 裝置間隔離是「熱點」分頁中的熱點設定，下次開啟熱點時生效。

已知限制：

- 網段固定為 /24。
- 已連線的裝置會沿用舊位址，直到重新連線或租約到期。關閉內網後也一樣，屆時請讓裝置重新連線。
- 綁定特定出口的裝置，DNS 會被導向該網路的 DNS 伺服器，無法解析 `.lan` 名稱。
- 裝置間隔離只驗證了設定能寫入，裝置之間是否真的互相隔離尚未實測。

## 流量統計與限速的運作方式

在「網路」分頁開啟「流量統計與限速」後，前景服務會替每台裝置的每個方向加上一條計數規則，每兩秒讀取一次計數器，顯示即時速率與累計用量。累計用量以 MAC 位址保存，重新啟動後仍在，直到你重設為止。

速率上限是排在計數規則前面的另一條規則，用 `hashlimit` 丟棄超過設定速率的封包。這是丟棄而不是排隊整形：TCP 會自行降速到上限附近，實測速度會在上限上下波動，不會剛好貼齊。

啟用期間會移除所有介面上系統的 tethering BPF offload 程式，因為被 offload 的封包不經過 netfilter，既不會被計數也不會被限速。

已知限制：

- 只對 IPv4 生效，IPv6 流量不會被計數或限速。
- 只有連線中的裝置可以設定上限。
- 已驗證限速規則會丟棄超速封包，但尚未用實際的裝置下載量測過速率。

## 狀態

規劃的六個階段都已完成：app 可設定與開關熱點、列出連線裝置並支援暱稱與 MAC 封鎖、控制每台裝置的上網權限與出口並具備 kill switch、提供自訂網段、固定 IP 綁定、本地 DNS 名稱與裝置間隔離，並顯示每台裝置的流量與可選的速率上限。各階段進度見[總覽](../../README.md#status)。

# AdvanceHotspot（進階熱點）

[總覽](../../README.md) · [English](../en/README.md)

AdvanceHotspot 是一個 Android app，在已 root 的手機上擴充系統 Wi-Fi 熱點。無線電部分沿用系統 SoftAP，再透過 root 補上 Android 沒有開放的控制項：讓熱點流量走 VPN、指定每台裝置的出口、自訂內網，以及每台裝置的流量統計與限速。

## 需求

- Pixel 9 Pro XL、Android 17（API 37）。app 依賴單一 Android 版本的隱藏 API，目前不支援其他裝置
- 透過 KernelSU 取得 root；沒有 root 時可改用 [Shizuku 模式](#shizuku-模式)，但只剩熱點設定、開關與裝置管理
- 建置需要 JDK 17 以上與 Android SDK platform 37

## 安裝

每個 [release](https://github.com/yeeeee0529/AdvanceHotspot/releases) 都附有已簽章的 APK。要自行建置 debug 版本：

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

套件名稱是 `net.oh4ma.advancehotspot`。KernelSU 不會跳出授權提示。請開啟 KernelSU 管理器，替 AdvanceHotspot 開啟超級使用者權限，再回到 app 的「環境」分頁下拉重新整理。

不使用 root 時，請改看 [Shizuku 模式](#shizuku-模式)。

## 功能

- 熱點設定：SSID、密碼、加密方式、頻段、頻道、最大連線數、隱藏 SSID
- 讓熱點流量走手機上任何已連線 VPN app 的 tun 介面
- 連線裝置清單，顯示名稱、IP 位址與 MAC 位址，可設定暱稱、封鎖或暫時中斷連線
- 每台裝置的上網開關與出口網卡（Wi-Fi、行動網路、VPN、乙太網路）
- Kill switch：出口斷線時裝置直接斷網，不會改走其他介面
- IPv6 防漏
- 內網：自訂網段與閘道、依 MAC 綁定固定 IP、本地 DNS 名稱、裝置間隔離
- 每台裝置的流量統計與限速
- 熱點分頁顯示出口 IP 與熱點總速率
- Shizuku 模式：不需要 root 也能使用熱點設定、開關與裝置管理

## 使用方式

介面文字跟隨手機語言：繁體中文（預設）、簡體中文或英文。介面採用 Material 3 Expressive（Compose Material 3 `1.5.0-rc01`、動態配色）。底部的浮動導覽列有四個分頁，可以點選，也可以按住後左右拖曳切換。

| 分頁 | 內容 |
| --- | --- |
| 熱點 | 熱點狀態與開關、出口 IP（IP、組織、國別）、熱點的總上傳與下載速率及累計用量、熱點設定。有未儲存的變更時，底部會出現儲存工具列 |
| 裝置 | 已連線、未連線但有設定、已封鎖的裝置，已連線與未連線的裝置依累計用量由多到少排序。點一下裝置會開啟面板，顯示流量、路由、詳細資料與操作 |
| 網路 | 每裝置路由、自訂內網、流量統計與限速的開關與全域設定，以及重設累計用量 |
| 環境 | Root 狀態、系統資訊、網路工具與啟用中的網路介面 |

熱點開啟中儲存設定會重新啟動熱點，已連線的裝置會暫時斷線。

速率與累計用量需要在「網路」分頁開啟流量統計才會顯示。

### 暫時中斷連線

裝置面板的「暫時中斷連線」會把裝置加入封鎖名單三分鐘，讓系統把它踢除，之後只把這台從封鎖名單移除，裝置即可重新連線。三分鐘內封鎖名單的其他變更會保留。若 app 行程在這三分鐘內被終止，裝置會一直留在封鎖名單中，需要手動解除封鎖。

### 背景服務與通知

熱點開啟期間，app 會執行一個前景服務，負責套用路由、內網與流量規則，並顯示持久通知：「熱點已開啟」、目前的出口網卡，以及「關閉熱點」按鈕。出口為路由控制綁定的預設出口，否則為手機目前預設網路的網卡。

在 app 內關閉熱點會停止服務並清除通知；在 app 內開啟熱點，或熱點已開啟時開啟 app，會把服務帶起來。服務停止期間不會偵測熱點狀態：

- 從系統快速設定開啟熱點後，要開一次 app，規則才會套用
- 從快速設定關閉熱點後，通知會留著，直到在 app 內操作或下次開啟 app

### 出口 IP

出口 IP 由公開的 IP 查詢服務查得，依序嘗試 ident.me、ipwho.is、api.ip.sb。請求走手機目前的預設網路，所以手機連著 VPN 時顯示的是 VPN 的出口。開啟 app、預設網路改變或按重新整理時會重查。這是 app 唯一的對外連線，因此需要 `INTERNET` 權限。

### Shizuku 模式

在「環境」分頁的「特權模式」可以切換 Root 與 Shizuku，預設為 Root。

| 功能 | Root | Shizuku |
| --- | --- | --- |
| 熱點設定與開關 | ✓ | ✓ |
| 裝置清單、暱稱、封鎖、暫時中斷連線 | ✓ | ✓ |
| 前景通知與「關閉熱點」按鈕 | ✓ | ✓ |
| 每裝置路由、kill switch、IPv6 防漏 | ✓ | ✗ |
| 自訂內網 | ✓ | ✗ |
| 流量統計與限速 | ✓ | ✗ |

使用步驟：

1. 安裝 [Shizuku](https://shizuku.rikka.app/)，並以 ADB 或無線偵錯啟動。以 root 啟動的 Shizuku 不能用，見下方說明
2. 在「環境」分頁選擇 Shizuku，按「請求授權」，在 Shizuku 的對話框中允許本 app

Shizuku 模式下，「網路」分頁的三個開關會停用，原本的設定會保留，切回 Root 模式後恢復。切換模式時，前景服務會重新啟動；從 Root 切到 Shizuku 時，已套用的規則會先清除。

## 已知限制

- 只支援 Pixel 9 Pro XL 與 Android 17
- 流量統計與限速只對 IPv4 生效，IPv6 流量不會被計數或限速
- 設有非預設路由策略的裝置沒有 IPv6 連線
- 綁定特定出口的裝置，DNS 會被導向該網路的 DNS 伺服器，無法解析 `.lan` 名稱
- 裝置自行使用加密 DNS（DoH 或 DoT）時，流量仍走指定出口，但 DNS 導向對它無效
- 以行動網路為出口尚未實測，電信商以 464XLAT 提供 IPv4 的情況也還沒處理
- 內網網段固定為 /24
- Shizuku 必須以 ADB 或無線偵錯啟動。以 root 啟動時，它的行程是 uid 0，熱點的隱藏 API 會拒絕呼叫，「環境」分頁會顯示「以 root 啟動」
- 已連線的裝置會沿用舊位址，直到重新連線或租約到期。關閉內網後也一樣，屆時請讓裝置重新連線
- 關閉路由控制或流量統計後，系統的 tethering BPF offload 程式不會重新掛回，下次開啟熱點時才會恢復
- 只有連線中的裝置可以設定速率上限
- 尚未在實機上驗證的部分：裝置間隔離是否真的隔離裝置（只驗證了設定能寫入）、限速對實際下載的效果（只驗證了規則會丟棄超速封包）

## 開發

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

GitHub Actions 會在每次 push 到 `main` 與每個 pull request 時執行同一組指令（`.github/workflows/ci.yml`），並保留 debug APK 作為 artifact。

要發布新版本，push 一個 tag，例如 `v0.2.0`。`.github/workflows/release.yml` 會以 tag 作為 `versionName` 建置已簽章的 release APK，並附加到 GitHub release。簽章金鑰從儲存庫 secrets `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 讀取。在本機執行 `assembleRelease` 時若沒有對應的 `RELEASE_*` 環境變數，產出的 APK 不會簽章。

原始碼位於 `app/src/main/kotlin/net/oh4ma/advancehotspot/`：

| 套件 | 職責 |
| --- | --- |
| `root` | root 指令的單一出入口（libsu） |
| `privilege` | 特權模式設定、Shizuku 連線，以及依模式選擇 root 或 Shizuku 的唯讀指令入口 |
| `helper` | 以 shell 身分呼叫 SoftAP 與 tethering 隱藏 API 的 helper：root 模式以 `app_process` 執行的指令列，Shizuku 模式則是 UserService |
| `hotspot` | 熱點資料模型、驗證，以及呼叫 helper 的控制器 |
| `devices` | 以 MAC 位址保存的裝置暱稱與主機名稱 |
| `routing` | 路由策略、規則腳本產生器、出口解析與前景服務 |
| `intranet` | 自訂網段設定，以及管理 dnsmasq 與 DHCP 轉向的腳本產生器 |
| `traffic` | 計數與限速規則、計數器解析、上限與累計用量的儲存 |
| `system` | 裝置與網路探測、指令輸出解析、出口 IP 查詢 |
| `ui` | Jetpack Compose 畫面、共用元件與 view model |

單元測試只涵蓋純函式的腳本產生器與解析器。動到 helper、路由、內網或流量的程式碼時，請在手機上實測。

## 運作方式

### 熱點控制

SoftAP 與 tethering 的隱藏 API 會檢查呼叫端的套件名稱是否屬於呼叫的 uid。root（uid 0）沒有對應套件，呼叫會被拒絕。因此 app 只用 root 來降權成 shell 使用者（uid 2000），再用 `app_process` 執行自己 APK 內的小型 helper。`com.android.shell` 擁有 `NETWORK_SETTINGS` 與 `TETHER_PRIVILEGED`，足以寫入 SoftAP 設定並啟動 tethering。

Shizuku 模式改用 Shizuku 的 UserService。以 ADB 啟動的 Shizuku 本身就是 shell 身分，UserService 行程也一樣，所以直接在其中執行同一套 helper 程式碼，透過 AIDL 回傳結果；裝置監看則改用 binder callback。

手機同時連著不同頻道的 Wi-Fi 時，指定的熱點頻道仍會生效（已在 Pixel 9 Pro XL 實測）。

### 每裝置路由

前景服務讓規則與連線裝置、手機的網路狀態保持同步。它透過一個常駐的 helper 行程接收裝置與網路的變動通知，不需要輪詢。

每台裝置有一個上網開關和一個出口：全域預設、跟隨系統、Wi-Fi、行動網路、VPN 或乙太網路。出口以網路類型而非介面名稱指定，因為 VPN 重連後介面名稱可能改變。對於綁定出口的裝置，服務會安裝：

- 一條 `ip rule` 把它的流量送往該介面的路由表，後面接一條 `unreachable` 規則
- filter 規則，只放行從該介面出去的流量，該裝置其餘流量全部丟棄
- DNAT 規則，把它的 DNS 查詢導向出口網路的 DNS 伺服器；否則手機自己的 DNS 轉送器會經由系統上游解析
- IPv6 丟棄規則，因為 IPv6 無法導向指定出口

Kill switch 就是這組 filter 規則：出口消失時裝置會被完全擋下，不會改走其他網路。即使服務被終止，規則仍留在核心中。

服務也會移除系統掛在熱點介面上的 tethering BPF offload 程式。被 offload 的連線會繞過 netfilter，不移除的話，裝置被擋下後既有連線仍會繼續通。

### 自訂內網

開啟自訂內網後，同一個前景服務會接手熱點介面上的位址發放：

- 在熱點介面加上第二組位址當作閘道，例如 `10.77.0.1/24`
- 用手機內建的 `/system/bin/dnsmasq` 啟動兩個行程：一個只負責 DHCP，一個只在閘道位址上負責 DNS
- 系統的 DHCP 服務佔住 UDP 67 埠且無法關閉，所以 dnsmasq 改聽 1067 埠，再用 NAT 規則把 DHCP 封包轉過去。廣播請求只改埠號、保留廣播位址；如果連位址一起改寫，來源為 `0.0.0.0` 的請求會被核心丟棄
- NAT 規則只在一條連線的第一個封包上生效，而所有裝置的 DHCP 廣播共用同一個 conntrack（連線追蹤）項目。規則變動時若已有裝置在重試 DHCP，舊項目不會逾時，所以 app 會暫時在 raw 表丟棄 DHCP 請求，等舊項目逾時（最多約 30 秒）後再放行
- 手機連著 VPN 時，系統會把 root 行程的封包導向 VPN。app 加上一條優先序 10500 的 `ip rule`，讓手機送往內網的封包固定從熱點介面出去，DHCP 與 DNS 的回覆才到得了裝置
- 固定 IP 綁定以 MAC 位址為鍵寫入 dnsmasq 的 hosts 檔
- 裝置間隔離是「熱點」分頁中的熱點設定，下次開啟熱點時生效

本地名稱以 `名稱.lan` 解析，閘道本身是 `hotspot.lan`。名稱取自裝置的暱稱；暱稱沒有可用字元時改用裝置自己回報的主機名稱。名稱只保留英數字與連字號，所以全中文的暱稱會退回使用主機名稱。兩者都沒有的裝置（許多手機在 DHCP 時不送主機名稱）會得到 `device-<IP 最後一段>.lan`，例如 `device-24.lan`。

### 流量統計與限速

前景服務替每台裝置的每個方向加上一條計數規則，每兩秒讀取一次計數器，顯示即時速率與累計用量。累計用量以 MAC 位址保存，重新啟動後仍在，直到你重設為止。

速率上限是排在計數規則前面的另一條規則，用 `hashlimit` 丟棄超過設定速率的封包。這是丟棄而不是排隊整形：TCP 會自行降速到上限附近，實測速度會在上限上下波動，不會剛好貼齊。

啟用期間會移除所有介面上系統的 tethering BPF offload 程式，因為被 offload 的封包不經過 netfilter，既不會被計數也不會被限速。

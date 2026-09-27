<p align="center"><img src="docs/icon.png" width="96" alt="PocketDrop icon"></p>

<h1 align="center">PocketDrop 口袋快傳</h1>

<p align="center">
手機 ↔ 電腦，同一個 Wi-Fi 或<b>一條 USB 線</b>就能直接互傳檔案和文字。<br>
不用註冊、不經過雲端、不用裝一堆東西，Android App 只有 <b>約 40 KB</b>。<br>
<a href="#english">English below</a>
</p>

<p align="center"><img src="docs/screenshot-pc.png" width="420" alt="PocketDrop Windows app"></p>

## 特色

- **雙向傳檔：** 手機 → 電腦、電腦 → 手機，資料夾也可以
- **傳文字/網址：** 收到自動複製到剪貼簿，像共用剪貼簿
- **自動找到對方：** 同一個 Wi-Fi 下打開就連上，找不到時可以手動輸入 IP
- **USB 線一插就配對：** 手機用傳輸線接電腦、開「USB 網路共用」，自動配對、不用按任何確認；配對一次後拔掉線改用 Wi-Fi 也會自動連。沒有 Wi-Fi 的地方也能傳
- **Wi-Fi 第一次連線要在電腦按「允許」：** 同一個網路裡的陌生手機不能亂傳
- **中英文介面：** 跟著系統語言自動切換
- **超輕量：** Android App 約 40 KB（純 Java、沒有任何第三方函式庫），電腦版是單一個 exe
- **方便的入口：**
  - 手機：相簿或任何 App 按「分享 → 口袋快傳」
  - 電腦：把檔案拖進視窗；也可以把 exe 的捷徑放進右鍵「傳送到」選單（Win+R 輸入 `shell:sendto`，把捷徑放進去）
- **手機還沒裝 App？** 電腦版按「手機安裝 App」會出現 QR code，手機掃一下就能從電腦直接下載安裝

## 下載

到 [Releases](../../releases) 下載：

| 檔案 | 說明 |
|---|---|
| `PocketDrop.exe` | Windows 電腦版，直接執行，不用安裝 Python |
| `PocketDrop.apk` | Android App（Android 10 以上） |

## 使用方法

1. 電腦打開 `PocketDrop.exe`。
   - 第一次 Windows 會跳出防火牆詢問，請按「**允許存取**」，不然手機連不到。要用 USB 線連的話，「**公用網路**」也要打勾（Windows 會把手機的 USB 網路當成公用網路）。
   - 這個 exe 沒有數位簽章，如果出現「Windows 已保護您的電腦」，按「其他資訊 → 仍要執行」。
2. 手機安裝 `PocketDrop.apk`。也可以按電腦版右下角的「手機安裝 App」，用手機相機掃 QR code 下載。
3. 連線，二選一：
   - **Wi-Fi：** 手機跟電腦連同一個 Wi-Fi，打開手機 App，電腦上按「允許」就連上了。
   - **USB 線：** 用傳輸線接上電腦，手機 App 按「用 USB 線連 → 打開設定」，開啟「USB 網路共用」，就會自動配對連上。

收到的檔案存在：

- **電腦：** `下載\PocketDrop`（可以改）
- **手機：** `下載/PocketDrop`

## 注意事項

- 電腦傳東西到手機時，手機 App 要開著；沒開的話會先排隊，打開 App 就會自動收下。
- 傳輸走區域網路的 HTTP，**沒有加密**，請在自己家裡或信任的 Wi-Fi 使用。
- 有些公共 Wi-Fi 會擋裝置之間互連（AP 隔離），這種時候改用 USB 線就好。
- 開著 USB 網路共用時，電腦可能會透過手機的網路上網（會用到手機流量），傳完可以關掉。
- USB 自動配對目前只支援 Windows 電腦版。
- 電腦版在 Windows 11 上測試過；macOS / Linux 可以用原始碼執行，但還沒實際測過。

## 從原始碼執行／打包

**電腦版**（Python 3.10+）：

```bash
pip install -r requirements.txt
python pc/pocketdrop.pyw
```

**Android App：** 不需要 Android Studio 或 Gradle，只要 JDK 17 和 Android SDK 命令列工具：

1. 安裝 JDK 17。
2. 安裝 [Android command-line tools](https://developer.android.com/studio#command-tools)，再用 `sdkmanager` 裝好 `"build-tools;35.0.0"` 和 `"platforms;android-35"`。
3. 執行：

```powershell
powershell -ExecutionPolicy Bypass -File build_apk.ps1 -Tools <放 jdk-17* 和 sdk 的資料夾>
```

也可以改設 `JAVA_HOME`、`ANDROID_HOME` 環境變數。第一次打包會產生自己的簽名金鑰（`android/release.keystore`），請保存好，之後更新 App 要用同一把。

## 運作方式

- **找電腦：** 手機用 UDP 廣播 `POCKETDROP?` 到 47852 埠，電腦回覆自己的名字和 HTTP 埠（47850）。
- **連線與傳輸：** 之後全部走 HTTP。第一次連線 `/api/hello` 時，電腦會跳出詢問；允許後發一把鑰匙給手機，之後每個請求都要帶。
- **USB 自動配對：** 手機開 USB 網路共用後，電腦會多一張 RNDIS / NCM 網卡。電腦發現 `/api/hello` 是從這張網卡進來的，代表手機實體接在這台電腦上，就直接發鑰匙。網卡名稱比對刻意很嚴格，USB 轉乙太網路的網卡不會被誤認。
- **電腦 → 手機：** 用 long-polling（`/api/poll`）。電腦一有新東西，手機馬上知道。
- **手機存檔：** 用 MediaStore 存到「下載」資料夾，不需要任何儲存權限。

---

<a name="english"></a>

## English

<p align="center"><img src="docs/screenshot-pc-en.png" width="420" alt="PocketDrop Windows app (English)"></p>

**PocketDrop** sends files and text between your Android phone and your PC over the same Wi-Fi, or over a single USB cable. There's no account and no cloud, and the Android app is only about 40 KB (plain Java, zero dependencies).

- Two-way file transfer, including whole folders. Text you receive is copied to the clipboard automatically.
- Auto-discovery on the local network, with a manual IP fallback.
- **Plug in a USB cable and it pairs itself.** Turn on USB tethering and the PC sees the request arrive on the phone's RNDIS/NCM adapter, so it knows the phone is physically connected and pairs it with no confirmation. After that, the phone also reconnects automatically over Wi-Fi. This works even where there's no Wi-Fi.
- Over Wi-Fi, the first time a phone connects you approve it on the PC.
- Send from anywhere: use the phone's share sheet, drag files onto the PC window, or add a shortcut to `shell:sendto` for the right-click menu.
- The PC app shows a QR code so a phone can download the APK straight from the PC.

**Download** `PocketDrop.exe` (Windows) and `PocketDrop.apk` (Android 10+) from [Releases](../../releases).
The UI switches between English and Chinese to match your system language.
Allow the Windows firewall prompt the first time the PC app starts. Tick "Public networks" too if you want to use USB, because Windows treats USB tethering as a public network.

**Security:** traffic is plain HTTP on your LAN and is not encrypted, so only use it on networks you trust.

**Build:** see the Chinese section above. You need Python with `requirements.txt` for the PC app. For the APK, run `build_apk.ps1` with JDK 17 and the Android SDK command-line tools; no Gradle or Android Studio is needed.

## License

[MIT](LICENSE)

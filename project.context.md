# ComicHub 手機版專案脈絡（project.context.md）

這份文件記錄 ComicHub 手機版（Android）的架構、資料放在哪、開發與發布的規則，以及**每一次改動的技術細節和修了什麼**。
每次改程式都要在最下面的「改版紀錄」補一筆（版本號 = 發布後的 v1.0.N）。

桌面版在 `D:\code\another\ComicHub`（Electron），它的 `project.context.md` 有各站的詳細說明；手機版的站別邏輯是從桌面版 `src/sites.js`、`fetcher.js` 移植過來的，**改一邊時想一下另一邊要不要跟著改**。

> 這個 repo 是公開的：文件、程式碼、commit 訊息裡**不能出現來源網站的名稱或網域**，
> 一律用代號 **NH / JM / WN / EH**（程式裡是 `nh` / `jm` / `wn` / `eh`）。站名、網址以 Base64 存在 `net/Model.kt` 的 `SiteConfig.DEFS`。
> 提交前用 `git grep -i` 檢查（關鍵字清單見桌面版的 project.context.md）。

---

## 1. 概要

- Android 手機 App，Kotlin + Jetpack Compose（Material3），單一模組 `app`，package `com.comichub.app`，minSdk 26。
- 跟作者其他 Android 專案同一套：AGP 9.0.1、Kotlin compose plugin 2.2.10、Compose BOM 2025.06、Coil 2.7、OkHttp 4.12、Jsoup 1.18.3。
- 發布：推到 `main` → GitHub Actions 打包簽章 APK → Release `v1.0.<run_number>`，附 `ComicHub.apk`。
  固定下載網址：`https://github.com/DLIN131/ComicHubApp/releases/latest/download/ComicHub.apk`
- App 內更新：啟動時讀 GitHub 最新 Release，有新版跳視窗 → 下載 APK → 系統安裝畫面。資產名稱要完全等於 `ComicHub.apk`。

## 2. 檔案結構（`app/src/main/java/com/comichub/app/`）

| 檔案 | 內容 |
|---|---|
| `App.kt` | Application：CookieManager 初始化、Coil（快取上限 150 MB、CBZ 圖片來源）；`Prefs`（SharedPreferences：語言、排序、閱讀模式、帳號名稱、網址覆寫…） |
| `net/Model.kt` | 共用資料格式 `Item` / `Detail` / `ReaderPage` / `TagRef` / `Chapter`、`Lang`（語言判斷與篩選）、`SiteConfig`（Base64 站名網址、網址覆寫） |
| `net/Net.kt` | OkHttp（cookie 跟 WebView 共用 `WebCookieJar`）、統一 UA、NH token（`access_token` cookie → `Authorization: User …`，401 時 `/auth/refresh`）、錯誤訊息、下載用 `bytes()`、登出清 cookie |
| `net/WebFetcher.kt` | 隱藏 WebView 抓 HTML（JM 一律走這裡；其他站被 Cloudflare 擋到時也改走這裡） |
| `net/Sites.kt` | 四站轉接層 `Nh` / `Jm` / `Wn` / `Eh`（`parseXxx` 只吃字串，可 JVM 測試）、`Query`（編號搜尋、作者、整理書名） |
| `net/Auth.kt` | 各站登入狀態（跟桌面版 `checkAuth` / `authStatus` 一樣，含「登入過期」判斷）、帳號名稱、登出 |
| `data/Store.kt` | `state.json`：紀錄、進度、合集、書櫃分組、下載紀錄（暫存檔改名 + `.bak`） |
| `data/History.kt` | 閱讀紀錄與進度（key：`site_id` / `local_檔名` / `col_id`，跟桌面版一樣） |
| `data/Library.kt` | 書櫃：掃描 CBZ/ZIP、讀 ComicInfo.xml、匯入、分組、合集 |
| `data/Downloads.kt` / `DownloadService.kt` | 下載成 CBZ（前景服務 + 通知進度） |
| `data/ZipImages.kt` | Coil 讀 CBZ 內圖片（`ZipPage` + Fetcher + Keyer）、JM 圖片重組（`Descramble`） |
| `util/Updater.kt` | App 內更新、更新紀錄（GitHub Releases） |
| `ui/Nav.kt` | 導覽堆疊 `Nav` / `Route`、分頁 `Tab`、無限捲動 `Feed`、多站搜尋 `SearchState` |
| `ui/MainActivity.kt` | 主畫面、底部分頁（首頁／收藏／紀錄／書櫃／帳號）、從檔案管理員開 CBZ、更新提示 |
| `ui/HomeScreen.kt` | 首頁、搜尋頁 |
| `ui/DetailScreen.kt` | 作品頁、「用此漫畫搜尋」面板 |
| `ui/ReaderScreen.kt` | 閱讀器（上下捲動 / 左右翻頁、縮放、章節、合集目錄、進度） |
| `ui/LibraryScreens.kt` | 收藏、紀錄、書櫃（分組、合集）、下載頁 |
| `ui/AccountScreen.kt` | 帳號與設定、登入頁 / 開啟網站（WebView）、更新視窗、更新紀錄 |
| `ui/Components.kt` | 主題顏色、封面卡片、站別 / 語言標籤、錯誤框（含「開啟驗證頁」）等共用元件 |

測試：`app/src/test/.../net/ParserTest.kt` 用真的網頁存檔測四站解析器。存檔含站名，**不放進 repo**；設環境變數 `FIXTURES` 指到存檔資料夾才會跑（沒設就跳過）。
存檔用桌面版的 fetcher 抓：寫一個 Electron 腳本 `require('D:/code/another/ComicHub/fetcher.js')`，`fetcher.init(UA)` 後 `fetcher.get(site, url)` 存檔（userData 指到暫存資料夾）。

## 3. 網路與各站要點

- **統一 UA**：OkHttp、隱藏 WebView、登入 WebView 都用「電腦版 Chrome UA（版本取自手機 WebView）」。
  原因：①網站給跟桌面版一樣的網頁格式，解析器才能共用；②Cloudflare 的通行 cookie 綁 UA，WebView 拿到的要讓 OkHttp 也能用。
  登入頁因此是電腦版排版（開了縮放）。
- **cookie**：`WebCookieJar` 讓 OkHttp 直接讀寫 Android 的 `CookieManager`，在 WebView 登入後 OkHttp 就有登入狀態。
- **JM**：HTML 被 Cloudflare 擋（一般 HTTP 回 403），一律用 `WebFetcher`：每站一個隱藏 WebView，停在 `/robots.txt`，在裡面 `fetch()` 抓 HTML 經 JS bridge 傳回；
  `fetch()` 被驗證擋下時，跟桌面版一樣在隱藏 WebView 打開那一頁、等驗證跑完、讀 `outerHTML`。25 秒還過不了 → `BlockedException`，錯誤框出現「開啟驗證頁」讓使用者手動過。
  圖片 CDN 不用驗證，OkHttp 直接抓。閱讀頁的圖被切條打亂：段數由 `aid` + 檔名的 md5 決定（`Jm.segments`），閱讀時 Coil `DescrambleTransformation`（要用原尺寸 `Size.ORIGINAL`，縮過再拼會錯位），下載時解碼重組存 JPEG。
  JM 同一個帳號同時只能在一個地方登入（在電腦版 / 瀏覽器登入會把手機踢掉）。收藏網址要帳號名稱：登入頁每秒讀「看得到的」帳號欄記下來，也可在帳號頁手動填。
- **NH**：官方 v2 API，OkHttp 直接用。圖片 `i1~4` / `t1~4` 子網域（依路徑 hash 分配，跟桌面版同一算法）。
- **WN**：一般 HTML，OkHttp 直接用；圖片在別的網域的 CDN，不需要 Referer。不存在的編號也回 200 → 沒封面就當找不到。
- **EH**：作品編號 `gid_token`；列表用 `next=` 游標翻頁；閱讀頁要再開每一頁拿圖片網址（`ReaderPage.resolve`，重試帶 `nl=` 換伺服器）；登入在論壇子網域。
- 2026-10-26 實測：JM HTML 403（Cloudflare），NH API / WN / EH 一般 HTTP 都 200；四站圖片 CDN 都不需要 Referer（OkHttp 還是會依網址帶該站 Referer）。

## 4. 資料放在哪

| 位置 | 內容 |
|---|---|
| `filesDir/state.json`（+ `.bak`） | 紀錄、閱讀進度、合集、書櫃分組、下載紀錄 |
| SharedPreferences `prefs` | 語言、首頁分頁、排序、搜尋站、閱讀模式、略過的版本、上次版本、網址覆寫、各站帳號名稱（不存密碼） |
| `getExternalFilesDir()/downloads`（Android/data/com.comichub.app/files/downloads） | 下載的 CBZ（書櫃）。解除安裝會一起刪除 |
| `cacheDir/images` | 圖片快取（上限 150 MB） |
| WebView `CookieManager` | 各站登入 cookie、Cloudflare 通行 cookie |

## 5. 開發、測試、發布

- 建置：`./gradlew assembleRelease`（本機用 `keystore/keystore.properties` 簽章；`keystore/` 不進 git）。產出 `app/build/outputs/apk/release/app-release.apk`，同時複製到 `dist/ComicHub.apk`（不進 git）。
- 測試：`FIXTURES=<存檔資料夾> ./gradlew :app:testDebugUnitTest`。使用者不要用模擬器測（電腦太慢），沒有實機可接時只能靠單元測試 + 仔細檢查。
- 發布：commit 訊息 = 更新說明（繁體中文、給使用者看的條列，結尾 `Co-Authored-By` 行），推到 `main` 自動發布；`*.md` 的改動不觸發發布。
  CI 需要 GitHub Secrets：`KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`（值在本機 `keystore/`）。

---

## 6. 改版紀錄

### v1.0.1（2026-10-26）第一版
- 從桌面版移植：四站首頁 / 搜尋（交錯、站別篩選、編號與網址搜尋）/ 作品頁 / 閱讀器 / 語言篩選 / 下載 CBZ（檔名、ComicInfo 跟桌面版一樣）/ 書櫃（分組、作者搜尋、用此漫畫搜尋、分享、刪除、匯入）/ 合集 / 閱讀進度 / 紀錄 / 各站登入與收藏（NH 可在作品頁加入收藏）/ 網址設定 / App 內更新與更新紀錄。
- 手機版特有：上下捲動與左右翻頁兩種閱讀模式、雙指縮放、多話作品看完接下一話、下載用前景服務、從檔案管理員開 CBZ。
- 四站解析器用真實網頁存檔做 JVM 單元測試，全部通過（NH 25 筆、JM 80 筆 / 136 頁閱讀、WN 20 筆 / 62 頁、EH 25 筆 + 圖片頁解析）。
- 實機還沒測過。
- 發布：建立公開 repo `DLIN131/ComicHubApp`、設好 4 個簽章 Secrets，CI 第一次就成功；Release APK 的簽章憑證 SHA-256 `65872f67…172613` 跟本機打包的一致（App 內更新才能覆蓋安裝）。

### v1.0.2（2026-10-26）修正版本號顯示
- 修：「帳號」頁把正式版 v1.0.1 顯示成「開發版」。原因：用 versionCode ≤ 1 判斷開發版，但 CI 第一版的 versionCode 剛好是 1。
- 改成看版本名稱：CI 打包是 `1.0.<run_number>`，本機打包（沒有 BUILD_NUMBER）是 `1.0.dev` → 顯示「開發版」（`app/build.gradle.kts`、`Updater.currentVersionName`）。

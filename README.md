# Sector RRG

雙動能 ETF 輪動 app。資金留喺動能最強嘅資產，跌穿趨勢就去現金。

16 隻資產：11 板塊 ETF + TLT + IEF + GLD + EFA + EEM。冇加密資產 —— 10–20% 止損同 BTC 嘅波動本質唔夾。

- **今日**（預設頁）— 九成時間只會見到「今日無動作」。有止損穿破或者總閘關閉先至變色。
- **輪動** — 16 隻資產按 13612W 動能分數排，顯示每隻過唔過得到入選閘。每行有「喺富途開」。
- **RRG** — 保留做前望視角：睇邊個資產喺 Improving 準備接棒。排名唔喺呢度做。

## 模型

| | |
|---|---|
| 資產池 | 11 板塊 ETF + TLT + IEF + GLD + EFA + EEM |
| 排名 | 13612W = (12×1M + 4×3M + 2×6M + 1×12M) ÷ 4 總報酬，用復權價 |
| 入選閘 | SPY 企 200 日線之上 **且** 該資產企自己 200 日線之上 **且** 動能為正 |
| 持倉 | 1 格 |
| 換入 | 月底 |
| 換出 | 每日檢查：跌穿 200 日線或者觸及追蹤止損 → 即日去現金，等下個月底先再部署 |
| 追蹤止損 | clamp(10 × ATR20/價, 10%, 20%)，由近期高位往下計 |
| 權重 | 波動率目標 20% 年化：倉位 = min(100%, 0.20 ÷ 資產年化波動)，差額留現金 |

### 點解排名唔用 RRG 嘅 RS-Ratio

RS-Ratio 係對每隻標的**自己過去一年**做 z 標準化，答嘅係「相對自己舊年強唔強」。
標準化正正把跨資產嘅量級差異除乾淨 —— BTC 跑贏 SPY 40% 同 XLK 跑贏 5% 可以得出同一個 z 值。
排名要保留量級，所以行原始總報酬。RRG 留返做輪動階段嘅視覺層。

### 點解用波動率目標唔用反波動率

得一格嘅時候，榜首係邊個邊個就係全副身家。反波動率權重只喺多格之間分配，一格之下完全冇作用。
波動率目標先至令一格之下嘅風險可比：XLK（波動 21%）攞足 95%，XLE（26%）攞 77%，其餘留現金。
封頂 100%，唔加槓桿。

配合追蹤止損，止損觸發時嘅組合損失落喺大約 10–18%。

### 入場同離場唔同頻率

入場遲少少，損失嘅係機會；離場遲少少，損失嘅係本金。兩者唔對稱，所以唔共用一個時鐘。
月底換入、每日檢查離場。被止損之後唔即刻補倉，等下個月底 —— 否則會變成高頻換馬，
把摩擦成本同雜訊全部請返入嚟。

### 數據體檢

真正嘅風險唔係報價唔準，係源靜靜雞停止更新、或者未復權嘅拆股偽裝成暴跌污染動能分數。
四項檢查：最後一根 K 線日期、單日 |報酬| > 35%、成交量長期為零、對持倉同頭三名向第二個源抽驗最新收市價。
任何一項唔過，該資產標黃並且**唔准入選**。

一律用 Yahoo 嘅 `adjclose` 並按比例縮放 OHLC。未復權嘅話派息完全唔計入報酬，
TLT / IEF 呢類靠派息嘅資產動能會被系統性低估。

---

## 出 APK：唔使喺自己部機裝任何嘢

1. 開一個 GitHub 帳號（免費），撳 **New repository**，名可以叫 `sector-rrg`，設 **Private** 都得。
2. 解壓個 zip。喺個空 repo 頁面撳 **uploading an existing file**，把解壓出嚟嘅**全部項目**拖入去，撳 **Commit changes**。

   `.github` 同 `.gitignore` 係隱藏檔，預設睇唔到，一定要先開顯示：
   Mac 喺 Finder 撳 `Cmd + Shift + .`；Windows 喺檔案總管 **檢視 → 隱藏的項目** 打剔。
   冇咗 `.github/workflows/build.yml` 就唔會自動編譯。

   萬一 `.github` 點都上傳唔到：撳 **Add file → Create new file**，
   檔名直接打 `.github/workflows/build.yml`（打斜線佢會自動開資料夾），
   再把 zip 入面同名檔案嘅內容貼落去。
3. 上傳完 GitHub Actions 自動開始編譯。撳頂部 **Actions** 分頁睇進度，大約 5–8 分鐘。
4. 綠色剔之後，去 repo 右邊 **Releases** → `Sector RRG (latest build)` → 下載 **sector-rrg.apk**。
   呢條連結喺手機瀏覽器直接開就下載到，唔使解壓。
5. 手機安裝：開個 APK，Android 會問「允許呢個來源安裝應用程式」，開咗佢就裝到。

### 第一次建構之後做多一步（建議）

第一次 CI 會自動生成簽名 key。去 Actions 嗰次執行嘅 **Artifacts** 下載 `release-keystore-KEEP-THIS`，
解壓得到 `release.jks`，上傳返去 repo 嘅 `keystore/release.jks`。

唔做都裝到，但每次建構嘅簽名會唔同，下次更新要先解除安裝舊版。做咗就可以直接覆蓋安裝。

---

## 想喺自己部電腦編譯

要 Android Studio（佢會自動補 `gradle/wrapper/gradle-wrapper.jar`，呢個係 binary，冇放喺 repo 入面）。

```bash
gradle wrapper --gradle-version 8.11.1   # 如果唔用 Android Studio
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/release.jks \
  -alias sectorrrg -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass sectorrrg -keypass sectorrrg \
  -dname "CN=SectorRRG, O=Personal, C=HK"

./gradlew :core:test          # 先驗數學
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 架構

| 模組 | 內容 |
|---|---|
| `:core` | 純 Kotlin。RRG 數學、EMA／滾動 z-score／ATR、FVG 擇時、龍頭評分。有單元測試，冇 Android 依賴。 |
| `:data` | 行情源（Yahoo → Stooq 後備）、檔案快取、兩級收窄編排、訊號 diff。 |
| `:app` | Compose UI、WorkManager 每日刷新。 |

### 曆法對齊

全部標的一律拉**日線**，週線由 `toWeekly()` 自己按 ISO 週（星期一起）合成，唔用行情源嘅週線。

原因：美股遇著假期嗰週會由星期二開始，行情源嘅週線錨定會跟住郁。`align()` 係用 epochDay 硬 join，
對唔上嘅根會靜靜雞被丟走，而且唔會報錯。自己合成就一把尺到底。
RRG 參數全部仍然係週（fast 10 / slow 30 / zWindow 52），一個都冇改。

順帶好處：成分股日線拉一次就夠，週線自己合成，第二層請求量減半。

### 非板塊資產點擺

TLT / IEF / GLD / EFA / EEM 照樣以 SPY 為基準畫喺同一張 RRG，但喺板塊頁獨立一組「宏觀」，
唔參與板塊強度排序。佢哋照樣入輪動池排名。

**冇加密資產。** BTC 喺升浪入面正常回調 25–35%，10–20% 止損唔係保護，係保證俾佢震走，
而且震走之後要等月底先再入場，正正錯過反彈。硬砌特例只會令規則變複雜。

### 冇個股，係刻意嘅

個股候選池要靠一份寫死嘅成份股名單，而 S&P Select Sector 指數每季再平衡，
一份過期名單唔會俾錯答案，但會**靜靜雞漏咗新晉嘅強勢股**，而你唔會察覺。
為咗一個純瀏覽嘅頁面換一季一次嘅維護責任，唔抵。

`:core` 仲保留住 `Fvg.kt`（FVG／EMA 回踩擇時）同 `Leaders.kt`（個股橫截面評分），
代碼同測試都喺度，將來想開返個股腿唔使重寫。

---

## 已知限制

- **Yahoo 嘅 chart endpoint 係非公開 API**，隨時可以改或者封。掛咗會自動退去 Stooq，兩邊都掛就用舊快取頂住。`PriceSource` 係 interface，換源只需要寫一個新 class。
- **`holdings.json` 唔係官方名單**，係憑印象整理嘅大型成分股。用之前去 sectorspdr.com 下載各 ETF 嘅 holdings CSV 對一次，之後每季更新。權重排序唔準只影響候選池邊緣，唔影響評分本身。
- **免費行情源冇財報日曆**，龍頭榜冇辦法自動剔除臨近業績嘅股票。
- RRG 數值**唔會**同 StockCharts 逐點對得上——官方 JdK 公式未公開。象限判斷同順時針旋轉特性應該一致，數值尺度唔同。
- `targetSdk = 35`。Android 16（API 36）裝同行都冇問題，只係唔會觸發 API 36 專屬嘅行為變更。想要嘅話改 `app/build.gradle.kts` 兩行就得。

## 驗數

裝完第一件事係驗數字，唔係睇 UI。開 StockCharts 免費 RRG，基準設 SPY，載入 11 隻 SPDR ETF，
同 app 嘅板塊清單對一次象限。有板塊落錯象限，問題通常喺 `RrgParams` 嘅 `fast` / `slow` / `zWindow`。

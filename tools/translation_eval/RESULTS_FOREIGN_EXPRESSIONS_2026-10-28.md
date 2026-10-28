# 一般提示詞：刻意外語表達保留

日期：2026-10-28（Asia/Hong_Kong）。範圍：GENERAL NON-SAKURA，日文輸入、簡中／繁中／英文輸出。

> 後續已依使用者澄清改採語境取捨政策，見 [新版比較](RESULTS_CONTEXTUAL_FOREIGN_2026-10-29.md)。本檔保留較強保留政策的歷史實測；下列「採用版」指當時版本，保留率不能作為目前翻譯品質評分。

## 已採用的修改

- `PromptBuilder.kt` 的六組一般基礎提示詞共用外語例外：保留原有外語拼寫與重音符號，將明確的片假名外語還原為慣用拼寫。
- 外語例外明確優先於目標語言要求；用 `メルシー -> Merci`、`アディオス -> Adiós` 兩個短例子避免模型一律改寫成中文。普通日文／借詞仍正常翻譯，不因角色國籍添加外語；不明確時翻譯意思。
- 中、英文 ruby 指令都保留正文／小字的角色，兩者皆可包含刻意外語。原文沒有 ruby 不新增；兩者譯後相同仍沿用去重規則。
- 純文字輸出指令明確允許譯文中保留外語；JSON 格式與數量契約沒有更改。
- 中文主版本為 `jp-cn-fgo-target-v99-foreign-expressions`；英文為 `jp-en-fgo-target-v7-foreign-expressions`。既有主版本已參與每種一般請求的快取鍵，無須修改共用快取格式或字幕子版本。
- 沒有修改 Translator、安全檢查、重試邏輯、詞庫、角色卡、OCR、渲染或 Sakura 檔案。

## 測試方法與用量

基準為 Git `797a506` 的正式提示詞。使用 [foreign_language_cases.json](foreign_language_cases.json) 的 12 個案例 × 3 個目標；由既有 Kotlin 匯出器呼叫正式組裝函式，固定準備好的 glossary／speaker 資料，不模擬 DB 查找。

呼叫官方 DeepSeek `/v1/chat/completions`，沿用 App 模型名稱 `deepseek-v4-flash`，實際回傳名稱為 `deepseek-flash`。參數：`temperature=1.0`、`max_tokens=256`、`thinking.type=disabled`。收集器串行執行，相同案例的 A/B 相鄰且次序隨機。

| 階段 | 請求數 | 用途 |
|---|---:|---|
| 初稿 A/B | 144 | 只加入抽象保留指令；中文仍常改寫外語，未採用此稿 |
| 修正版小批 | 12 | 確認明確例外及短例子能保留 Merci 與既有法／西文拼寫 |
| 最終 A/B | 144 | 12 案例 × 3 目標 × A/B × 2 次；下表只使用這一輪 |
| 合計 | 300 | HTTP 失敗 0；沒有自動追加付費呼叫 |

金鑰由隱藏輸入進入收集器記憶體；沒有保存到程式碼、命令列參數、匯出或報告。原始回應、完整 prompt、盲化檢視及計算檔放在 Git 忽略的 `app/build/reports/translation-eval/foreign-expressions-20261028/`。

## 最終結果

9 個正向案例檢查指定外語拼寫、ruby 角色、詞庫譯名及選項結構；每種目標各 18 筆。另有 3 個反例 × 3 目標 × 2 次，檢查是否把普通借詞、角色國籍或不完整聲音擅自外語化。

先以隱藏 variant 的資料檢視全部回應，記錄明確詞形／角色／結構要求，再揭開 A/B 標籤。這是本次助手的專項判讀與可重算的規則檢查，不是獨立語言專家盲評，也不是全面翻譯品質分數。JSON 結構另經正式 Kotlin `GeneralTranslationResponseContract` 驗證。

| 正向案例：外語與結構要求 | 舊版 | 採用版 |
|---|---:|---:|
| 簡中 | 4/18 | 18/18 |
| 繁中 | 5/18 | 18/18 |
| 英文 | 11/18 | 17/18 |
| 合計 | 20/54（37.0%） | 53/54（98.1%） |

- 反例「沒有擅自加入外語」：兩版均為 18/18。這一數字不代表反例的全部語意皆正確，問題列於下節。
- 正式輸出契約：舊版 71/72，採用版 72/72。舊版其中一次英文選項返回三個分開的陣列。
- `Merci`、`Adiós`、`Bonjour`、`Guten Tag` 可保留於中文／英文譯文；既有 `Très bien, señorita` 的字母及重音符號可保留。

| 首次後端回應／prompt 大小 | 舊版 | 採用版 |
|---|---:|---:|
| 回應時間中位數 | 895 ms | 940 ms |
| 回應時間樣本 p95 | 1,106 ms | 1,077 ms |
| 平均輸入 tokens | 322.86 | 423.00 |
| 平均輸出 tokens | 7.90 | 8.42 |
| 平均供應商 prefix-cache 命中 tokens | 141.64 | 222.19 |

使用者訊息完全相同；system prompt 增加 460–600 字符，取決於目標、純文字／JSON 契約及 ruby 是否啟用。平均輸入增加約 100 tokens；兩個短例子是針對初稿實測失敗而保留的有效提示，不宣稱 prompt 變短或雜訊下降。

這是單次 HTTP 回應時間，包含網路與供應商條件；供應商 prefix cache 不同，部分請求期間電腦也在本機建置。小樣本的 p95 不穩定，不能據此宣稱更快、更慢具有統計顯著性，亦不是 App 端到端速度。

## 保留的失敗與限制

- 採用版一次英文 `Merci〈ありがとう〉、マスター。` 返回 `Merci, Master.`，漏了原有 ruby；另一次正確保留 `Merci〈thank you〉, Master.`。沒有新增付費補測或把失敗排除。
- 不完整聲音案例中，採用版兩次英文回應擅自加入 `he`，一次繁中原始回應保留了 `ブ……ボン……`。它們沒有猜出完整外語，但仍是語意／未翻譯問題。此收集器沒有執行 App 的安全檢查、清理及重試，不能推定 App 最終會照原樣顯示，也不能假定重試一定修好。
- 舊版普通借詞案例兩次把「電腦」具體化為 `laptop`；一次不完整聲音案例變成「我……我……」。專項外語通過率不涵蓋這些整體語意問題。
- 正向案例特別針對本次功能，98.1% 不能套用到任意 FGO 台詞、其他模型或所有語言。德文是兩個提示例子以外的檢查，但仍只有小量樣本。
- 付費樣本只涵蓋一般純文字與選項陣列。戰鬥、截圖、對話字幕、重試及整幕 JSON 的組裝／契約由離線測試覆蓋；沒有操作手機或聲稱完成實機渲染驗證。

## 本機驗證與重算

- App JVM：1,262 tests，0 failures/errors；其中新增 6 個一般外語政策測試。
- Python 評測工具：18 tests 通過，完全離線。
- `assembleDebug` 成功；APK 為 `app/build/outputs/apk/debug/app-debug.apk`。
- 已同步既有版本預期、ruby 指令大小寫檢查及戰鬥 prompt 大小上限；戰鬥 prompt 仍不混入姓名／前文／ruby 規則。

本機證據：`baseline.json`、`candidate-v2.json`、`responses-v2.json`、`blind-review-v2.json`、`policy-review-v2.json`、`analysis-v2.json` 及 `analyze_foreign.py` 均位於上述忽略目錄。正式回應契約驗證為 `app/build/reports/translation-eval/validation.json`，含原始回應檔 SHA-256；`validated-v2.json` 可核對最終程式匯出與測試候選一致。`policy-review-v2.json` 是外語專項布林判讀，不是既有四維品質評分格式。

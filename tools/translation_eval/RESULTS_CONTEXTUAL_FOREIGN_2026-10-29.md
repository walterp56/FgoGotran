# 一般提示詞：外語依語境取捨

整理完成：2026-10-29，Asia/Hong_Kong；測試自 2026-10-28 開始。範圍：GENERAL NON-SAKURA，簡中／繁中／英文。

## 現行行為

最高原則是語境、意思忠實、角色語氣與自然可讀性。外語保留與意譯都是選項；一般問候不因保留更多外語而得分。當語言差異、詞語本身或雙關是句子的重點時，必須保住可辨識的關鍵字。

例如 `メルシー、マスター。` 可保留 Merci，也可自然譯成「謝謝，御主」或 Thank you, Master；`「メルシー」はフランス語で「ありがとう」という意味だ。` 則要保住被解釋的詞，避免變成「謝謝是法文」這類失真。普通日文不因角色國籍而新增外語，普通借詞依正常規則翻譯。

`PromptBuilder.kt` 的六組一般基礎提示詞共用以下規則：

```text
For foreign expressions, choose retention or natural target-language translation by context, voice and readability. Keep or restore the foreign word itself when discussing its language, word forms or wordplay. When retaining, restore clear katakana to established foreign spelling and preserve diacritics; if unsure, translate the meaning. メルシー is French Merci, not English mercy. Never invent foreign wording.
```

已移除強制保留的「即使目標是中文／英文也保留」、固定還原範例，以及純文字輸出指令重複的保留提醒。Merci 的短例子只排除 mercy 的錯義，不強迫選擇外語形式。ruby 延續正文／小字各自的角色與資訊；若兩者自然譯成同一詞，仍可使用既有去重規則。

中文主版本為 `jp-cn-fgo-target-v100-contextual-foreign`；英文為 `jp-en-fgo-target-v8-contextual-foreign`。現有主版本已參與一般快取鍵，會避開較強保留政策的舊譯文。Sakura、Translator、安全檢查、重試、詞庫、角色卡、OCR 和渲染沒有更改。

## 測試設計

使用 [contextual_foreign_cases.json](contextual_foreign_cases.json) 的 12 個人工案例 × 3 個目標 × 每組 2 次。它們包含可保留或意譯的一般對話、必須保住詞語身分的解釋／對比、普通借詞、角色國籍、術語表、ruby、選項及不完整片假名。

基準是本次修改前的強保留政策（中文 v99／英文 v7），不是更早沒有通用外語規則的版本。正式 Kotlin 組裝器匯出兩個版本的 system/user 訊息；每組 user 訊息相同。準備好的 glossary／speaker 資料不是即時 App DB 檢索。

使用 DeepSeek 官方 `/v1/chat/completions`，沿用 App 名稱 `deepseek-v4-flash`，回傳名稱 `deepseek-flash`；`temperature=1.0`、`max_tokens=256`、`thinking.type=disabled`。

| 階段 | 實際請求 | 說明 |
|---|---:|---|
| 首輪 A/B | 144 | 強保留版與第一個語境版，相鄰隨機 A/B；發現 mercy 錯義及詞語解釋問題 |
| 第二個候選 | 72 | 用短例子消歧；發現一筆中文照用例子中的 Thank you |
| 最終候選 | 72 | 例子改為辨識法文來源，並明確要求自然目標語言意譯 |
| 合計 | 288 | HTTP 錯誤 0；沒有超出本輪最後宣告上限 |

最終比較使用首輪強保留版的 72 筆，加上最後候選的 72 筆。兩個未採用的語境候選不混入最終指標。最終兩臂分段採集，時間未相鄰；不能用此資料做嚴格的因果延遲比較。

## 結果：翻譯品質優先

在隱藏 variant 的檢視中逐組閱讀全部回應，記錄語境／語意／結構問題，再揭開版本標籤。合理保留與合理意譯同樣可通過；沒有以保留比例作分數。這是本次助手依人工案例的判讀，並非獨立語言專家盲評或全面翻譯品質百分比。

| 首次回應符合案例要求 | 強保留版 | 最終語境版 |
|---|---:|---:|
| 簡中 | 23/24 | 24/24 |
| 繁中 | 24/24 | 23/24 |
| 英文 | 23/24 | 22/24 |
| 合計 | 70/72 | 69/72 |
| 詞語解釋／對比類 | 18/18 | 17/18 |
| 正式輸出契約 | 72/72 | 71/72 |

結論：新指令符合「依語境取捨」的產品要求；這批小樣本沒有證明整體翻譯品質優於強保留版，也不足以判定差一筆具有穩定意義。不能沿用歷史強保留專項的 20/54 → 53/54 作為本次品質提升證據。

最終語境版保留以下三筆未通過樣本：

- 英文一次把 `パソコン` 具體化為 laptop，原文沒有筆電資訊。
- 繁中一次在法文詞語解釋中留下 `メルシー` 假名，未還原清楚的外語拼寫。
- 英文一次選項輸出三個分開的陣列，而非一個三項陣列。

強保留版有一次把電腦具體化為 laptop，一次不完整聲音保留了未受保護的日文假名。兩版皆有正確保持原 ruby 資訊的樣本；普通問候的自然意譯不列為失敗。部分基準繁中回應使用簡體字，App 既有目標轉換未在本收集器執行，另列觀察，不當成語意失真。英文不完整聲音中的 I 被視為可接受的語法主語選擇，未混同新增第三人稱身分。

## 回應時間與 prompt 雜訊

| 指標 | 強保留版 | 最終語境版 |
|---|---:|---:|
| 單次 HTTP 中位數 | 797 ms | 774 ms |
| 樣本 p95 | 1,039 ms | 1,079 ms |
| 平均輸入 tokens | 420.89 | 403.64 |
| 平均輸出 tokens | 11.08 | 11.32 |
| 平均供應商 prefix-cache 命中 tokens | 223.94 | 184.82 |

新版 system prompt 比強保留版少 49–128 字符，user 訊息不變；平均輸入少約 17 tokens（約 4.1%）。刪除的是重複強制保留提醒；留下的一個短例子有實測消除錯義的用途。長度與 token 只代表大小，不能單獨當作 prompt 雜訊分數。

中位數略低、p95 略高，且兩臂採集時段、供應商 prefix cache 不同；此資料不證明新版穩定更快或更慢。它只量首次 HTTP 回應，不是 App 的 OCR、排程、清理、重試、快取或渲染端到端延遲。

## 本機驗證與證據

- 1,262 項 App JVM 測試通過，0 failures/errors；外語政策測試驗證三個目標及一般／戰鬥／截圖／對話字幕 profile、ruby、重試、來源及回應契約。
- 評測工具 18 項 Python 離線測試通過。
- `assembleDebug` 成功，APK：`app/build/outputs/apk/debug/app-debug.apk`。沒有操作手機或宣稱完成實機驗證。
- 正式 Kotlin 輸出契約驗證覆蓋最終比較 144 筆；純文字只檢查非空，不能由格式通過推論意思正確。

本機完整資料位於 Git 忽略的 `app/build/reports/translation-eval/contextual-foreign-20261028/`：`strong.json`、`contextual-release.json`、`release-comparison-responses.json`、`release-blind-review.json`、`release-context-review.json`、`release-analysis.json`、`analyze_contextual.py` 及 `release-validated.json`。原始未採用候選也保留，沒有覆寫。

`app/build/reports/translation-eval/validation.json` 帶有比較回應檔的 SHA-256。最終候選匯出的來源 SHA-256 及訊息會與目前程式／驗證匯出比對。金鑰只經隱藏輸入進入收集程序記憶體，不寫入程式、匯出或報告。

這批資料只覆蓋一個後端、人工案例與首次回應。App 的安全檢查／重試可能攔下部分問題，但本測試沒有模擬它們，不能假定一定修好。更廣泛的 FGO 品質結論需要更多真實場景與其他正式後端資料。

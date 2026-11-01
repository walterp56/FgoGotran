# 一般 FGO 翻譯 prompt：Atlas 配對評測（2026-11-01）

**決定：保留目前正式 prompt，不採用這次候選改寫。** 候選在調整集略好，但未延續至保留集，並出現新的英文慣用語誤譯。這輪速度較快，仍不足以抵銷品質問題。這是一次完成的候選篩選，不代表目前翻譯已沒有問題。

Sakura prompt／模板／版本、OCR、render、詞庫、角色卡及 AWS 均未修改。`PromptBuilder.kt` 與測試開始前的原始檔逐位元相同，沒有套用候選，也不需要更換其快取版本。

## 這次做了甚麼

- 讀取目前一般 prompt、正式請求組裝、條件卡、詞庫、角色 context、前文、response contract、重試及供應商參數。
- 下載 6 段劇本的 JP／CN／NA／TW，共 24 份原始檔；按台詞內容、說話者、分支及鄰近畫面人工核對對應，沒有按行數硬配。
- 12 個畫面作調整集、另 12 個畫面作保留集，兩組 script ID 完全分開。涵蓋未知人物、瑪修、戈爾德魯夫、高揚斯卡婭、敘述、玩家選項、ruby、註點及日文慣用語。
- 調整集：12 × 3 目標 × A/B × 2 次 = **144 次**；保留集：12 × 3 × A/B × 1 次 = **72 次**。共 **216 次**，全部 HTTP 200，沒有 `finish_reason=length`。沒有執行額外付費評審或自動重試。
- 用隨機交錯、相鄰配對的次序送出 A/B；使用 App 的一般單句／選項組裝函式及最終 emphasis wire 轉換。官方目標譯文完全放在評分 sidecar，沒有送入模型。

## 來源與對齊

| 用途 | Script ID | War / quest / phase |
| --- | --- | --- |
| 調整 | `0100000011` | 100 / 1000000 / 1 |
| 調整 | `0300010210` | 301 / 3000102 / 1 |
| 調整 | `0300030010` | 303 / 3000300 / 1 |
| 保留 | `0100010110` | 101 / 1000101 / 1 |
| 保留 | `0300010310` | 301 / 3000103 / 1 |
| 保留 | `0300030110` | 303 / 3000301 / 1 |

每個 ID 均另讀 JP 與 NA metadata，確認 quest／phase 相符；CN、TW 原始內容亦逐項核對。這是 script 分離，**不是章節完全分離**，同一 Lostbelt 中仍有不同 script。

可核對的起點：

- [Atlas API schema](https://api.atlasacademy.io/openapi.json)
- [JP 序章 metadata](https://api.atlasacademy.io/nice/JP/script/0100000011)
- [JP 慣用語案例 script](https://static.atlasacademy.io/JP/Script/03/0300010310.txt)／[NA 對應 script](https://static.atlasacademy.io/NA/Script/03/0300010310.txt)
- [JP 保留集 ruby script](https://static.atlasacademy.io/JP/Script/03/0300030110.txt)／[CN](https://static.atlasacademy.io/CN/Script/03/0300030110.txt)／[TW](https://static.atlasacademy.io/TW/Script/03/0300030110.txt)

原始檔的 URL、下載 UTC 時間、SHA-256、台詞 locator 和區域對應保存在本機 manifest。遊戲原始命令轉為目前 App 的 `base〈ruby〉`、emphasis encoding 和換行；不揭露原畫面未知人物的真名。官方在地化是參考，不是逐字答案。

## 實際評測設定與邊界

請求模型沿用 App 程式預設 `deepseek-v4-flash`，`temperature=1.0`，停用思考；單句 cap 256，選項 cap 384。伺服器所有回應的 model 欄位為 `deepseek-flash`。DeepSeek 的[當日官方文件](https://api-docs.deepseek.com/quick_start/pricing/)說明 legacy 名稱仍接受、由目前 Flash 模型服務；這不是測到舊版 V4 權重的證明。

固定現有術語 TSV 的命中條目，繁中使用 Android ICU `Hans-Hant` 與現有 general overrides；沒有從官方參考譯文新增詞條。角色提示使用現有 general／English 欄位。此處是準備好的詞庫輸入，**不是 live DB、完整角色別名檢索或全部 RAG 的驗收**。

為隔離本次核心句子的影響，兩版都關閉前文參考，使用可見日文身份。這是 App 可用的設定，但不代表預設兩幕 context 的完整遊玩流程。省略主詞的案例若缺乏上下文，官方譯文不能被當成模型唯一可知的答案；評語另標出這個限制。後續應另測各版本自行產生前文的連續場景。

這輪量測首次非串流 HTTP 回應，包含請求與傳輸時間；沒有量測 TTFT、OCR、render、App cache 或正式整個修復鏈。因此速度數字不是遊戲中的端到端等待時間，也不能把原始首答缺陷當成 App 經修復後一定仍會顯示的錯誤。

## 測試候選的精確文字

只替換 GENERAL 核心的風格句，其他條件卡、角色、術語、輸出契約及外語取捨政策保持相同。

中文原句：

```text
Use neutral modern standard written Chinese; keep register and voice only when the Japanese carries them. Use natural Chinese order, not Japanese syntax.
```

中文候選：

```text
Use natural modern standard written Chinese. Render idioms and indirect replies by their contextual meaning, preserving source-supported register and voice; avoid Japanese syntax.
```

英文原句：

```text
Use neutral modern American English; keep register and voice only when the Japanese carries it. Write natural English, not Japanese word order.
```

英文候選：

```text
Use natural modern American English. Render idioms and indirect replies by their contextual meaning, preserving source-supported register and voice; avoid Japanese syntax.
```

假設是讓間接回應／慣用語更自然，沒有要求犧牲原意或強制保留外語。候選只在離線匯出中作一次有檢查的替換；沒有部署到正式 prompt。候選不影響 battle／crop 的獨立核心，也沒有傳入 Sakura。

## 翻譯品質

每份輸出隱藏版本／模型標籤後，逐項依 JP、區域參考及固定 App 規則評分：meaning、terminology、voice、naturalness 各 0–4。0–10 摘要採 `2.5 × (0.55 meaning + 0.20 terminology + 0.15 voice + 0.10 naturalness)`。這是明示的審閱尺度，不是客觀正確率。

同一助手負責候選設計及版本隱藏審閱，**不是獨立雙語人工盲評**。不能因為隱藏標籤就聲稱消除了作者偏差。官方沒有保留的 honorific、相同含義合併的 ruby、忠實的其他措辭，不會只因不同於官方字句扣分。繁中原始字形混用按 App 最終 ICU 轉換後評估；原始回覆另存可查。

| 資料組 / 目標 | 目前版 | 候選版 | 差值 |
| --- | ---: | ---: | ---: |
| 調整集，全部 | 9.43 | 9.54 | +0.11 |
| 保留集，全部 | **9.34** | 9.31 | -0.04 |
| 保留集，簡中 | 9.49 | 9.47 | -0.02 |
| 保留集，繁中 | 9.07 | 9.34 | +0.27 |
| 保留集，英文 | **9.47** | 9.10 | -0.36 |

調整集 72 個配對：候選 12 勝、56 同分、4 負。保留集 36 個配對：7 勝、24 同分、5 負；敗例嚴重程度不同，勝場多不等於總體較好。調整集首答重大問題目前版 1、候選 0；保留集各 3，但候選新增了英文慣用語重大誤譯。所有 JSON 選項及非空／annotation body 契約均通過；結構通過不等於語意正確。

例一，高揚斯卡婭的 `自転車操業ライフ`：這是艱難周轉、勉強維生的財務慣用語，並非真的騎自行車。候選產生 `bicycle-operating grind`，比目前版的 `keep the wheels spinning` 更接近錯誤字面翻譯。兩版的 `Yaga land` 也不夠準確，沒有因保留目前版就把它評為完美。

例二，戈爾德魯夫斥責危機管理的中文，候選修好了目前版不自然的「你這傢伙們」。這是可見的改善，但不能抵銷其他句子的退步。

例三，`わたしたちどちらかに固定されている`：兩版中文仍會把「固定到我們其中一人」錯解成「我們被固定」，候選甚至加入來源未明示的「你」。缺少前文無法唯一恢復省略主詞，卻不會使這個動作方向反轉合理。

此外，唯一註點畫面的 6 份首答中，目前版保留標記 5/6，候選 3/6。樣本極小，不能推論整體退步率，但沒有理由在這個候選上忽略裝飾資訊的流失。

## 回應時間與 prompt noise

| 組別 | 目前版 p50 / p95 | 候選 p50 / p95 | 每版樣本 |
| --- | ---: | ---: | ---: |
| 調整集首次 HTTP 回應 | 888 / 1086 ms | 847 / 1068 ms | 72 |
| 保留集首次 HTTP 回應 | 898 / 1120 ms | 820 / 1036 ms | 36 |

保留集的配對延遲差中位數約 -99 ms。這輪候選較快，但小樣本、網路／provider cache／輸出長度會影響結果；沒有證明是這句 prompt 造成穩定加速。每目標保留集只有 12 份，p95 只能作探索性描述。

調整集平均輸入 token：目前版 410.36、候選 411.36；保留集 430.14、431.14，均是 provider 回報。每則完整訊息的字元數中文增加 26、英文增加 28。候選新增的是有明確目的的語境指令，不直接算雜訊；但也沒有可證明的雜訊減少。保留有用 safeguards，不能靠刪字冒稱改善。

## 採用條件與結果

在審閱結果前記錄的條件包括：品質摘要至少 +0.10、不得新增重大語意／契約失敗、任何目標不得退步超過 0.10；品質成立後才考慮延遲與雜訊。小樣本同分不當作統計等價。

候選未通過保留集條件：總分沒有改善、英文退步超過門檻，並出現新增重大敗例。因此保留目前正式版本，沒有為了交付改動而硬套用候選。後續若要改善，應先分開處理語境不足、慣用語、標記保留與字形政策，不把每個失敗都堆成通用 prompt 的新規則；新候選需另留未看過的 script 驗證。

## 保留的工程變更與驗證

- `GeneralScenePromptRequests.kt`：把原先 `Translator` 私有的一般整幕／voice-hint 文字組裝抽為純函式，原方法委派呼叫。抽出前後的函式內容經標準化名稱與呼叫資格後逐位元對照相同，沒有改 prompt 文案。
- `AtlasStoryPromptEvaluationTest.kt`：可選的本地真實劇本匯出，使用正式 general builders、Android ICU、overrides 和最後的 emphasis wire；有錄製回應時用正式 array contract／emphasis body 解碼驗證。沒有環境變數時不讀私人案例，也不連線。
- 另測一般整幕的 requested/context-only 欄位及 voice-hint 分離。主評測的付費請求仍只有正式單句／選項路徑，**沒有宣稱付費驗收完整 scene JSON／voice-hint 路由**。
- 更新原 name-box wiring test，指向同一個正式純函式，保留整個 visible label 的檢查。
- 122 項相關 Kotlin 測試通過，0 失敗／錯誤／跳過；debug APK 建置成功。先前 18 項 Python 離線工具測試亦通過。沒有安裝 APK 或實機遊玩驗收。

付費呼叫數／費用均在執行前設硬上限（300 次／US$2）。216 次的保守預留總額約 US$0.273；按 provider 回報 token、全部使用尖峰且不計 prefix-cache 優惠估算約 **US$0.032**。這是估算而非帳單數字。所有金鑰輸入只在記憶體；產物掃描未發現 credential-shaped 值。

## 本機完整證據

忽略的 `temp/atlas-prompt-eval-20261101/` 保存：

- `baseline-source/`、`baseline-manifest.json`、`sources.json`、`script-metadata.json`。
- `cases-with-private-references.json`、`tuning.json`、`holdout.json`。
- `tuning-exports/`、`holdout-exports/` 的 baseline／candidate wire 訊息與 hash。
- `*-responses.json`、`*-blind.json`、`*-reviewed.json`、`*-validation*.json`、`*-report.json`。
- `reviewed-runs.json`、`summary.json`、`paid-budget-ledger.json`、`experiment-plan.json`。
- `prepare_cases.py`、`run_atlas_eval.py`、`extraction-parity.json`、`final-verification.log`。

`*-private-key.json` 是隨機盲評 ID 對照表，**不是 API 金鑰**。原始劇本、完整翻譯回應、帳務紀錄及私人評分封包不放入 APK 或提交到儲存庫。這份報告只列必要的例子、來源、結果與限制。

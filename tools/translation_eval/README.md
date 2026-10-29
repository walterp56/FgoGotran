# 一般翻譯 prompt 的離線評測

範圍：GENERAL NON-SAKURA。優先順序是翻譯品質、回應時間、prompt 雜訊。

2026-10-28 已完成授權的 DeepSeek 實測及三項小幅實作，結果、未採用方案及限制見 [RESULTS_2026-10-28.md](RESULTS_2026-10-28.md)。不要把候選改寫、原始回應和已採用的編譯後 prompt 混為一談。

同日較早的強外語保留專項見 [RESULTS_FOREIGN_EXPRESSIONS_2026-10-28.md](RESULTS_FOREIGN_EXPRESSIONS_2026-10-28.md)，共 300 次授權請求。其保留率是當時政策的符合率，不是全面翻譯品質評分。

後續依使用者澄清，外語改採語境、翻譯品質及可讀性的取捨，保留與意譯都可成立。新版與上述強保留版的比較見 [RESULTS_CONTEXTUAL_FOREIGN_2026-10-29.md](RESULTS_CONTEXTUAL_FOREIGN_2026-10-29.md)；兩輪案例與評分準則不同，不能直接比較通過率。最終語境版與基準分段採集，延遲比較有此限制。

這個目錄提供案例、正式組裝函式的匯出、人工盲評和結果統計。`evaluate.py` 與單元測試完全離線；另有需要明確授權才執行的 `run_deepseek.py` 實測收集器。收集器用隱藏輸入讀取金鑰，只留在記憶體，不寫入參數、檔案或報告。這些工具本身不會修改 App prompt、詞庫、角色卡或 Sakura 行為。

## 檔案與已知邊界

- `cases.json`：15 個人工編寫的合成案例 × 簡中／繁中／英文，共 45 個一般請求。不是官方劇情引文，也不是已量測的模型結果。
- `foreign_language_cases.json`：12 個外語保留與反例 × 簡中／繁中／英文，共 36 個一般請求。Atlas JP/CN 劇本只提供法文／西班牙文／德文表達的來源依據；周邊句子、ruby 與選項排列是人工案例，來源與驗收要求寫在檔案內。涵蓋外語正文／小字、既有重音符號、普通借詞、角色國籍、人名詞庫及不完整片假名。它不取代原有案例集。
- `contextual_foreign_cases.json`：目前的語境取捨專項，12 個人工案例 × 3 目標。一般問候可保留或意譯；詞語解釋及語言／詞形對比則必須保住關鍵字。另檢查普通借詞、角色國籍、詞庫譯名、ruby 資訊與選項契約。歷史強保留案例留作紀錄，不作本輪品質判分標準。
- `evidence.json`：離線詞庫／角色指引審核紀錄。目前只登記現有紅閻魔指引待核實，沒有已批准的詞條修改。`proposed` 或 `approved` 必須有可定位的日文證據、來源 URL、正例和反例；`approved` 另外需要審核者及決定。
- `evaluate.py`：只使用 Python 標準函式庫，驗證資料、產生盲評包、比較 prompt 長度及整理結果。輸出不會覆寫既有檔案。
- `test_evaluate.py`、`test_run_deepseek.py`：統計、資料完整性及收集器的合成測試，不會連線，也不是模型翻譯品質測試。
- `run_deepseek.py`：明確執行後才呼叫官方 DeepSeek Flash API；模型預設沿用 App 的 `deepseek-v4-flash`，`temperature=1.0`、停用思考、輸出上限 256。不讀 App 的金鑰設定；每次請求保存回應、實測時間、供應商 token 和 prefix-cache 用量。預設最多 180 次、硬上限 300 次；驗證／餘額／權限錯誤立即停止，連續三次網路或 HTTP 錯誤停止，不自動重試。
- `GeneralPromptEvaluationFixtureTest.kt`：直接呼叫正式 `PromptBuilder`、`GeneralPromptRequests`、`TranslationGlossaryBuilder`、前文處理及回應契約。只選取角色卡的一般／英文變體。

匯出包含目前工作目錄的來源檔 SHA-256，包括未提交的內容、詞庫來源與角色卡；不使用 Git HEAD 來假裝代表全部現況。御主、詞庫及前文是明示的案例輸入。這不測試實際 OCR、資料庫檢索、Android ICU 轉換、快取路由或網路傳輸。不能據此宣稱那些環節已通過實機驗證。

召喚案例測試一般對話在無姓名／有前文的組裝；不模擬召喚畫面偵測或證明 App 一定會走相同分支。

整幕 JSON 額外覆蓋三種目標的正式 system prompt 和 `GeneralTranslationResponseContract`，記錄於 `scene_system_checks`。`Translator` 的整幕 user builder 是私有方法，本次沒有複製它或更改執行期來暴露它，所以這三筆不是可送出的完整整幕請求。voice_hint、裁切、戰鬥字幕與 Sakura 不在這批基準內。

角色／詞庫來源證據應優先選可核對的遊戲文本及官方譯名，逐項記錄原文、定位、版本／身分／語境、JP/CN/EN 目標、正反例、審核決定及共用影響。共用詞庫或角色卡變動可能影響 Sakura；審核紀錄不會自動套用修改。不要憑合成案例把角色設定當成已核實事實。

## 1. 本地驗證

以下 PowerShell 指令在儲存庫根目錄執行；`$py` 可換成你自己的 Python 3。

```powershell
$py = "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
& $py -B tools/translation_eval/evaluate.py check
& $py -B -m unittest discover -s tools/translation_eval -p 'test_*.py'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.fgogotran.translation.GeneralPromptEvaluationFixtureTest' --rerun
```

預設匯出為 `app/build/reports/translation-eval/prompts.json`。它是可重建的產物；保存 A/B 基準時，指定不同檔名及版本名稱：

```powershell
$env:FGO_TRANSLATION_EVAL_VARIANT = 'baseline'
$env:FGO_TRANSLATION_EVAL_EXPORT = "$PWD\app\build\reports\translation-eval\baseline.json"
.\gradlew.bat :app:testDebugUnitTest --tests 'com.fgogotran.translation.GeneralPromptEvaluationFixtureTest' --rerun
Remove-Item Env:FGO_TRANSLATION_EVAL_VARIANT, Env:FGO_TRANSLATION_EVAL_EXPORT
```

明確指定的匯出路徑不可已存在。環境變數改變不保證 Gradle 判斷測試過期，因此使用 `--rerun` 強制重跑該測試工作。候選版本以 `candidate` 和另一個路徑保存。沒有執行期改動時，不應虛構一個「改善後」候選版本。

在 WSL 可用 `python3 -B tools/translation_eval/evaluate.py check` 及 `python3 -B -m unittest discover -s tools/translation_eval -p 'test_*.py'`。Gradle 使用既有 Android／Java 開發環境執行；Windows 環境變數和路徑不要直接貼入 Bash。

## 2. 另行批准後才收集實際後端輸出

先明列要評測的實際 App 非 Sakura 後端、配置模型、請求數及預算，再批准收集。不要以審查模型取代生產後端。其他後端仍需另外收集；DeepSeek 可使用已授權的收集器：

```powershell
& $py -B tools/translation_eval/run_deepseek.py --exports app/build/reports/translation-eval/baseline.json app/build/reports/translation-eval/candidate.json --out temp/translation-eval/responses.json --trials 2 --max-calls 180
```

這會執行 45 組 × A/B × 2 次，共 180 次付費請求。金鑰在提示後隱藏輸入，不能放在命令列。每組 A/B 相鄰且順序隨機，單一請求串行執行，逐筆 checkpoint；不會重跑已存在的結果檔。`--case-ids` 可先挑小批案例。收集器測量的是固定訊息的**首次回應**，不模擬 App 的修復／取樣回退／快取／OCR，也不宣稱 App 端到端速度。HTTP 成功仍可能違反輸出契約或翻錯，必須繼續驗證與評分。

額外案例可用 `FGO_TRANSLATION_EVAL_CASES` 指定 JSON 路徑匯出及驗證；不同案例集的 hash 不同，應分開比較。合成案例中的 `notes`、`speaker_targets` 是明示準備好的註記與姓名目標，不是實機 DB 檢索結果。已補齊御主的性別／玩家名註記及 Avalon 的現有詞庫參考；早期缺少這些資料的探索性試跑不可與補齊後的 A/B 混用。

外語案例集使用 `$env:FGO_TRANSLATION_EVAL_CASES="$PWD\tools\translation_eval\foreign_language_cases.json"` 匯出修改前／後版本，再使用相同環境變數驗證回應。36 組 × A/B × 2 次為 144 次請求，收集時明確指定 `--trials 2 --max-calls 144`。完成後移除這個環境變數，避免把原有案例集與外語專項混用。這批只覆蓋一般純文字與選項陣列；戰鬥／截圖等 profile 的提示詞覆蓋由 `GeneralForeignLanguagePromptTest` 離線檢查，不能將本批後端結果當成所有 App 路徑的實機驗證。

同一組案例／目標、backend、model、有效參數和 trial 配對比較。輪替 A/B 次序、固定快取條件並記錄失敗及所有修復，不能只挑成功輸出。記錄實際 system/user 請求的 `prompt_sha256`；雜湊格式為 UTF-8 的 `system + U+001F + system內容 + U+001E + user + U+001F + user內容`。

記錄格式如下。`<...>` 都是必須用實際匯出／量測替換的佔位值，這不是結果資料：

```json
{
  "schema_version": 1,
  "fixture_sha256": "<export.fixture_sha256>",
  "runs": [{
    "id": "baseline-repetition-en-1",
    "variant": "baseline",
    "request_id": "source_repetition:en",
    "prompt_sha256": "<request.prompt_sha256>",
    "backend": "<actual App backend>",
    "model": "<actual configured model>",
    "parameters": {"max_tokens": 1024, "temperature": 0},
    "trial": 1,
    "cache_status": "bypassed",
    "latency_scope": "backend_request_sequence",
    "attempts": [{"response": "<raw response content>"}]
  }]
}
```

- `parameters` 記錄有效的非機密參數，包括思考設定／取樣回退等；不要包含 API key 或驗證標頭。
- `attempts` 依序記錄所有請求，包括失敗草稿及修復，不能把修復輸出當成首次成功。`response` 是 App 收到的翻譯內容字串，不是供應商的完整 HTTP 包裝。
- `cache_status` 明確使用 `bypassed`、`hit` 或 `unknown`。冷啟動、快取命中及網路請求不混合比較。
- 每次嘗試可填實測的 `elapsed_ms`、`input_tokens`、`output_tokens`，未知則省略或填 null。任何一次缺少數據，該次完整序列就不計入該指標；缺值不當成零。
- `backend_request_sequence` 的延遲是全部已記錄請求時間相加，包含修復，不包含 OCR／排程等 App 等待時間。HTTP 層未捕捉的重試不會自動被本工具補算，應在收集時記錄。
- `app_end_to_end` 使用另填的 `end_to_end_ms`，必須說明起訖點；不能用單次 API 時間代替 App 體感延遲。
- 斷線等無文字回覆的失敗可用空字串記錄，其契約會失敗；不得直接刪除失敗樣本。

## 3. 用正式契約驗證記錄

```powershell
$env:FGO_TRANSLATION_EVAL_RESPONSES = "$PWD\temp\translation-eval\responses.json"
.\gradlew.bat :app:testDebugUnitTest --tests 'com.fgogotran.translation.GeneralPromptEvaluationFixtureTest' --rerun
Remove-Item Env:FGO_TRANSLATION_EVAL_RESPONSES
```

產生 `app/build/reports/translation-eval/validation.json`。逐次記錄正式 JSON 陣列契約是否通過；純文字只驗證非空。日文未翻、ruby 語意、姓名臆測和自然度仍須人工檢查，不能由「契約通過」推論正確。修復率在報告中以有兩次以上嘗試的 run 數呈現，可除以該組總數。

## 4. 盲評與報告

```powershell
& $py -B tools/translation_eval/evaluate.py blind --exports app/build/reports/translation-eval/baseline.json app/build/reports/translation-eval/candidate.json --responses temp/translation-eval/responses.json --out temp/translation-eval/review.json --key temp/translation-eval/private-key.json
```

審核者只看 `review.json`，不要看 private-key、原始 runs 或版本 prompt。每筆順序隨機，隱藏版本／模型／執行 id。請保留原文、輸出及檢查條件，只填 `scores`、`critical_failure`、`notes`。工具會檢查不可編輯內容的雜湊。盲評不能消除人從措辭辨認版本的可能；正式結論宜用獨立雙語審核者並仲裁分歧。

四個分項均為 0–4：`meaning`（語意與角色／動作方向）、`terminology`（術語及明示字面）、`voice`（語氣／敬語與當前人物）、`naturalness`（自然度）。4=符合要求，3=小瑕疵，2=需要修正，1=明顯錯誤，0=失敗或缺失。沒有術語的案例按「沒有引入錯誤術語」評分，不能留空一個分項。

漏譯、額外內容、錯認身分／關係、動作方向反轉、選項錯序或 ruby 含義遺失等設 `critical_failure=true` 並記述原因。尚未評分時全部保持 null；不能只填部分分數。回應結構失敗由正式契約另列，不能讓自然度高分蓋過它。

```powershell
& $py -B tools/translation_eval/evaluate.py report --exports app/build/reports/translation-eval/baseline.json app/build/reports/translation-eval/candidate.json --responses temp/translation-eval/responses.json --validation app/build/reports/translation-eval/validation.json --review temp/translation-eval/review.json --key temp/translation-eval/private-key.json --out temp/translation-eval/report.json
& $py -B tools/translation_eval/evaluate.py compare-prompts --baseline app/build/reports/translation-eval/baseline.json --candidate app/build/reports/translation-eval/candidate.json --out temp/translation-eval/prompt-size.json
```

報告按版本、後端、模型、語言、參數、快取及延遲範圍分組；先檢查重大錯誤及契約失敗，再看完全配對案例的品質分項。缺評不記零，沒有配對不宣稱 A/B 優劣。p50/p95 附有效樣本數；少量合成案例不能建立統計顯著性或模型優越性。

字元／UTF-8 bytes 僅作長度代理，不是 token 或雜訊分數。雜訊仍須人工檢查重複、互相衝突、無關及過度限定的指令。採用順序：品質改善且無新增重大錯誤 → 速度／修復成本合理 → 品質相近時選較精簡的版本。工具不自動選出贏家。

所有結果保存在忽略的 `temp/` 或 `app/build/`。不要提交個人遊戲記錄、API key 或原始驗證標頭。這批工具不更改 prompt 版本、不清除快取、不更新或發布 DB；日後每項執行期修改需有自己的證據與回歸驗收。

已採用的執行期變更由 App 程式負責：一般中／英文 prompt 版本已更新，角色提示快取使用實際選取的內容。角色語氣設定統一維護於 `app/src/main/assets/translation/character_context_prompts.tsv`，欄位如下：

```tsv
speaker_id	aliases	base_general	base_english	source_pattern	conditional_general	conditional_english	prompt_sakura
```

姓名／別名索引先解析出角色，一般模型才按當前日文的 `source_pattern` 選擇基本語氣或「基本語氣＋條件指引」，不加入身分猜測。沒有條件規則的角色，將 `source_pattern` 及兩個 `conditional_*` 欄位一併留空。來源形式判斷是保守的提示選擇，不是完整日文口癖分析器，也不授權模型自行添加口癖。

`prompt_sakura` 獨立保留原有文字；一般模型的條件篩選不作用於 Sakura。原有完整中／英文角色提示由基本語氣與條件指引組合，維持合併前的目標語言選擇、實際提示文字及快取識別。Sakura 模板、Sakura 版本及共用詞庫不變。此 TSV 隨 APK 發佈，不需要重建詞庫 DB。

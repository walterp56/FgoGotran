const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/localization/AppLanguageManager.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    '    private val hansToHant: Transliterator? by lazy {\n        listOf("Hans-Hant", "Simplified-Traditional")\n            .firstNotNullOfOrNull { id ->\n                runCatching { Transliterator.getInstance(id) }\n                    .onFailure { FgoLogger.warn(TAG, "Chinese UI transliterator unavailable: $id", it) }\n                    .getOrNull()\n            }\n    }\n',
    '    private val hansToHant: Transliterator? by lazy {\n        listOf("Hans-Hant", "Simplified-Traditional")\n            .firstNotNullOfOrNull { id ->\n                runCatching { Transliterator.getInstance(id) }\n                    .onFailure { FgoLogger.warn(TAG, "Chinese UI transliterator unavailable: $id", it) }\n                    .getOrNull()\n            }\n    }\n\n    private val hantToHans: Transliterator? by lazy {\n        listOf("Hant-Hans", "Traditional-Simplified")\n            .firstNotNullOfOrNull { id ->\n                runCatching { Transliterator.getInstance(id) }\n                    .onFailure { FgoLogger.warn(TAG, "Chinese UI transliterator unavailable: $id", it) }\n                    .getOrNull()\n            }\n    }\n',
    'AppLanguageManager hantToHans translator'
  );

  text = replaceOnce(
    text,
    '        "手动" to "手動"\n    )\n\n    fun getLanguage(context: Context): String {\n',
    '        "手动" to "手動"\n    )\n\n    private val simplifiedPhraseReplacements = listOf(\n        "戰" to "战",\n        "介面語言" to "界面语言",\n        "介面" to "界面",\n        "系統預設" to "系统默认",\n        "預設" to "默认",\n        "簡體中文" to "简体中文",\n        "設定" to "设置",\n        "軟體" to "软件",\n        "影片" to "视频",\n        "音訊" to "音频",\n        "快取" to "缓存",\n        "網路" to "网络",\n        "資訊" to "信息",\n        "資料" to "数据",\n        "檔案" to "文件",\n        "儲存" to "保存",\n        "支援" to "支持",\n        "滑鼠" to "鼠标",\n        "游標" to "光标",\n        "品質" to "质量",\n        "載入" to "加载",\n        "啟動" to "启动",\n        "停用" to "禁用",\n        "啟用" to "启用",\n        "開啟" to "打开",\n        "關閉" to "关闭",\n        "權限" to "权限",\n        "授權" to "授权",\n        "位址" to "地址",\n        "目前" to "当前",\n        "點擊" to "点击",\n        "選擇" to "选择",\n        "測試" to "测试",\n        "狀態" to "状态",\n        "伺服器" to "服务器",\n        "帳號" to "账号",\n        "登入" to "登录",\n        "註冊" to "注册",\n        "即時" to "实时",\n        "螢幕" to "屏幕",\n        "懸浮" to "悬浮",\n        "按鈕" to "按钮",\n        "翻譯" to "翻译",\n        "遊戲" to "游戏",\n        "錯誤" to "错误",\n        "紀錄" to "纪录",\n        "日誌" to "日志",\n        "匯出" to "导出",\n        "檢視" to "查看",\n        "下載" to "下载",\n        "發佈" to "发布",\n        "內容" to "内容",\n        "自訂" to "自定义",\n        "自動" to "自动",\n        "推薦" to "推荐",\n        "手動" to "手动"\n    )\n\n    fun getLanguage(context: Context): String {\n',
    'AppLanguageManager reversed phrase list'
  );

  text = replaceOnce(
    text,
    '    fun localizeUiText(context: Context, text: String): String {\n        if (text.isEmpty() || effectiveLanguageTag(context) != LANGUAGE_TRADITIONAL) {\n            return text\n        }\n        localizedTextCache[text]?.let { return it }\n        val localized = toTraditional(text)\n        if (localizedTextCache.size > 2048) {\n            localizedTextCache.clear()\n        }\n        localizedTextCache.putIfAbsent(text, localized)\n        return localized\n    }\n',
    '    fun localizeUiText(context: Context, text: String): String {\n        if (text.isEmpty()) return text\n        val language = effectiveLanguageTag(context)\n        val cacheKey = "$language\\u001F$text"\n        localizedTextCache[cacheKey]?.let { return it }\n        val localized = when (language) {\n            LANGUAGE_TRADITIONAL -> toTraditional(text)\n            LANGUAGE_SIMPLIFIED -> toSimplified(text)\n            else -> text\n        }\n        if (localizedTextCache.size > 2048) {\n            localizedTextCache.clear()\n        }\n        localizedTextCache.putIfAbsent(cacheKey, localized)\n        return localized\n    }\n',
    'AppLanguageManager bidirectional localizeUiText'
  );

  text = replaceOnce(
    text,
    '    private fun toTraditional(text: String): String {\n        var value = text\n        traditionalPhraseReplacements.forEach { (simplified, traditional) ->\n            if (value.contains(simplified)) {\n                value = value.replace(simplified, traditional)\n            }\n        }\n        val transliterator = hansToHant ?: return value\n        return runCatching { transliterator.transliterate(value) }.getOrDefault(value)\n    }\n',
    '    private fun toTraditional(text: String): String {\n        var value = text\n        traditionalPhraseReplacements.forEach { (simplified, traditional) ->\n            if (value.contains(simplified)) {\n                value = value.replace(simplified, traditional)\n            }\n        }\n        val transliterator = hansToHant ?: return value\n        return runCatching { transliterator.transliterate(value) }.getOrDefault(value)\n    }\n\n    private fun toSimplified(text: String): String {\n        var value = text\n        simplifiedPhraseReplacements.forEach { (traditional, simplified) ->\n            if (value.contains(traditional)) {\n                value = value.replace(traditional, simplified)\n            }\n        }\n        val transliterator = hantToHans ?: return value\n        return runCatching { transliterator.transliterate(value) }.getOrDefault(value)\n    }\n',
    'AppLanguageManager toSimplified'
  );

  write(path, text);
}

console.log('bidirectional language conversion applied');

package com.fgogotran.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.icu.text.Transliterator
import android.os.Build
import android.os.LocaleList
import com.fgogotran.util.FgoLogger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * App UI language selection and the migration bridge for existing Simplified-Chinese
 * hard-coded UI strings.
 *
 * New UI should prefer Android string resources. This manager keeps the first
 * Traditional-Chinese rollout self-contained so the large legacy UI can be converted
 * without changing OCR or translation behaviour.
 */
object AppLanguageManager {
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_TRADITIONAL = "zh-Hant"
    const val LANGUAGE_SIMPLIFIED = "zh-Hans"
    const val LANGUAGE_ENGLISH = "en"

    private const val TAG = "FGO/Language"
    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE = "ui_language"
    private val supportedLanguages = setOf(
        LANGUAGE_SYSTEM,
        LANGUAGE_TRADITIONAL,
        LANGUAGE_SIMPLIFIED,
        LANGUAGE_ENGLISH
    )
    private val traditionalRegions = setOf("TW", "HK", "MO")

    private val localizedTextCache = ConcurrentHashMap<String, String>()

    private val hansToHant: Transliterator? by lazy {
        listOf("Hans-Hant", "Simplified-Traditional")
            .firstNotNullOfOrNull { id ->
                runCatching { Transliterator.getInstance(id) }
                    .onFailure { FgoLogger.warn(TAG, "Chinese UI transliterator unavailable: $id", it) }
                    .getOrNull()
            }
    }

    private val hantToHans: Transliterator? by lazy {
        listOf("Hant-Hans", "Traditional-Simplified")
            .firstNotNullOfOrNull { id ->
                runCatching { Transliterator.getInstance(id) }
                    .onFailure { FgoLogger.warn(TAG, "Chinese UI transliterator unavailable: $id", it) }
                    .getOrNull()
            }
    }

    /**
     * Phrase-level replacements first. ICU then handles the remaining Han characters.
     * This avoids common Mandarin-vs-Taiwan differences that a character-only conversion
     * would miss (设置 -> 設定, 软件 -> 軟體, 缓存 -> 快取, ...).
     */
    private val traditionalPhraseReplacements = listOf(
        "界面语言" to "介面語言",
        "界面" to "介面",
        "系统默认" to "系統預設",
        "默认" to "預設",
        "简体中文" to "簡體中文",
        "设置" to "設定",
        "软件" to "軟體",
        "视频" to "影片",
        "音频" to "音訊",
        "缓存" to "快取",
        "网络" to "網路",
        "信息" to "資訊",
        "数据" to "資料",
        "文件" to "檔案",
        "保存" to "儲存",
        "支持" to "支援",
        "鼠标" to "滑鼠",
        "光标" to "游標",
        "质量" to "品質",
        "加载" to "載入",
        "启动" to "啟動",
        "禁用" to "停用",
        "启用" to "啟用",
        "打开" to "開啟",
        "关闭" to "關閉",
        "权限" to "權限",
        "授权" to "授權",
        "地址" to "位址",
        "当前" to "目前",
        "点击" to "點擊",
        "选择" to "選擇",
        "测试" to "測試",
        "状态" to "狀態",
        "服务商" to "服務商",
        "服务器" to "伺服器",
        "账号" to "帳號",
        "登录" to "登入",
        "注册" to "註冊",
        "实时" to "即時",
        "屏幕" to "螢幕",
        "悬浮" to "懸浮",
        "按钮" to "按鈕",
        "翻译" to "翻譯",
        "游戏" to "遊戲",
        "错误" to "錯誤",
        "纪录" to "紀錄",
        "日志" to "日誌",
        "导出" to "匯出",
        "查看" to "檢視",
        "下载" to "下載",
        "发布" to "發佈",
        "内容" to "內容",
        "配置" to "設定",
        "自定义" to "自訂",
        "自动" to "自動",
        "推荐" to "推薦",
        "手动" to "手動"
    )

    private val simplifiedPhraseReplacements = listOf(
        "戰" to "战",
        "介面語言" to "界面语言",
        "介面" to "界面",
        "系統預設" to "系统默认",
        "預設" to "默认",
        "簡體中文" to "简体中文",
        "設定" to "设置",
        "軟體" to "软件",
        "影片" to "视频",
        "音訊" to "音频",
        "快取" to "缓存",
        "網路" to "网络",
        "資訊" to "信息",
        "資料" to "数据",
        "檔案" to "文件",
        "儲存" to "保存",
        "支援" to "支持",
        "滑鼠" to "鼠标",
        "游標" to "光标",
        "品質" to "质量",
        "載入" to "加载",
        "啟動" to "启动",
        "停用" to "禁用",
        "啟用" to "启用",
        "開啟" to "打开",
        "關閉" to "关闭",
        "權限" to "权限",
        "授權" to "授权",
        "位址" to "地址",
        "目前" to "当前",
        "點擊" to "点击",
        "選擇" to "选择",
        "測試" to "测试",
        "狀態" to "状态",
        "伺服器" to "服务器",
        "帳號" to "账号",
        "登入" to "登录",
        "註冊" to "注册",
        "即時" to "实时",
        "螢幕" to "屏幕",
        "懸浮" to "悬浮",
        "按鈕" to "按钮",
        "翻譯" to "翻译",
        "遊戲" to "游戏",
        "錯誤" to "错误",
        "紀錄" to "纪录",
        "日誌" to "日志",
        "匯出" to "导出",
        "檢視" to "查看",
        "下載" to "下载",
        "發佈" to "发布",
        "內容" to "内容",
        "自訂" to "自定义",
        "自動" to "自动",
        "推薦" to "推荐",
        "手動" to "手动"
    )

    fun getLanguage(context: Context): String {
        val raw = prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM).orEmpty()
        return normalizeLanguage(raw)
    }

    fun setLanguage(context: Context, language: String) {
        val normalized = normalizeLanguage(language)
        prefs(context).edit().putString(KEY_LANGUAGE, normalized).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = if (normalized == LANGUAGE_SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(normalized)
            }
        }
        FgoLogger.info(TAG, "UI language updated: $normalized")
    }

    /** Applies an explicit UI locale to Activity and View-based contexts. */
    fun wrap(context: Context): Context {
        val language = getLanguage(context)
        if (language == LANGUAGE_SYSTEM) return context

        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    /** Effective language after resolving [LANGUAGE_SYSTEM] against the system locale. */
    fun effectiveLanguageTag(context: Context): String {
        return when (val language = getLanguage(context)) {
            LANGUAGE_TRADITIONAL, LANGUAGE_SIMPLIFIED, LANGUAGE_ENGLISH -> language
            else -> resolveSystemLanguage(context)
        }
    }

    /**
     * Converts legacy Simplified-Chinese UI strings for the Traditional-Chinese UI.
     * Non-Chinese text is returned unchanged.
     */
    fun localizeUiText(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val language = effectiveLanguageTag(context)
        val cacheKey = "$language\u001F$text"
        localizedTextCache[cacheKey]?.let { return it }
        val localized = when (language) {
            LANGUAGE_TRADITIONAL -> toTraditional(text)
            LANGUAGE_SIMPLIFIED -> toSimplified(text)
            LANGUAGE_ENGLISH -> EnglishUiText.translate(text) ?: text
            else -> text
        }
        if (localizedTextCache.size > 2048) {
            localizedTextCache.clear()
        }
        localizedTextCache.putIfAbsent(cacheKey, localized)
        return localized
    }

    fun localizedString(context: Context, resId: Int, vararg formatArgs: Any): String =
        localizeUiText(context, context.getString(resId, *formatArgs))

    fun recreateActivity(context: Context) {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                current.recreate()
                return
            }
            current = current.baseContext
        }
    }

    private fun toTraditional(text: String): String {
        var value = text
        traditionalPhraseReplacements.forEach { (simplified, traditional) ->
            if (value.contains(simplified)) {
                value = value.replace(simplified, traditional)
            }
        }
        val transliterator = hansToHant ?: return value
        return runCatching { transliterator.transliterate(value) }.getOrDefault(value)
    }

    private fun toSimplified(text: String): String {
        var value = text
        simplifiedPhraseReplacements.forEach { (traditional, simplified) ->
            if (value.contains(traditional)) {
                value = value.replace(traditional, simplified)
            }
        }
        val transliterator = hantToHans ?: return value
        return runCatching { transliterator.transliterate(value) }.getOrDefault(value)
    }

    private fun resolveSystemLanguage(context: Context): String {
        val locales = context.resources.configuration.locales
        val locale = if (locales.size() == 0) Locale.getDefault() else locales[0]
        if (locale.language.equals("en", ignoreCase = true)) {
            return LANGUAGE_ENGLISH
        }
        if (!locale.language.equals("zh", ignoreCase = true)) {
            return LANGUAGE_SIMPLIFIED
        }
        val script = locale.script
        val region = locale.country.uppercase(Locale.ROOT)
        return if (script.equals("Hant", ignoreCase = true) || region in traditionalRegions) {
            LANGUAGE_TRADITIONAL
        } else {
            LANGUAGE_SIMPLIFIED
        }
    }

    fun normalizeLanguage(language: String): String =
        language.takeIf { it in supportedLanguages } ?: LANGUAGE_SYSTEM

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}




const fs = require('fs');
const p = 'app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt';
let t = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
function once(needle, replacement, label) { const c = t.split(needle).length - 1; if (c !== 1) throw new Error(`${label}: ${c}`); return t.replace(needle, replacement); }
t = once('import androidx.annotation.DrawableRes\n', 'import androidx.annotation.DrawableRes\nimport androidx.annotation.StringRes\n', 'StringRes import');
t = once('cacheClearMessage = "已清除 $count 条缓存"', 'cacheClearMessage = context.getString(R.string.settings_clear_cache_result, count)', 'cache result');
t = once('private val targetChineseLocaleOptions = listOf(\n    TargetChineseLocaleOption(\n        locale = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,\n        label = "简体中文"\n    ),\n    TargetChineseLocaleOption(\n        locale = SettingsRepository.TARGET_LOCALE_TRADITIONAL,\n        label = "繁體中文"\n    )\n)', 'private val targetChineseLocaleOptions = listOf(\n    TargetChineseLocaleOption(\n        locale = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,\n        labelRes = R.string.settings_target_simplified\n    ),\n    TargetChineseLocaleOption(\n        locale = SettingsRepository.TARGET_LOCALE_TRADITIONAL,\n        labelRes = R.string.settings_target_traditional\n    )\n)', 'target options');
t = once('private data class TargetChineseLocaleOption(\n    val locale: String,\n    val label: String\n)', 'private data class TargetChineseLocaleOption(\n    val locale: String,\n    @StringRes val labelRes: Int\n)', 'target data');
t = once('private val playerGenderOptions = listOf(\n    PlayerGenderOption(\n        gender = SettingsRepository.PLAYER_GENDER_MALE,\n        label = "男"\n    ),\n    PlayerGenderOption(\n        gender = SettingsRepository.PLAYER_GENDER_FEMALE,\n        label = "女"\n    )\n)', 'private val playerGenderOptions = listOf(\n    PlayerGenderOption(\n        gender = SettingsRepository.PLAYER_GENDER_MALE,\n        labelRes = R.string.settings_gender_male\n    ),\n    PlayerGenderOption(\n        gender = SettingsRepository.PLAYER_GENDER_FEMALE,\n        labelRes = R.string.settings_gender_female\n    )\n)', 'gender options');
t = once('private data class PlayerGenderOption(\n    val gender: String,\n    val label: String\n)', 'private data class PlayerGenderOption(\n    val gender: String,\n    @StringRes val labelRes: Int\n)', 'gender data');
t = once('option.label,', 'stringResource(option.labelRes),', 'translation label use');
t = once('text = option.label,', 'text = stringResource(option.labelRes),', 'gender label use');
t = once('"$safeCount 幕"', 'stringResource(R.string.settings_context_scenes, safeCount)', 'scene count');
fs.writeFileSync(p, t, 'utf8');
console.log('settings remaining strings finished');



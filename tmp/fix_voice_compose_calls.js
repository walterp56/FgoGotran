const fs = require('fs');
const p = 'app/src/main/java/com/fgogotran/ui/screen/VoiceSettingsScreen.kt';
let t = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
function once(needle, replacement, label) {
  const c = t.split(needle).length - 1;
  if (c !== 1) throw new Error(`${label}: expected 1, found ${c}`);
  return t.replace(needle, replacement);
}
t = once('import androidx.annotation.DrawableRes\n', 'import android.content.Context\nimport androidx.annotation.DrawableRes\nimport androidx.annotation.StringRes\n', 'imports');
t = t.split('stringResource(R.string.voice_auto_11)').join('context.getString(R.string.voice_auto_11)');
t = t.split('stringResource(R.string.voice_auto_47)').join('context.getString(R.string.voice_auto_47)');
t = t.split('stringResource(R.string.voice_auto_19)').join('context.getString(R.string.voice_auto_19)');
t = t.split('stringResource(R.string.voice_auto_28)').join('context.getString(R.string.voice_auto_28)');
t = once('azureVoiceTestSample(settingsRepository.targetChineseLocale.first())', 'azureVoiceTestSample(context, settingsRepository.targetChineseLocale.first())', 'sample call');
t = once('voiceTestSuccessMessage(\n                    result = result,', 'voiceTestSuccessMessage(\n                    context = context,\n                    result = result,', 'success call');
t = once('voiceTestErrorMessage(e)', 'voiceTestErrorMessage(context, e)', 'error call');
t = once('option.title,', 'stringResource(option.titleRes),', 'region title use');
t = once('private data class AzureSpeechRegionOption(\n    val region: String,\n    val title: String,\n    val subtitle: String\n)', 'private data class AzureSpeechRegionOption(\n    val region: String,\n    @StringRes val titleRes: Int,\n    val subtitle: String\n)', 'region data class');
t = once('private val azureSpeechRegionOptions = listOf(\n    AzureSpeechRegionOption(\n        region = SettingsRepository.AZURE_SPEECH_REGION_GLOBAL_SOUTHEAST_ASIA,\n        title = stringResource(R.string.voice_auto_26),\n        subtitle = "southeastasia"\n    ),\n    AzureSpeechRegionOption(\n        region = SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3,\n        title = stringResource(R.string.voice_auto_27),\n        subtitle = "chinanorth3"\n    )\n)', 'private val azureSpeechRegionOptions = listOf(\n    AzureSpeechRegionOption(\n        region = SettingsRepository.AZURE_SPEECH_REGION_GLOBAL_SOUTHEAST_ASIA,\n        titleRes = R.string.voice_auto_26,\n        subtitle = "southeastasia"\n    ),\n    AzureSpeechRegionOption(\n        region = SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3,\n        titleRes = R.string.voice_auto_27,\n        subtitle = "chinanorth3"\n    )\n)', 'region list');
t = once('private fun azureVoiceTestSample(targetChineseLocale: String): AzureVoiceTestSample {', 'private fun azureVoiceTestSample(context: Context, targetChineseLocale: String): AzureVoiceTestSample {', 'sample signature');
t = t.split('speakerName = stringResource(R.string.voice_auto_49)').join('speakerName = context.getString(R.string.voice_auto_49)');
t = t.split('dialogue = stringResource(R.string.voice_auto_9)').join('dialogue = context.getString(R.string.voice_auto_9)');
t = t.split('speakerName = stringResource(R.string.voice_auto_50)').join('speakerName = context.getString(R.string.voice_auto_50)');
t = t.split('dialogue = stringResource(R.string.voice_auto_10)').join('dialogue = context.getString(R.string.voice_auto_10)');
t = once('private fun voiceTestSuccessMessage(\n    result: AzureVoiceTestResult,', 'private fun voiceTestSuccessMessage(\n    context: Context,\n    result: AzureVoiceTestResult,', 'success signature');
t = t.split('apiHintError != null -> stringResource(R.string.voice_auto_18)').join('apiHintError != null -> context.getString(R.string.voice_auto_18)');
t = t.split('else -> stringResource(R.string.voice_auto_23)').join('else -> context.getString(R.string.voice_auto_23)');
t = once('return "测试语音已播放$apiStatus"', 'return context.getString(R.string.voice_test_voice_played, apiStatus)', 'voice played');
t = once('private fun voiceTestErrorMessage(error: Throwable): String {', 'private fun voiceTestErrorMessage(context: Context, error: Throwable): String {', 'error signature');
t = t.split('stringResource(R.string.voice_auto_14)').join('context.getString(R.string.voice_auto_14)');
t = t.split('stringResource(R.string.voice_auto_12)').join('context.getString(R.string.voice_auto_12)');
t = once('"Azure 语音请求失败：${message.take(96)}"', 'context.getString(R.string.voice_azure_request_failed, message.take(96))', 'azure request failed');
t = t.split('stringResource(R.string.voice_auto_15)').join('context.getString(R.string.voice_auto_15)');
t = once('message.isNotBlank() -> "测试失败：${message.take(96)}"', 'message.isNotBlank() -> context.getString(R.string.voice_test_failed_detail, message.take(96))', 'test failed detail');
t = once('else -> "测试失败：${error::class.java.simpleName}"', 'else -> context.getString(R.string.voice_test_failed_detail, error::class.java.simpleName)', 'test failed simple');
fs.writeFileSync(p, t, 'utf8');
console.log('voice compose call fixes applied');





const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/screen/ApiSettingsScreen.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(source, needle, replacement, label) {
  const count = source.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return source.replace(needle, replacement);
}
function replaceAll(text, needle, replacement, expected, label) {
  const count = text.split(needle).length - 1;
  if (count !== expected) throw new Error(`${label}: expected ${expected} occurrences, found ${count}`);
  return text.split(needle).join(replacement);
}

text = replaceOnce(text, 'import android.os.SystemClock\n', 'import android.content.Context\nimport android.os.SystemClock\n', 'Context import');
text = replaceOnce(text, 'import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.res.stringResource\n', 'Compose localization imports');
text = replaceOnce(text, 'import com.fgogotran.analytics.AppAnalytics\n', 'import com.fgogotran.R\nimport com.fgogotran.analytics.AppAnalytics\n', 'R import');

const helperStart = text.indexOf('private fun formatApiResponseTime');
const helperEnd = text.indexOf('@OptIn(ExperimentalMaterial3Api::class)');
if (helperStart < 0 || helperEnd < 0 || helperEnd <= helperStart) throw new Error('helper block bounds not found');
const helperBlock = `private fun formatApiResponseTime(context: Context, durationMs: Long): String {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val tenths = (safeDuration + 50L) / 100L
    return context.getString(R.string.api_response_seconds, "\${tenths / 10}.\${tenths % 10}")
}

private fun apiTestMessage(context: Context, status: String, durationMs: Long, result: String): String =
    context.getString(
        R.string.api_test_message,
        status,
        formatApiResponseTime(context, durationMs),
        result
    )

private fun formatSamplingValue(value: Double): String = value.toString()

private fun apiBackendDisplayName(context: Context, backend: String): String =
    when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_ZHIPU -> context.getString(R.string.api_backend_zhipu)
        SettingsRepository.BACKEND_QWEN -> context.getString(R.string.api_backend_qwen)
        SettingsRepository.BACKEND_CUSTOM_OPENAI -> context.getString(R.string.api_backend_custom)
        else -> SettingsRepository.backendDisplayName(backend)
    }

private fun samplingRecommendation(context: Context, backend: String, apiModel: String): String =
    when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_DEEPSEEK -> context.getString(
            R.string.api_sampling_deepseek,
            SettingsRepository.DEFAULT_DEEPSEEK_TEMPERATURE.toString()
        )
        SettingsRepository.BACKEND_ZHIPU -> context.getString(R.string.api_sampling_zhipu)
        SettingsRepository.BACKEND_QWEN -> context.getString(R.string.api_sampling_qwen)
        SettingsRepository.BACKEND_GPT -> if (
            SettingsRepository.supportsApiSamplingCustomization(backend, apiModel)
        ) {
            context.getString(R.string.api_sampling_gpt_custom)
        } else {
            context.getString(R.string.api_sampling_gpt_default)
        }
        SettingsRepository.BACKEND_GEMINI -> context.getString(R.string.api_sampling_gemini)
        SettingsRepository.BACKEND_CLAUDE -> context.getString(R.string.api_sampling_claude)
        else -> context.getString(R.string.api_sampling_local)
    }

private fun apiTestFailureResult(context: Context, error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.startsWith("API 地址") ||
            message.startsWith("HTTP 仅允许") ||
            message.startsWith("模型名称") ->
            localizedApiError(context, message)
        message.contains("Cleartext HTTP traffic", ignoreCase = true) ->
            context.getString(R.string.api_error_local_http_disabled)
        message.contains("Role must be in [user, assistant]", ignoreCase = true) ->
            context.getString(R.string.api_error_model_format)
        message.contains("API Key", ignoreCase = true) || message.contains("401") ->
            context.getString(R.string.api_error_check_key)
        message.contains("model", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("invalid_request", ignoreCase = true) ||
            message.contains("400") ->
            context.getString(R.string.api_error_provider_rejected, message.take(160))
        message.contains("quota", ignoreCase = true) ||
            message.contains("insufficient", ignoreCase = true) ||
            message.contains("balance", ignoreCase = true) ||
            message.contains("402") ||
            message.contains("429") ->
            context.getString(R.string.api_error_quota, message.take(160))
        message.contains("empty", ignoreCase = true) ->
            context.getString(R.string.api_error_empty_response)
        message.contains("untranslated", ignoreCase = true) ->
            context.getString(R.string.api_error_untranslated)
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ->
            context.getString(R.string.api_error_timeout)
        message.contains("connect", ignoreCase = true) ||
            message.contains("unreachable", ignoreCase = true) ||
            message.contains("refused", ignoreCase = true) ->
            context.getString(R.string.api_error_connect)
        message.isNotBlank() ->
            context.getString(R.string.api_error_provider, message.take(160))
        else ->
            context.getString(R.string.api_error_test_failed)
    }
}

private fun localizedApiError(context: Context, message: String): String = when (message) {
    "API 地址不能为空" -> context.getString(R.string.api_error_url_empty)
    "API 地址格式无效" -> context.getString(R.string.api_error_url_invalid)
    "API 地址必须使用 https:// 或 http://" -> context.getString(R.string.api_error_url_scheme)
    "API 地址必须包含有效的服务器地址" -> context.getString(R.string.api_error_url_host)
    "API 地址不能包含用户名或密码" -> context.getString(R.string.api_error_url_userinfo)
    "API 地址不能包含 # 片段" -> context.getString(R.string.api_error_url_fragment)
    "API 地址端口无效" -> context.getString(R.string.api_error_url_port)
    "API 地址必须指向 Chat Completions 接口，例如 /v1/chat/completions" ->
        context.getString(R.string.api_error_url_path)
    "HTTP 仅允许数字形式的电脑私有局域网地址；公网或主机名请使用 HTTPS" ->
        context.getString(R.string.api_error_http_private)
    "模型名称不能为空" -> context.getString(R.string.api_error_model_empty)
    else -> message
}

`;
text = text.slice(0, helperStart) + helperBlock + text.slice(helperEnd);

text = replaceOnce(text, '    val scope = rememberCoroutineScope()\n    val scrollState = rememberScrollState()\n', '    val scope = rememberCoroutineScope()\n    val context = LocalContext.current\n    val scrollState = rememberScrollState()\n', 'context val');
text = replaceOnce(text, '    val backendOptions = remember {\n', '    val customBackendNote = context.getString(R.string.api_compat_note)\n    val backendOptions = remember(customBackendNote) {\n', 'backend options remember');
text = replaceOnce(text, '                "兼容 OpenAI Chat Completions；支持可信局域网内的本地模型"\n', '                customBackendNote\n', 'custom backend note');

text = replaceOnce(text, '{ "Temperature 必须在 0.00 到 1.00 之间" }', '{ context.getString(R.string.api_error_temperature_range) }', 'temperature require');
text = replaceOnce(text, '{ "Top-p 必须在 0.01 到 1.00 之间" }', '{ context.getString(R.string.api_error_top_p_range) }', 'top p require');
text = replaceOnce(text, '                saveMessage = "已保存"\n', '                saveMessage = context.getString(R.string.api_saved)\n', 'saved message');
text = replaceOnce(text, '?: "保存失败"', '?: context.getString(R.string.api_save_failed)', 'save failed message');
text = replaceAll(text, '                saveMessage = apiTestMessage(\n', '', 2, 'apiTestMessage marker');
// The marker replacement above intentionally does nothing; calls are updated below.
text = replaceOnce(text, 'status = "成功",', 'context = context,\n                    status = context.getString(R.string.api_success),', 'api success call');
text = replaceOnce(text, 'status = "失败",', 'context = context,\n                    status = context.getString(R.string.api_failed),', 'api failed call');
text = replaceOnce(text, '                                "do_sample" -> "采样开关"', '                                "do_sample" -> context.getString(R.string.api_sampling_switches)', 'sampling label');
text = replaceOnce(text, '"可用于 FgoGotran 翻译；接口不接受 $ignoredLabels，已自动忽略"', 'context.getString(R.string.api_ignored_params, ignoredLabels)', 'ignored params');
text = replaceOnce(text, '                        "可用于 FgoGotran 翻译"\n', '                        context.getString(R.string.api_ready)\n', 'ready message');
text = replaceOnce(text, 'result = apiTestFailureResult(e)', 'result = apiTestFailureResult(context, e)', 'failure result call');
text = replaceOnce(text, 'samplingRecommendation(selectedBackend, apiModel)', 'samplingRecommendation(context, selectedBackend, apiModel)', 'sampling recommendation call');
text = replaceOnce(text, 'label = SettingsRepository.backendDisplayName(option.value)', 'label = apiBackendDisplayName(context, option.value)', 'backend display name');

const uiReplacements = [
  ['Text("API接口")', 'Text(stringResource(R.string.api_title))'],
  ['Text("返回", color = MaterialTheme.colorScheme.primary)', 'Text(stringResource(R.string.api_back), color = MaterialTheme.colorScheme.primary)'],
  ['Text("服务商", style = MaterialTheme.typography.titleMedium)', 'Text(stringResource(R.string.api_provider), style = MaterialTheme.typography.titleMedium)'],
  ['Text("请求设置", style = MaterialTheme.typography.titleMedium)', 'Text(stringResource(R.string.api_request_settings), style = MaterialTheme.typography.titleMedium)'],
  ['Text("站点", style = MaterialTheme.typography.bodyMedium)', 'Text(stringResource(R.string.api_site), style = MaterialTheme.typography.bodyMedium)'],
  ['Text("中国站")', 'Text(stringResource(R.string.api_site_china))'],
  ['Text("国际站")', 'Text(stringResource(R.string.api_site_international))'],
  ['Text(\n                                "请使用对应站点的 API Key。",', 'Text(\n                                stringResource(R.string.api_site_key_note),'],
  ['label = { Text("API 地址") }', 'label = { Text(stringResource(R.string.api_address)) }'],
  ['Text("HTTPS 可连接任意服务；HTTP 仅允许数字形式的私有局域网 IP")', 'Text(stringResource(R.string.api_address_https_note))'],
  ['                                    "本地 HTTP 不加密，仅应在可信 Wi-Fi 中使用；请保留 API Key 认证。"\n                                } else {\n                                    "HTTP 地址必须使用私有局域网数字 IP，并指向 /chat/completions。"\n                                }', '                                    stringResource(R.string.api_local_http_warning)\n                                } else {\n                                    stringResource(R.string.api_http_address_hint)\n                                }'],
  ['label = { Text("模型") }', 'label = { Text(stringResource(R.string.api_model)) }'],
  ['Text("当前服务商需要 API Key")', 'Text(stringResource(R.string.api_api_key_required))'],
  ['Text(if (advancedSamplingExpanded) "收起高级自定义" else "高级自定义")', 'Text(if (advancedSamplingExpanded) stringResource(R.string.api_advanced_hide) else stringResource(R.string.api_advanced_custom))'],
  ['Text("自动（推荐）")', 'Text(stringResource(R.string.api_sampling_auto_recommended))'],
  ['label = { Text("自定义") }', 'label = { Text(stringResource(R.string.api_sampling_custom)) }'],
  ['Text(\n                                "该服务商或模型为保证请求兼容性，仅使用自动模式。",', 'Text(\n                                stringResource(R.string.api_sampling_auto_only),'],
  ['                                    "本地／自定义接口可单独启用，也可同时发送两个参数。"\n                                } else {\n                                    "云端接口一次只启用一个参数；开启另一项会自动关闭当前项。"\n                                }', '                                    stringResource(R.string.api_sampling_local_note)\n                                } else {\n                                    stringResource(R.string.api_sampling_cloud_note)\n                                }'],
  ['Text("控制随机程度", style = MaterialTheme.typography.bodyMedium)', 'Text(stringResource(R.string.api_temperature_title), style = MaterialTheme.typography.bodyMedium)'],
  ['Text(if (samplingCapabilities.supportsTemperature) {\n                                            "控制随机程度"\n                                        } else {\n                                            "当前模型不支持"\n                                        }', 'Text(if (samplingCapabilities.supportsTemperature) {\n                                            stringResource(R.string.api_temperature_title)\n                                        } else {\n                                            stringResource(R.string.api_not_supported)\n                                        }'],
  ['supportingText = { Text("范围 0.00–1.00；数值越低越稳定") }', 'supportingText = { Text(stringResource(R.string.api_temperature_range)) }'],
  ['Text("限制候选词范围", style = MaterialTheme.typography.bodyMedium)', 'Text(stringResource(R.string.api_top_p_title), style = MaterialTheme.typography.bodyMedium)'],
  ['Text(if (samplingCapabilities.supportsTopP) {\n                                            "限制候选词范围"\n                                        } else {\n                                            "当前模型不支持"\n                                        }', 'Text(if (samplingCapabilities.supportsTopP) {\n                                            stringResource(R.string.api_top_p_title)\n                                        } else {\n                                            stringResource(R.string.api_not_supported)\n                                        }'],
  ['supportingText = { Text("范围 0.01–1.00；数值越低候选范围越小") }', 'supportingText = { Text(stringResource(R.string.api_top_p_range)) }'],
  ['Text(if (testingApi) "测试中" else "测试 API")', 'Text(if (testingApi) stringResource(R.string.api_testing) else stringResource(R.string.api_test))'],
  ['Text("恢复默认")', 'Text(stringResource(R.string.api_restore_defaults))'],
  ['Text("应用API设置")', 'Text(stringResource(R.string.api_apply))']
];
for (const [needle, replacement] of uiReplacements) {
  const count = text.split(needle).length - 1;
  if (count === 0) continue;
  if (count > 1) throw new Error(`UI replacement ambiguous for ${needle}: found ${count}`);
  text = text.replace(needle, replacement);
}
fs.writeFileSync(filePath, text, 'utf8');
console.log('api screen refactor applied');




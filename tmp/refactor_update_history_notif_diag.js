const fs = require('fs');
function read(p){return fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n');}
function write(p,t){fs.writeFileSync(p,t,'utf8');}
function once(source,needle,replacement,label){const c=source.split(needle).length-1;if(c!==1)throw new Error(`${label}: expected 1, found ${c}`);return source.replace(needle,replacement);}
function all(source,needle,replacement,expected,label){const c=source.split(needle).length-1;if(c!==expected)throw new Error(`${label}: expected ${expected}, found ${c}`);return source.split(needle).join(replacement);}

{
  const p='app/src/main/java/com/fgogotran/ui/component/AppUpdateDialog.kt';
  let t=read(p);
  t=once(t,'import androidx.compose.ui.Modifier\n','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.res.stringResource\n','stringResource import');
  t=once(t,'import com.fgogotran.update.AppVersionInfo\n','import com.fgogotran.R\nimport com.fgogotran.update.AppVersionInfo\n','R import');
  t=all(t,'Text("发现新版本")','Text(stringResource(R.string.update_title))',2,'update title');
  t=all(t,'Text("暂不更新")','Text(stringResource(R.string.update_not_now))',2,'not now');
  t=all(t,'Text("立即更新")','Text(stringResource(R.string.update_now))',2,'update now');
  t=once(t,'Text("此版本不再提醒")','Text(stringResource(R.string.update_skip_version))','skip version');
  t=once(t,'Text("当前版本：$currentVersionName")','Text(stringResource(R.string.update_current_version, currentVersionName))','current version');
  t=once(t,'Text("最新版本：${update.versionName}")','Text(stringResource(R.string.update_latest_version, update.versionName))','latest version');
  t=once(t,'Text("发布日期：${releaseDate.substringBefore(\'T\')}")','Text(stringResource(R.string.update_release_date, releaseDate.substringBefore(\'T\')))','release date');
  t=once(t,'text = "更新内容",','text = stringResource(R.string.update_whats_new),','whats new');
  t=once(t,'Text("请前往下载页面查看更新内容。")','Text(stringResource(R.string.update_open_download))','open download');
  write(p,t);
}
{
  const p='app/src/main/java/com/fgogotran/ui/overlay/HistoryOverlayPanel.kt';
  let t=read(p);
  t=once(t,'import com.fgogotran.localization.AppLanguageManager\n','','remove AppLanguageManager import');
  t=once(t,'import java.util.Locale\n','import com.fgogotran.R\nimport java.util.Locale\n','R import');
  t=once(t,'AppLanguageManager.localizeUiText(context, "暂无翻译LOG。")','context.getString(R.string.history_empty)','history empty');
  write(p,t);
}
{
  const p='app/src/main/java/com/fgogotran/runner/FgoRunnerService.kt';
  let t=read(p);
  t=once(t,'import com.fgogotran.localization.AppLanguageManager\n','','remove AppLanguageManager import');
  t=once(t,'.setContentText(AppLanguageManager.localizeUiText(this, "翻译悬浮窗正在运行"))','.setContentText(getString(R.string.notification_running))','notification text');
  write(p,t);
}
{
  const p='app/src/main/java/com/fgogotran/ui/screen/DiagnosticLogScreen.kt';
  let t=read(p);
  t=once(t,'import com.fgogotran.diagnostic.DiagnosticEventStore\n','import com.fgogotran.R\nimport com.fgogotran.diagnostic.DiagnosticEventStore\n','R import');
  t=once(t,'import androidx.compose.ui.platform.LocalContext\n','import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.res.stringResource\n','stringResource import');
  t=once(t,'Text("错误纪录")','Text(stringResource(R.string.diagnostic_title))','diag title');
  t=once(t,'Text("返回", color = MaterialTheme.colorScheme.primary)','Text(stringResource(R.string.diagnostic_back), color = MaterialTheme.colorScheme.primary)','diag back');
  t=once(t,'"自动显示全部最近问题：错误、权限阻塞、资料更新失败、缺少语音档案、临时语音 API 建立结果。"','stringResource(R.string.diagnostic_description)','diag description');
  t=once(t,'putExtra(Intent.EXTRA_SUBJECT, AppLanguageManager.localizeUiText(context, "FgoGotran 错误纪录"))','putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_subject))','diag subject');
  t=once(t,'Intent.createChooser(shareIntent, AppLanguageManager.localizeUiText(context, "分享错误纪录"))','Intent.createChooser(shareIntent, context.getString(R.string.diagnostic_share_title))','diag chooser');
  t=once(t,'exportMessage = "已生成 TXT"','exportMessage = context.getString(R.string.diagnostic_txt_generated)','txt generated');
  t=once(t,'exportMessage = "导出失败"','exportMessage = context.getString(R.string.diagnostic_export_failed)','export failed');
  t=once(t,'Text("导出 TXT")','Text(stringResource(R.string.diagnostic_export_txt))','export txt');
  t=once(t,'exportMessage = "已清空"','exportMessage = context.getString(R.string.diagnostic_cleared)','cleared');
  t=once(t,'Text("清空")','Text(stringResource(R.string.diagnostic_clear))','clear');
  t=once(t,'"目前没有错误纪录。"','stringResource(R.string.diagnostic_empty)','empty diag');
  t=once(t,'private fun levelLabel(level: String): String {\n    return when (level) {\n        DiagnosticEventStore.LEVEL_ERROR -> "错误"\n        DiagnosticEventStore.LEVEL_WARNING -> "注意"\n        else -> "记录"\n    }\n}','@Composable\nprivate fun levelLabel(level: String): String {\n    return when (level) {\n        DiagnosticEventStore.LEVEL_ERROR -> stringResource(R.string.diagnostic_error)\n        DiagnosticEventStore.LEVEL_WARNING -> stringResource(R.string.diagnostic_note)\n        else -> stringResource(R.string.diagnostic_record)\n    }\n}','level label');
  write(p,t);
}
console.log('update/history/notification/diagnostic resource refactor applied');

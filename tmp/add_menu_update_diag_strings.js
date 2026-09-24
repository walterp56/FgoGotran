const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': {
    menu_log: '翻译LOG', menu_area: '区域翻译', menu_battle: 'BATTLE', menu_live: '实时字幕', menu_manual: '手动', menu_semi: '半自动', menu_auto: '全自动', menu_close: '关闭服务',
    mode_glyph_manual: 'GO', mode_glyph_semi: '半', mode_glyph_auto: '全', mode_glyph_battle: '戰',
    history_empty: '暂无翻译LOG。', notification_running: '翻译悬浮窗正在运行',
    update_title: '发现新版本', update_not_now: '暂不更新', update_now: '立即更新', update_skip_version: '此版本不再提醒', update_current_version: '当前版本：%1$s', update_latest_version: '最新版本：%1$s', update_release_date: '发布日期：%1$s', update_whats_new: '更新内容', update_open_download: '请前往下载页面查看更新内容。',
    diagnostic_title: '错误纪录', diagnostic_back: '返回', diagnostic_description: '自动显示全部最近问题：错误、权限阻塞、资料更新失败、缺少语音档案、临时语音 API 建立结果。', diagnostic_export_txt: '导出 TXT', diagnostic_share_title: '分享错误纪录', diagnostic_subject: 'FgoGotran 错误纪录', diagnostic_txt_generated: '已生成 TXT', diagnostic_export_failed: '导出失败', diagnostic_cleared: '已清空', diagnostic_clear: '清空', diagnostic_empty: '目前没有错误纪录。', diagnostic_error: '错误', diagnostic_note: '注意', diagnostic_record: '记录'
  },
  'app/src/main/res/values-b+zh+Hant/strings.xml': {
    menu_log: '翻譯LOG', menu_area: '區域翻譯', menu_battle: 'BATTLE', menu_live: '即時字幕', menu_manual: '手動', menu_semi: '半自動', menu_auto: '全自動', menu_close: '關閉服務',
    mode_glyph_manual: 'GO', mode_glyph_semi: '半', mode_glyph_auto: '全', mode_glyph_battle: '戰',
    history_empty: '暫無翻譯LOG。', notification_running: '翻譯懸浮視窗正在運行',
    update_title: '發現新版本', update_not_now: '暫不更新', update_now: '立即更新', update_skip_version: '此版本不再提醒', update_current_version: '目前版本：%1$s', update_latest_version: '最新版本：%1$s', update_release_date: '發佈日期：%1$s', update_whats_new: '更新內容', update_open_download: '請前往下載頁面查看更新內容。',
    diagnostic_title: '錯誤紀錄', diagnostic_back: '返回', diagnostic_description: '自動顯示全部最近問題：錯誤、權限阻塞、資料更新失敗、缺少語音檔案、臨時語音 API 建立結果。', diagnostic_export_txt: '匯出 TXT', diagnostic_share_title: '分享錯誤紀錄', diagnostic_subject: 'FgoGotran 錯誤紀錄', diagnostic_txt_generated: '已生成 TXT', diagnostic_export_failed: '匯出失敗', diagnostic_cleared: '已清空', diagnostic_clear: '清空', diagnostic_empty: '目前沒有錯誤紀錄。', diagnostic_error: '錯誤', diagnostic_note: '注意', diagnostic_record: '紀錄'
  },
  'app/src/main/res/values-en/strings.xml': {
    menu_log: 'Log', menu_area: 'Area', menu_battle: 'Battle', menu_live: 'Live', menu_manual: 'Manual', menu_semi: 'Semi', menu_auto: 'Auto', menu_close: 'Close',
    mode_glyph_manual: 'GO', mode_glyph_semi: 'S', mode_glyph_auto: 'A', mode_glyph_battle: 'B',
    history_empty: 'No log yet.', notification_running: 'Translation overlay is running',
    update_title: 'Update available', update_not_now: 'Not now', update_now: 'Update now', update_skip_version: 'Skip this version', update_current_version: 'Current version: %1$s', update_latest_version: 'Latest version: %1$s', update_release_date: 'Release date: %1$s', update_whats_new: "What's new", update_open_download: 'Open the download page to see what is new.',
    diagnostic_title: 'Diagnostic log', diagnostic_back: 'Back', diagnostic_description: 'Shows recent issues: errors, permission blocks, data update failures, missing voice profiles, and temporary voice API setup results.', diagnostic_export_txt: 'Export TXT', diagnostic_share_title: 'Share diagnostic log', diagnostic_subject: 'FgoGotran diagnostic log', diagnostic_txt_generated: 'TXT generated', diagnostic_export_failed: 'Export failed', diagnostic_cleared: 'Cleared', diagnostic_clear: 'Clear', diagnostic_empty: 'No diagnostic events yet.', diagnostic_error: 'Error', diagnostic_note: 'Warning', diagnostic_record: 'Info'
  }
};
function xmlEscape(value) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '\\"'); }
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = Object.entries(entries).map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('menu/update/diagnostic strings added');

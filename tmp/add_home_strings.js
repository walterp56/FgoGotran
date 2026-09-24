const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': {
    home_start_service: '启动服务', home_stop_service: '停止服务',
    home_accessibility_connected: '已连接', home_accessibility_enabled_not_connected: '已开启，未连接', home_accessibility_disabled: '未启用', home_accessibility_unknown: '状态无法确认',
    home_accessibility_label: '无障碍服务', home_action_settings: '设置 →', home_overlay_label: '显示在其他应用上层', home_granted: '已授权', home_not_granted: '未授权', home_action_grant: '去授权 →',
    home_optional_stability: '可选稳定性（非必要）', home_battery_optimization: '电池优化', home_battery_optimization_off: '已关闭优化', home_battery_optimization_on: '优化中', home_action_manage: '去管理 →',
    home_settings: '设置', home_guide: '使用指南', home_language: '语言', home_server_title: 'FGO 服务器', home_server_jp: '日服', home_server_cn: '简中服', home_server_tw: '繁中服',
    home_current: '当前', home_mode: '模式', home_mode_translate_voice: '翻译 + 朗读', home_mode_voice: '朗读',
    home_agree_open_settings: '我同意并前往设置', home_cancel: '取消', home_go_settings: '去设置', home_close: '关闭'
  },
  'app/src/main/res/values-b+zh+Hant/strings.xml': {
    home_start_service: '啟動服務', home_stop_service: '停止服務',
    home_accessibility_connected: '已連線', home_accessibility_enabled_not_connected: '已開啟，未連線', home_accessibility_disabled: '未啟用', home_accessibility_unknown: '狀態無法確認',
    home_accessibility_label: '無障礙服務', home_action_settings: '設定 →', home_overlay_label: '顯示在其他應用程式上層', home_granted: '已授權', home_not_granted: '未授權', home_action_grant: '去授權 →',
    home_optional_stability: '可選穩定性（非必要）', home_battery_optimization: '電池最佳化', home_battery_optimization_off: '已關閉最佳化', home_battery_optimization_on: '最佳化中', home_action_manage: '去管理 →',
    home_settings: '設定', home_guide: '使用指南', home_language: '語言', home_server_title: 'FGO 伺服器', home_server_jp: '日服', home_server_cn: '簡中服', home_server_tw: '繁中服',
    home_current: '目前', home_mode: '模式', home_mode_translate_voice: '翻譯 + 朗讀', home_mode_voice: '朗讀',
    home_agree_open_settings: '我同意並前往設定', home_cancel: '取消', home_go_settings: '去設定', home_close: '關閉'
  },
  'app/src/main/res/values-en/strings.xml': {
    home_start_service: 'Start service', home_stop_service: 'Stop service',
    home_accessibility_connected: 'Connected', home_accessibility_enabled_not_connected: 'Enabled, not connected', home_accessibility_disabled: 'Disabled', home_accessibility_unknown: 'Status unknown',
    home_accessibility_label: 'Accessibility', home_action_settings: 'Settings →', home_overlay_label: 'Display over other apps', home_granted: 'Granted', home_not_granted: 'Not granted', home_action_grant: 'Grant →',
    home_optional_stability: 'Optional stability', home_battery_optimization: 'Battery optimization', home_battery_optimization_off: 'Optimization off', home_battery_optimization_on: 'Optimization on', home_action_manage: 'Manage →',
    home_settings: 'Settings', home_guide: 'Guide', home_language: 'Language', home_server_title: 'FGO server', home_server_jp: 'JP', home_server_cn: 'CN', home_server_tw: 'TW',
    home_current: 'Current', home_mode: 'Mode', home_mode_translate_voice: 'Translate + voice', home_mode_voice: 'Voice',
    home_agree_open_settings: 'Agree, open Settings', home_cancel: 'Cancel', home_go_settings: 'Open Settings', home_close: 'Close'
  }
};
function xmlEscape(value) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = Object.entries(entries).map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('home strings added');

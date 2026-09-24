const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': [
    ['voice_test_voice_played', '测试语音已播放%1$s'],
    ['voice_azure_request_failed', 'Azure 语音请求失败：%1$s'],
    ['voice_test_failed_detail', '测试失败：%1$s']
  ],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [
    ['voice_test_voice_played', '測試語音已播放%1$s'],
    ['voice_azure_request_failed', 'Azure 語音請求失敗：%1$s'],
    ['voice_test_failed_detail', '測試失敗：%1$s']
  ],
  'app/src/main/res/values-en/strings.xml': [
    ['voice_test_voice_played', 'Test voice played%1$s'],
    ['voice_azure_request_failed', 'Azure voice request failed: %1$s'],
    ['voice_test_failed_detail', 'Test failed: %1$s']
  ]
};
function xmlEscape(value) { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '\\"').replace(/'/g, "\\'"); }
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = entries.map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('voice dynamic strings added');

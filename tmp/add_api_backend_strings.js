const fs = require('fs');
const files = {
  'app/src/main/res/values/strings.xml': [
    ['api_backend_zhipu', '智谱 GLM'],
    ['api_backend_qwen', '阿里云百炼 Qwen'],
    ['api_backend_custom', '自定义 / 本地 AI']
  ],
  'app/src/main/res/values-b+zh+Hant/strings.xml': [
    ['api_backend_zhipu', '智譜 GLM'],
    ['api_backend_qwen', '阿里雲百鍊 Qwen'],
    ['api_backend_custom', '自訂 / 本機 AI']
  ],
  'app/src/main/res/values-en/strings.xml': [
    ['api_backend_zhipu', 'Zhipu GLM'],
    ['api_backend_qwen', 'Alibaba Qwen'],
    ['api_backend_custom', 'Custom / Local AI']
  ]
};
function xmlEscape(value) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
for (const [filePath, entries] of Object.entries(files)) {
  let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
  const block = entries.map(([key, value]) => `    <string name="${key}">${xmlEscape(value)}</string>`).join('\n');
  text = text.replace('</resources>', block + '\n</resources>');
  fs.writeFileSync(filePath, text, 'utf8');
}
console.log('api backend strings added');

const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}
function replaceAll(text, needle, replacement, expectedCount, label) {
  const count = text.split(needle).length - 1;
  if (count !== expectedCount) throw new Error(`${label}: expected ${expectedCount} occurrences, found ${count}`);
  return text.split(needle).join(replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    'private fun showAccessibilityDisclosure(context: Context) {\n',
    'private fun AlertDialog.Builder.setLocalizedTitle(\n    context: Context,\n    text: String\n): AlertDialog.Builder = setTitle(AppLanguageManager.localizeUiText(context, text))\n\nprivate fun AlertDialog.Builder.setLocalizedMessage(\n    context: Context,\n    text: String\n): AlertDialog.Builder = setMessage(AppLanguageManager.localizeUiText(context, text))\n\nprivate fun showAccessibilityDisclosure(context: Context) {\n',
    'HomeScreen localized dialog helpers'
  );

  text = replaceOnce(text, '.setTitle("无障碍服务用途说明")', '.setLocalizedTitle(context, "无障碍服务用途说明")', 'accessibility disclosure title');
  text = replaceOnce(text, '.setTitle("无障碍服务未连接")', '.setLocalizedTitle(context, "无障碍服务未连接")', 'accessibility not connected title');
  text = replaceOnce(text, '.setTitle("悬浮窗权限用途说明")', '.setLocalizedTitle(context, "悬浮窗权限用途说明")', 'overlay permission title');

  text = replaceAll(
    text,
    '.setMessage(\n            """',
    '.setLocalizedMessage(context, """',
    3,
    'localized dialog messages'
  );

  text = replaceAll(
    text,
    '.setPositiveButton("我同意并前往设置") {',
    '.setPositiveButton(AppLanguageManager.localizeUiText(context, "我同意并前往设置")) {',
    2,
    'agree buttons'
  );
  text = replaceOnce(
    text,
    '.setPositiveButton("去设置") {',
    '.setPositiveButton(AppLanguageManager.localizeUiText(context, "去设置")) {',
    'go settings button'
  );
  text = replaceAll(
    text,
    '.setNegativeButton("取消", null)',
    '.setNegativeButton(AppLanguageManager.localizeUiText(context, "取消"), null)',
    2,
    'cancel buttons'
  );
  text = replaceOnce(
    text,
    '.setNegativeButton("关闭", null)',
    '.setNegativeButton(AppLanguageManager.localizeUiText(context, "关闭"), null)',
    'close button'
  );

  write(path, text);
}

console.log('home warning dialogs localized');

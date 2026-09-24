const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/screen/HomeScreen.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    'private fun AlertDialog.Builder.setLocalizedTitle(\n    context: Context,\n    text: String\n): AlertDialog.Builder = setTitle(AppLanguageManager.localizeUiText(context, text))\n\nprivate fun AlertDialog.Builder.setLocalizedMessage(\n    context: Context,\n    text: String\n): AlertDialog.Builder = setMessage(AppLanguageManager.localizeUiText(context, text))\n\n',
    '',
    'remove localized dialog helpers'
  );

  text = replaceOnce(text, '.setLocalizedTitle(context, "无障碍服务用途说明")', '.setTitle(context.getString(R.string.accessibility_disclosure_title))', 'disclosure title');
  text = replaceOnce(text, '.setLocalizedTitle(context, "无障碍服务未连接")', '.setTitle(context.getString(R.string.accessibility_not_connected_title))', 'not connected title');
  text = replaceOnce(text, '.setLocalizedTitle(context, "悬浮窗权限用途说明")', '.setTitle(context.getString(R.string.overlay_permission_title))', 'overlay title');

  const messageResources = [
    'accessibility_disclosure_message',
    'accessibility_not_connected_message',
    'overlay_permission_message'
  ];
  let messageIndex = 0;
  text = text.replace(/\.setLocalizedMessage\(context, """[\s\S]*?"""\.trimIndent\(\)\s*\)/g, () => {
    const resource = messageResources[messageIndex++];
    return `.setMessage(context.getString(R.string.${resource}))`;
  });
  if (messageIndex !== messageResources.length) {
    throw new Error(`expected ${messageResources.length} message blocks, replaced ${messageIndex}`);
  }

  write(path, text);
}

console.log('warning dialog resources applied');

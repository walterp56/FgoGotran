const fs = require('fs');
const p = 'app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt';
let t = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
function once(needle, replacement, label) {
  const c = t.split(needle).length - 1;
  if (c !== 1) throw new Error(`${label}: expected 1, found ${c}`);
  t = t.replace(needle, replacement);
}
once('playerNameSaveMessage = stringResource(R.string.settings_auto_42)', 'playerNameSaveMessage = context.getString(R.string.settings_auto_42)', 'save player');
once('cacheClearMessage = stringResource(R.string.settings_auto_17)', 'cacheClearMessage = context.getString(R.string.settings_auto_17)', 'clear cache fail');
once('debugLogMessage = if (enabled) stringResource(R.string.settings_auto_13) else stringResource(R.string.settings_auto_14)', 'debugLogMessage = if (enabled) context.getString(R.string.settings_auto_13) else context.getString(R.string.settings_auto_14)', 'debug log');
once('stringResource(R.string.settings_auto_15)\n            } else {\n                stringResource(R.string.settings_auto_10)\n            }', 'context.getString(R.string.settings_auto_15)\n            } else {\n                context.getString(R.string.settings_auto_10)\n            }', 'foreground test');
once('private fun floatingButtonSizeLabel(sizeDp: Int): String {', '@Composable\nprivate fun floatingButtonSizeLabel(sizeDp: Int): String {', 'floating label composable');
fs.writeFileSync(p, t, 'utf8');
console.log('settings compose call fixes applied');

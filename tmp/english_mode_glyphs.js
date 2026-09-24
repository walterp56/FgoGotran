const fs = require('fs');
const filePath = 'app/src/main/java/com/fgogotran/ui/overlay/FloatingButton.kt';
let text = fs.readFileSync(filePath, 'utf8').replace(/\r\n/g, '\n');
function replaceOnce(needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}
text = replaceOnce(
  'import androidx.compose.foundation.BorderStroke\n',
  'import android.content.Context\nimport androidx.compose.foundation.BorderStroke\n',
  'import Context'
);
text = replaceOnce(
  'import com.fgogotran.localization.LocalizedText as Text\n',
  'import com.fgogotran.localization.AppLanguageManager\nimport com.fgogotran.localization.LocalizedText as Text\n',
  'import AppLanguageManager'
);
text = replaceOnce(
  'internal fun FloatingActionIcon.textLabel(): String = when (this) {\n    FloatingActionIcon.GO -> "GO"\n    FloatingActionIcon.SEMI -> "半"\n    FloatingActionIcon.AUTO -> "全"\n    FloatingActionIcon.BATTLE -> "戰"\n    else -> error("$this is not a text action")\n}\n',
  'internal fun FloatingActionIcon.textLabel(context: Context): String {\n    val english = AppLanguageManager.effectiveLanguageTag(context) == AppLanguageManager.LANGUAGE_ENGLISH\n    return when (this) {\n        FloatingActionIcon.GO -> "GO"\n        FloatingActionIcon.SEMI -> if (english) "S" else "半"\n        FloatingActionIcon.AUTO -> if (english) "A" else "全"\n        FloatingActionIcon.BATTLE -> if (english) "B" else "戰"\n        else -> error("$this is not a text action")\n    }\n}\n',
  'language-aware glyph labels'
);
text = replaceOnce(
  '    when (icon) {\n        FloatingActionIcon.GO,\n',
  '    val context = androidx.compose.ui.platform.LocalContext.current\n    when (icon) {\n        FloatingActionIcon.GO,\n',
  'glyph context'
);
text = replaceOnce(
  '                text = icon.textLabel(),\n',
  '                text = icon.textLabel(context),\n',
  'glyph label call'
);
fs.writeFileSync(filePath, text, 'utf8');
console.log('English mode glyphs applied');

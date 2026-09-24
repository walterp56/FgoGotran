const fs = require('fs');
const p='app/src/main/java/com/fgogotran/ui/component/LanguagePickerDialog.kt';
let t=fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n');
function once(n,r,l){const c=t.split(n).length-1;if(c!==1)throw new Error(`${l}: ${c}`);t=t.replace(n,r);}
t=once('import androidx.compose.ui.platform.LocalContext\n','','remove LocalContext');
t=once('import androidx.compose.ui.text.font.FontWeight\n','import androidx.compose.ui.res.stringResource\nimport androidx.compose.ui.text.font.FontWeight\n','stringResource import');
t=once('import com.fgogotran.localization.AppLanguageManager\n','import com.fgogotran.R\nimport com.fgogotran.localization.AppLanguageManager\n','R import');
t=once('    val context = LocalContext.current\n','','context val');
t=t.split('AppLanguageManager.localizeUiText(context, "系统默认")').join('stringResource(R.string.ui_language_system)');
t=t.split('AppLanguageManager.localizeUiText(context, "跟随系统设置")').join('stringResource(R.string.ui_language_follow_system)');
t=t.split('AppLanguageManager.localizeUiText(context, "语言")').join('stringResource(R.string.ui_language_title)');
t=t.split('AppLanguageManager.localizeUiText(context, "关闭")').join('stringResource(R.string.ui_language_close)');
fs.writeFileSync(p,t,'utf8');
console.log('language picker cleaned');

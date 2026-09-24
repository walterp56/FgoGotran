const fs = require('fs');
function read(p){return fs.readFileSync(p,'utf8').replace(/\r\n/g,'\n');}
function write(p,t){fs.writeFileSync(p,t,'utf8');}
function once(source,needle,replacement,label){const c=source.split(needle).length-1;if(c!==1)throw new Error(`${label}: expected 1, found ${c}`);return source.replace(needle,replacement);}
{
  const p='app/src/main/java/com/fgogotran/localization/AppLanguageManager.kt';
  let t=read(p);
  t=once(t,'    fun recreateActivity(context: Context) {','    fun localizedString(context: Context, resId: Int, vararg formatArgs: Any): String =\n        localizeUiText(context, context.getString(resId, *formatArgs))\n\n    fun recreateActivity(context: Context) {','localizedString helper');
  write(p,t);
}
{
  const p='app/src/main/java/com/fgogotran/ui/screen/SettingsScreen.kt';
  let t=read(p);
  if(!t.includes('import com.fgogotran.localization.AppLanguageManager')) {
    t=once(t,'import com.fgogotran.data.SettingsRepository\n','import com.fgogotran.data.SettingsRepository\nimport com.fgogotran.localization.AppLanguageManager\n','settings import');
  }
  t=t.split('context.getString(R.string.settings_auto_').join('AppLanguageManager.localizedString(context, R.string.settings_auto_');
  write(p,t);
}
{
  const p='app/src/main/java/com/fgogotran/ui/screen/VoiceSettingsScreen.kt';
  let t=read(p);
  if(!t.includes('import com.fgogotran.localization.AppLanguageManager')) {
    t=once(t,'import com.fgogotran.data.SettingsRepository\n','import com.fgogotran.data.SettingsRepository\nimport com.fgogotran.localization.AppLanguageManager\n','voice import');
  }
  t=t.split('context.getString(R.string.voice_auto_').join('AppLanguageManager.localizedString(context, R.string.voice_auto_');
  write(p,t);
}
console.log('dynamic Traditional wrapping applied');

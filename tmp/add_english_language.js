const fs = require('fs');
function read(path) { return fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n'); }
function write(path, text) { fs.writeFileSync(path, text, 'utf8'); }
function replaceOnce(text, needle, replacement, label) {
  const count = text.split(needle).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 occurrence, found ${count}`);
  return text.replace(needle, replacement);
}

{
  const path = 'app/src/main/java/com/fgogotran/localization/AppLanguageManager.kt';
  let text = read(path);

  text = replaceOnce(
    text,
    '    const val LANGUAGE_TRADITIONAL = "zh-Hant"\n    const val LANGUAGE_SIMPLIFIED = "zh-Hans"\n',
    '    const val LANGUAGE_TRADITIONAL = "zh-Hant"\n    const val LANGUAGE_SIMPLIFIED = "zh-Hans"\n    const val LANGUAGE_ENGLISH = "en"\n',
    'AppLanguageManager English constant'
  );
  text = replaceOnce(
    text,
    '        LANGUAGE_SYSTEM,\n        LANGUAGE_TRADITIONAL,\n        LANGUAGE_SIMPLIFIED\n    )\n',
    '        LANGUAGE_SYSTEM,\n        LANGUAGE_TRADITIONAL,\n        LANGUAGE_SIMPLIFIED,\n        LANGUAGE_ENGLISH\n    )\n',
    'AppLanguageManager supported languages'
  );
  text = replaceOnce(
    text,
    '            LANGUAGE_TRADITIONAL -> toTraditional(text)\n            LANGUAGE_SIMPLIFIED -> toSimplified(text)\n            else -> text\n',
    '            LANGUAGE_TRADITIONAL -> toTraditional(text)\n            LANGUAGE_SIMPLIFIED -> toSimplified(text)\n            LANGUAGE_ENGLISH -> EnglishUiText.translate(text) ?: text\n            else -> text\n',
    'AppLanguageManager English localization'
  );
  text = replaceOnce(
    text,
    '        if (!locale.language.equals("zh", ignoreCase = true)) {\n            return LANGUAGE_SIMPLIFIED\n        }\n',
    '        if (locale.language.equals("en", ignoreCase = true)) {\n            return LANGUAGE_ENGLISH\n        }\n        if (!locale.language.equals("zh", ignoreCase = true)) {\n            return LANGUAGE_SIMPLIFIED\n        }\n',
    'AppLanguageManager system English resolution'
  );

  write(path, text);
}

{
  const path = 'app/src/main/java/com/fgogotran/ui/component/LanguagePickerDialog.kt';
  let text = read(path);
  text = replaceOnce(
    text,
    '        AppLanguageDialogOption(\n            language = AppLanguageManager.LANGUAGE_TRADITIONAL,\n            label = "繁體中文",\n            description = ""\n        ),\n',
    '        AppLanguageDialogOption(\n            language = AppLanguageManager.LANGUAGE_ENGLISH,\n            label = "English",\n            description = ""\n        ),\n        AppLanguageDialogOption(\n            language = AppLanguageManager.LANGUAGE_TRADITIONAL,\n            label = "繁體中文",\n            description = ""\n        ),\n',
    'LanguagePickerDialog English option'
  );
  write(path, text);
}

console.log('English language wired');

const fs = require('fs');
const p = 'app/src/main/java/com/fgogotran/overlay/OverlayRenderer.kt';
let t = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
function once(needle, replacement, label) {
  const c = t.split(needle).length - 1;
  if (c !== 1) throw new Error(`${label}: expected 1, found ${c}`);
  return t.replace(needle, replacement);
}
t = once(
  '        private const val DIALOGUE_TEXT_TOP_INSET = 48f\n',
  '        private const val DIALOGUE_TEXT_TOP_INSET = 48f\n        private const val DIALOGUE_TEXT_TOP_NUDGE_PX = 5f\n',
  'top nudge constant'
);
t = once(
  '            panelBox.top + DIALOGUE_TEXT_TOP_INSET * scale,',
  '            panelBox.top + DIALOGUE_TEXT_TOP_INSET * scale - DIALOGUE_TEXT_TOP_NUDGE_PX,',
  'fixed dialogue top nudge'
);
fs.writeFileSync(p, t, 'utf8');
console.log('dialogue top nudge applied');

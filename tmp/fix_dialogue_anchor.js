const fs = require('fs');
const p = 'app/src/main/java/com/fgogotran/overlay/OverlayRenderer.kt';
let t = fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
function once(needle, replacement, label) {
  const c = t.split(needle).length - 1;
  if (c !== 1) throw new Error(`${label}: expected 1, found ${c}`);
  return t.replace(needle, replacement);
}
t = once(
  '        val sourceLeft = originalBounds?.left?.minus(sourceLeftPaddingX) ?: textArea.left\n        val sourceTop = originalBounds?.top?.minus(sourcePaddingY) ?: textArea.top\n        val sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: textArea.left\n        val sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: textArea.top\n        val clearRightForRiskyTail = if (instruction.hasRiskyTrailingDialogueText()) {\n            textArea.right\n        } else {\n            textArea.left\n        }\n\n        return boundedRect(\n            left = minOf(sourceLeft, textArea.left - clearLeftInsetX),\n            top = minOf(sourceTop, textArea.top - clearInsetY) - 12f * scale,\n            right = maxOf(sourceRight, textArea.left + textWidth + clearInsetX, clearRightForRiskyTail),\n            bottom = maxOf(sourceBottom, textBottom + clearInsetY),\n            bounds = panelBox\n        )\n',
  '        val anchorLeft = textArea.left - clearLeftInsetX\n        val anchorTop = textArea.top - clearInsetY - 12f * scale\n        val sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: anchorLeft\n        val sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: anchorTop\n        val clearRightForRiskyTail = if (instruction.hasRiskyTrailingDialogueText()) {\n            textArea.right\n        } else {\n            anchorLeft\n        }\n\n        return boundedRect(\n            left = anchorLeft,\n            top = anchorTop,\n            right = maxOf(sourceRight, textArea.left + textWidth + clearInsetX, clearRightForRiskyTail),\n            bottom = maxOf(sourceBottom, textBottom + clearInsetY),\n            bounds = panelBox\n        )\n',
  'fixed dialogue anchor'
);
fs.writeFileSync(p, t, 'utf8');
console.log('dialogue anchor fix applied');

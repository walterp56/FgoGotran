const choice = /^？([1-6])：(.*)$/;
const boundary = /^\[(?:k|messageOff|end)\]$/;
const standaloneCommand = /^(?:\[[a-z][a-z0-9]*(?: [^\[\]\r\n]*)?\])+$/i;
const visibleTag = /\[(?:#[^\[\]]+|%[0-9]+|&[^\[\]]+|r|sr)\]/;
const bracketTag = /\[([^\[\]\r\n]*)\]/g;
// Atlas raw scripts place presentation commands inline in dialogue text
// (for example [messageShake 0.03 4 4 0.7]) and append spot metadata to
// speaker lines (for example ＠長州藩兵=spot[U,M]). Commands change the stage,
// not the visible text, so they are dropped; any other unknown tag still fails.
const inlineCommand = /^[A-Za-z][A-Za-z0-9]*(?: [^\[\]]*)?$/;
const speakerSpot = /=spot\[[^\[\]]*\]$/;
// Narration boxes without a speaker line are opened by [backlogStart] and
// closed by the following [k]. Emphasis is written as [#word] (no colon).
const narrationStart = '[backlogStart]';
const emphasis = /^#([^:]+)$/;

function displayText(text, fail) {
  if (/[\[\]]/.test(text.replace(bracketTag, ''))) fail('unclosed or nested bracket tag');
  return text.replace(bracketTag, (tag, value) => {
    const ruby = /^#([^:]+):(.+)$/.exec(value);
    if (ruby) return ruby[1] + '〈' + ruby[2] + '〉';
    const mark = emphasis.exec(value);
    if (mark && !/^[0-9a-f]{6}$/i.test(mark[1])) return '⟦' + mark[1] + '⟧';
    if (/^(?:%[0-9]+|&[^:]+:.+)$/.test(value)) return tag;
    if (value === 'r' || value === 'sr') return '\n';
    // `[line N]` is a draw command: the game (and the Atlas viewer) paints a
    // horizontal rule N character-widths long. Store it as the visible bar so the
    // table and the translations keep the separator; matching strips it again.
    const lineMark = /^line ([0-9]+)$/i.exec(value);
    if (lineMark) return '―'.repeat(Number(lineMark[1]));
    if (/^(?:[0-9a-f]{6}|-)$/i.test(value)) return '';
    if (inlineCommand.test(value)) return '';
    fail('unsupported inline bracket tag');
  });
}

// This parser handles the line grammar exercised by the Story Library fixtures.
// Script commands that affect a visible box need evidence before conversion.
export function parseJapaneseScript(scriptId, source) {
  const lines = source.split(/\r\n?|\n/);
  const opening = lines.find((line) => line.trim());
  if (!opening?.startsWith('＄') && opening !== '[soundStopAll]') {
    throw new Error(scriptId + ': unsupported script opening');
  }
  const entries = [];
  const selections = [];
  let lineNumber = 0;
  let box = null;
  const fail = (problem) => {
    throw new Error(scriptId + ':' + lineNumber + ': ' + problem);
  };

  const branchPath = () => selections.map(({ group, slot }) => group + '.' + slot).join('/');

  function closeBox() {
    if (box?.parts.length) {
      entries.push({
        line: box.line,
        type: 'dialogue',
        name: box.name,
        dialog: box.parts.join('\n'),
        branch: box.branch,
      });
    }
    box = null;
  }

  for (const line of lines) {
    lineNumber++;
    if (!line.trim() || line.startsWith('//')) continue;
    if (line.startsWith('＄') || boundary.test(line)) {
      closeBox();
      continue;
    }
    if (line === narrationStart) {
      closeBox();
      box = { line: lineNumber, name: '', parts: [], branch: branchPath() };
      continue;
    }
    if (line.startsWith('＠')) {
      closeBox();
      box = {
        line: lineNumber,
        name: displayText(line.slice(1).replace(/^[A-Z]：/, '').replace(speakerSpot, ''), fail),
        parts: [],
        branch: branchPath(),
      };
      continue;
    }
    if (line === '？！') {
      closeBox();
      if (!selections.pop()) fail('choice terminator without a group');
      continue;
    }
    const option = choice.exec(line);
    if (option) {
      closeBox();
      const slot = Number(option[1]);
      if (slot === 1) selections.push({ group: lineNumber, slot: 0 });
      const selection = selections.at(-1);
      if (!selection || slot !== selection.slot + 1) fail('unexpected choice slot');
      const dialog = displayText(option[2], fail);
      if (!dialog.trim()) fail('choice text is empty');
      selection.slot = slot;
      entries.push({
        line: lineNumber,
        type: 'choice',
        name: '',
        dialog,
        branch: branchPath(),
        choiceGroup: selection.group,
        choiceSlot: slot,
      });
      continue;
    }
    if (standaloneCommand.test(line) && !visibleTag.test(line)) {
      if (box && !/^(?:\[line [0-9]+\])+$/i.test(line)) {
        fail('unsupported command inside a dialogue box');
      }
      continue;
    }
    if (!box) fail('visible text outside a dialogue box');
    const visible = displayText(line, fail);
    if (visible) box.parts.push(visible);
  }
  closeBox();
  if (selections.length) fail('choice group is still open');
  return entries;
}

const columns = [
  'jp_name', 'jp_dialog', 'cn_name', 'cn_dialog',
  'tw_name', 'tw_dialog', 'en_name', 'en_dialog', 'type', 'script',
];

export function japaneseTsv(entries) {
  const field = (value) => /["\t\r\n]/.test(value)
    ? '"' + value.replaceAll('"', '""') + '"'
    : value;
  const rows = entries.map(({ name, dialog, type, script }) => [
    field(name), field(dialog), '', '', '', '', '', '', type, field(script ?? ''),
  ].join('\t'));
  return [columns.join('\t'), ...rows].join('\n') + '\n';
}

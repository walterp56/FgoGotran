/**
 * The dialogue size marker carried by a chapter pack row.
 *
 * The JP raw script writes the game's font-size command at the head of a line
 * (`[f large]`, `[f small]`). The converter turns that leading command into a
 * marker on the visible text, so the pack keeps the presentation without
 * keeping raw script syntax:
 *
 *   [f large][line 3]『…』  ->  ⟬L⟭―――『…』
 *
 * The marker lives only in the JP source column. Translation export strips it,
 * translated columns never carry it, and both this builder and the app remove
 * it before hashing or drawing text: it must never reach the screen and must
 * never change a lookup key.
 */

export const DIALOGUE_MARKERS = { large: '⟬L⟭', small: '⟬S⟭' };

const marker = /^⟬([LS])⟭/;

/** Text without the leading size marker. */
export function stripDialogueMarker(text) {
  return text.replace(marker, '');
}

/** `large`, `small`, or `normal` for stored row text. */
export function dialogueSizeOf(text) {
  const match = marker.exec(text);
  return match ? (match[1] === 'L' ? 'large' : 'small') : 'normal';
}

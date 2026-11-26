// Boxes on a page (fractions of the displayed page, from its top-left), as the window's text selection reports them.
import type { PageRect } from './pdf.ts';

const overlap = (a0: number, a1: number, b0: number, b1: number) => Math.min(a1, b1) - Math.max(a0, b0);

/**
 * Joins the boxes of a text selection into one box per line (or per column, for text that runs down a turned page).
 * A selection reports a fully selected span twice and splits words across spans; marking those boxes one by one
 * would darken the overlaps twice. Two boxes are joined when they share most of their height (or width) and the
 * gap between them along the line is smaller than half the text's size. Empty slivers are dropped.
 */
export function joinBoxes(boxes: PageRect[]): PageRect[] {
  const out = boxes.filter((b) => b.width > 1e-4 && b.height > 1e-4).map((b) => ({ ...b }));
  const joinable = (a: PageRect, b: PageRect) => {
    const size = Math.min(a.width, a.height, b.width, b.height);
    const shareY = overlap(a.y, a.y + a.height, b.y, b.y + b.height); // negative = the gap between them
    const shareX = overlap(a.x, a.x + a.width, b.x, b.x + b.width);
    const column = a.height > a.width && b.height > b.width; // text running down the screen
    // Lines: most of the height shared, close along the line. Never joins two lines of ordinary text.
    if (!column) return shareY > 0.5 * Math.min(a.height, b.height) && shareX > -0.5 * size;
    return shareX > 0.5 * Math.min(a.width, b.width) && shareY > -0.5 * size;
  };
  for (let joined = true; joined; ) {
    joined = false;
    for (let i = 0; i < out.length && !joined; i++) {
      for (let j = i + 1; j < out.length; j++) {
        if (!joinable(out[i], out[j])) continue;
        const [a, b] = [out[i], out[j]];
        const x = Math.min(a.x, b.x);
        const y = Math.min(a.y, b.y);
        out[i] = { x, y, width: Math.max(a.x + a.width, b.x + b.width) - x, height: Math.max(a.y + a.height, b.y + b.height) - y };
        out.splice(j, 1);
        joined = true;
        break;
      }
    }
  }
  return out.sort((a, b) => a.y - b.y || a.x - b.x);
}

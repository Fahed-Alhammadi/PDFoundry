// Boxes on a page (fractions of the displayed page, from its top-left), as the window's text selection reports them.
import type { PageRect } from './pdf.ts';

const overlap = (a0: number, a1: number, b0: number, b1: number) => Math.min(a1, b1) - Math.max(a0, b0);

/** Which way the text runs on the screen: along the line ('across'), or down it on a page turned a quarter. */
export type TextDirection = 'across' | 'down';

/**
 * Joins the boxes of a text selection into one box per line. A selection splits a line into several boxes (one per
 * span, sometimes the same text twice). The direction comes from the page and span rotation, never from a box's
 * shape: a single character or a list number is narrower than it is tall and must not be taken for a column.
 * Two boxes are joined only when they lie on the same line (they share most of the taller one's height, or width
 * for text running down) and the gap between them along the line is under one text height. Empty slivers are
 * dropped. The engine fills all boxes as one shape, so boxes that stay overlapping do not darken twice.
 */
export function joinBoxes(boxes: PageRect[], direction: TextDirection): PageRect[] {
  const out = boxes.filter((b) => b.width > 1e-4 && b.height > 1e-4).map((b) => ({ ...b }));
  const joinable = (a: PageRect, b: PageRect) => {
    const shareY = overlap(a.y, a.y + a.height, b.y, b.y + b.height); // negative = the gap between them
    const shareX = overlap(a.x, a.x + a.width, b.x, b.x + b.width);
    if (direction === 'across') return shareY > 0.5 * Math.max(a.height, b.height) && shareX > -Math.min(a.height, b.height);
    return shareX > 0.5 * Math.max(a.width, b.width) && shareY > -Math.min(a.width, b.width);
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

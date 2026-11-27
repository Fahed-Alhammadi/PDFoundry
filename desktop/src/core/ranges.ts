// Split PDF's choices, as page lists (0-based): the ranges the user types, and "every N pages". The same rules and
// messages as the phone apps.

export type Ranges = { parts: number[][] } | { error: string };

const EMPTY = 'Enter page ranges, such as 1-3, 4-6.';
const BACKWARDS = 'Write ranges from low to high, such as 3-5.';
const SYNTAX = 'Use numbers, dashes and commas, such as 1-3, 4-6.';

/**
 * Reads text such as "1-3, 4-6, 9": numbers and low-high ranges separated by commas, spaces ignored. Each piece is
 * one part. Parts may overlap. An empty piece (a trailing comma while typing) is skipped.
 */
export function parseRanges(text: string, pageCount: number): Ranges {
  const pieces = text.replace(/\s+/g, '').split(',');
  if (pieces.every((p) => p === '')) return { error: EMPTY };
  const parts: number[][] = [];
  for (const piece of pieces) {
    if (piece === '') continue;
    const match = /^(\d+)(?:-(\d+))?$/.exec(piece);
    if (!match) return { error: SYNTAX };
    const from = Number(match[1]);
    const to = match[2] === undefined ? from : Number(match[2]);
    for (const n of [from, to]) {
      if (n < 1 || n > pageCount) return { error: `Page ${n} doesn't exist. This PDF has ${pageCount === 1 ? '1 page' : `${pageCount} pages`}.` };
    }
    if (from > to) return { error: BACKWARDS };
    parts.push(Array.from({ length: to - from + 1 }, (_, i) => from - 1 + i));
  }
  return { parts };
}

/**
 * Export as Images: "<name> page 1.png" … for the chosen pages (0-based). With more than 9 pages in the document the
 * numbers are padded to the width of the page count ("page 01"), so the files sort in page order.
 */
export function exportNames(base: string, pages: number[], pageCount: number, extension: 'png' | 'jpg'): string[] {
  const width = pageCount > 9 ? String(pageCount).length : 1;
  return pages.map((p) => `${base} page ${String(p + 1).padStart(width, '0')}.${extension}`);
}

/** A page's picture size in pixels at `dpi` dots per inch (`size` in points, 72 to the inch). */
export function pixelSize(size: { width: number; height: number }, dpi: number): { width: number; height: number } {
  return { width: Math.round((size.width * dpi) / 72), height: Math.round((size.height * dpi) / 72) };
}

/** Consecutive parts of `n` pages; the last one may be shorter. Every page on its own is `everyN(count, 1)`. */
export function everyN(pageCount: number, n: number): number[][] {
  if (!Number.isInteger(n) || n < 1) throw new Error('Enter a number of pages of 1 or more.');
  const parts: number[][] = [];
  for (let start = 0; start < pageCount; start += n) {
    parts.push(Array.from({ length: Math.min(n, pageCount - start) }, (_, i) => start + i));
  }
  return parts;
}

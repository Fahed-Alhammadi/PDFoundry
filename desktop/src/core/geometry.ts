// Where things are on a page. The interface works in fractions of the page as it is displayed (its crop box, turned
// by its rotation), measured from the top-left corner: the same convention as the Android app's PdfEngine. A PDF
// stores everything in the page's own coordinates (points from the bottom-left of the unrotated page). These turn
// one into the other.
import type { PDFPage } from '@cantoo/pdf-lib';

/** A rectangle on the displayed page, as fractions (0…1) from its top-left corner. */
export type PageRect = { x: number; y: number; width: number; height: number };
/** A point on the displayed page, as fractions (0…1) from its top-left corner. */
export type PagePoint = { x: number; y: number };
/** A box in the page's own coordinates (points, from the bottom-left, unrotated). */
export type UserBox = { x: number; y: number; width: number; height: number };

/** The page's size as a reader shows it: its crop box, turned by its rotation. */
export function displayedSize(page: PDFPage): { width: number; height: number } {
  const box = page.getCropBox();
  return quarterTurns(page) % 2 ? { width: box.height, height: box.width } : { width: box.width, height: box.height };
}

export function quarterTurns(page: PDFPage): number {
  return ((Math.round(page.getRotation().angle / 90) % 4) + 4) % 4;
}

/** A point of the displayed page (fractions) in the page's own coordinates. */
export function userPoint(page: PDFPage, point: PagePoint): [number, number] {
  const box = page.getCropBox();
  const turns = quarterTurns(page);
  const shown = displayedSize(page);
  const dx = point.x * shown.width;
  const dy = point.y * shown.height;
  const [x, y] = turns === 0 ? [dx, box.height - dy] : turns === 1 ? [dy, dx] : turns === 2 ? [box.width - dx, dy] : [box.width - dy, box.height - dx];
  return [box.x + x, box.y + y];
}

/** A point in the page's own coordinates as a point of the displayed page (fractions): the reverse of `userPoint`. */
export function displayedPoint(page: PDFPage, px: number, py: number): PagePoint {
  const crop = page.getCropBox();
  const turns = quarterTurns(page);
  const shown = displayedSize(page);
  const [x, y] = [px - crop.x, py - crop.y];
  const [dx, dy] = turns === 0 ? [x, crop.height - y] : turns === 1 ? [y, x] : turns === 2 ? [crop.width - x, y] : [crop.height - y, crop.width - x];
  return { x: dx / shown.width, y: dy / shown.height };
}

/**
 * Where an upright image must be drawn so that it lands on `rect` of the displayed page.
 * Returns the unrotated page coordinates of the image's bottom-left corner, its size, and the angle to draw it at.
 */
export function placement(page: PDFPage, rect: PageRect): { x: number; y: number; width: number; height: number; angle: number } {
  const shown = displayedSize(page);
  const [x, y] = userPoint(page, { x: rect.x, y: rect.y + rect.height });
  return { x, y, width: rect.width * shown.width, height: rect.height * shown.height, angle: quarterTurns(page) * 90 };
}

/** `rect` of the displayed page as a box in the page's own coordinates (points, from the bottom-left, unrotated). */
export function userRect(page: PDFPage, rect: PageRect): UserBox {
  const [x1, y1] = userPoint(page, { x: rect.x, y: rect.y });
  const [x2, y2] = userPoint(page, { x: rect.x + rect.width, y: rect.y + rect.height });
  return { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
}

/** `box` (page coordinates, unrotated) as a rectangle of the displayed page: the reverse of `userRect`. */
export function displayedRect(page: PDFPage, box: UserBox): PageRect {
  const a = displayedPoint(page, box.x, box.y);
  const b = displayedPoint(page, box.x + box.width, box.y + box.height);
  return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), width: Math.abs(b.x - a.x), height: Math.abs(b.y - a.y) };
}

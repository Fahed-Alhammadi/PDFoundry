// Page Numbers and Watermark: drawn into each page's own content (not annotations), placed on the page as the reader
// sees it (its crop box and rotation), so they read the right way up on turned pages too. Undo and Revert take them
// away again while the document is open; they are not edited afterwards.
import { StandardFonts, degrees, rgb } from '@cantoo/pdf-lib';
import type { PDFDocument, PDFFont, PDFImage, PDFPage } from '@cantoo/pdf-lib';
import { displayedSize, quarterTurns, userPoint } from './geometry.ts';
import { PdfError, canEncode, isolate, open, save } from './pdf.ts';

export type NumberPosition = 'bottom-center' | 'bottom-right' | 'bottom-left' | 'top-center' | 'top-right' | 'top-left';
/** `1`, `Page 1`, `1 of 12`, `Page 1 of 12`. */
export type NumberFormat = 'n' | 'page-n' | 'n-of-total' | 'page-n-of-total';
export type PageNumberStyle = { position: NumberPosition; format: NumberFormat; startAt: number; skipFirst: boolean };

export type WatermarkColour = 'graphite' | 'ember';
export type WatermarkStyle = { text: string; opacity: number; diagonal: boolean; color: WatermarkColour };

export const NUMBER_POSITIONS: Record<NumberPosition, string> = {
  'bottom-center': 'Bottom centre', 'bottom-right': 'Bottom right', 'bottom-left': 'Bottom left',
  'top-center': 'Top centre', 'top-right': 'Top right', 'top-left': 'Top left',
};
export const NUMBER_FORMATS: Record<NumberFormat, string> = { n: '1', 'page-n': 'Page 1', 'n-of-total': '1 of 12', 'page-n-of-total': 'Page 1 of 12' };

const GRAPHITE = rgb(0x12 / 255, 0x14 / 255, 0x1c / 255);
const EMBER = rgb(0xe2 / 255, 0x51 / 255, 0x1b / 255);
/** Page numbers: 10 pt, half an inch from the edges of the page as shown. */
const NUMBER_SIZE = 10;
const MARGIN = 36;

/** The text of one page number. `total` is the last number shown. */
export function numberText(format: NumberFormat, n: number, total: number): string {
  return format === 'n' ? `${n}` : format === 'page-n' ? `Page ${n}` : format === 'n-of-total' ? `${n} of ${total}` : `Page ${n} of ${total}`;
}

/**
 * Draws text (or an image) upright on the page as shown: `at` is its starting point on the displayed page, in points
 * from its bottom-left corner (y up), and `angle` turns it counter-clockwise, in degrees, as seen on the screen.
 */
function shown(page: PDFPage, at: { x: number; y: number }, angle: number): { x: number; y: number; rotate: ReturnType<typeof degrees> } {
  const size = displayedSize(page);
  const [x, y] = userPoint(page, { x: at.x / size.width, y: 1 - at.y / size.height });
  return { x, y, rotate: degrees(angle + quarterTurns(page) * 90) };
}

/** Numbers every page (or every page but the first) with Helvetica, 10 pt, graphite. */
export async function addPageNumbers(bytes: Uint8Array, style: PageNumberStyle): Promise<Uint8Array> {
  const doc = await open(bytes);
  const first = style.skipFirst ? 1 : 0;
  const count = doc.getPageCount() - first;
  if (count < 1) throw new PdfError('There are no pages to number.');
  if (!Number.isInteger(style.startAt) || style.startAt < 0) throw new PdfError('Start at a whole number, such as 1.');
  const font = await doc.embedFont(StandardFonts.Helvetica);
  const total = style.startAt + count - 1;
  const cap = font.heightAtSize(NUMBER_SIZE, { descender: false });
  doc.getPages().forEach((page, index) => {
    if (index < first) return;
    const text = numberText(style.format, style.startAt + index - first, total);
    const size = displayedSize(page);
    const width = font.widthOfTextAtSize(text, NUMBER_SIZE);
    const [vertical, horizontal] = style.position.split('-');
    const x = horizontal === 'left' ? MARGIN : horizontal === 'right' ? size.width - MARGIN - width : (size.width - width) / 2;
    const y = vertical === 'bottom' ? MARGIN : size.height - MARGIN - cap; // the text's foot (or top) is 36 pt from the edge
    isolate(doc, page);
    page.drawText(text, { ...shown(page, { x, y }, 0), size: NUMBER_SIZE, font, color: GRAPHITE });
  });
  return save(doc);
}

/**
 * A watermark across every page: centred, about 60% of the page's width as shown, diagonal (bottom-left to top-right)
 * or across, on top of everything and see-through. Text the standard font cannot write (Arabic, say) needs `image`:
 * the text drawn by the window, as a PNG.
 */
export async function addWatermark(bytes: Uint8Array, style: WatermarkStyle, image?: Uint8Array): Promise<Uint8Array> {
  const text = style.text.trim();
  if (!text) throw new PdfError('Enter the watermark text.');
  if (!image && !canEncode(text)) throw new PdfError('This text needs to be drawn by the window first.');
  const opacity = Math.min(1, Math.max(0.05, style.opacity));
  const doc = await open(bytes);
  const picture = image ? await doc.embedPng(image) : null;
  const font = picture ? null : await doc.embedFont(StandardFonts.HelveticaBold);
  for (const page of doc.getPages()) {
    isolate(doc, page);
    drawMark(doc, page, text, style, opacity, font, picture);
  }
  return save(doc);
}

function drawMark(_doc: PDFDocument, page: PDFPage, text: string, style: WatermarkStyle, opacity: number, font: PDFFont | null, picture: PDFImage | null): void {
  const size = displayedSize(page);
  const theta = style.diagonal ? Math.atan2(size.height, size.width) : 0;
  const width = size.width * 0.6;
  const points = font ? width / font.widthOfTextAtSize(text, 1) : 0;
  const height = font ? font.heightAtSize(points, { descender: false }) : (width * picture!.height) / picture!.width;
  // The starting corner, so that the middle of the text lands on the middle of the page.
  const [u, v] = [width / 2, height / 2];
  const at = {
    x: size.width / 2 - (u * Math.cos(theta) - v * Math.sin(theta)),
    y: size.height / 2 - (u * Math.sin(theta) + v * Math.cos(theta)),
  };
  const place = shown(page, at, (theta * 180) / Math.PI);
  const color = style.color === 'ember' ? EMBER : GRAPHITE;
  if (font) page.drawText(text, { ...place, size: points, font, color, opacity });
  else page.drawImage(picture!, { ...place, width, height, opacity });
}

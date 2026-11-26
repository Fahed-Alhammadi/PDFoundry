// The PDF engine: every change PDFoundry makes to a document. Pure functions from bytes to bytes (no Electron, no
// DOM), so they run in the window and in the tests (test/pdf.test.ts).
//
// Edits are drawn into the page itself, so they look the same in every other PDF reader. Positions are fractions of
// the page as it is displayed (after its rotation), measured from the top-left corner: the same convention as the
// Android app's PdfEngine.
import { BlendMode, PDFDocument, PDFPage, degrees } from '@cantoo/pdf-lib';

export class PdfError extends Error {}

/** A rectangle on the displayed page, as fractions (0…1) from its top-left corner. */
export type PageRect = { x: number; y: number; width: number; height: number };

export type StampOptions = {
  /** Darkens what is underneath instead of covering it (the highlighter). */
  multiply?: boolean;
  opacity?: number;
};

export type SourceImage = { bytes: Uint8Array; type: 'png' | 'jpeg' };

const LETTER: [number, number] = [612, 792];

async function open(bytes: Uint8Array): Promise<PDFDocument> {
  try {
    return await PDFDocument.load(bytes, { updateMetadata: false });
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new PdfError(/encrypt/i.test(message) ? 'This PDF is protected, so it can only be read.' : 'This file is not a PDF that PDFoundry can read.');
  }
}

function save(doc: PDFDocument): Promise<Uint8Array> {
  return doc.save({ useObjectStreams: true });
}

function check(doc: PDFDocument, pages: number[]): number[] {
  const count = doc.getPageCount();
  const unique = [...new Set(pages)];
  for (const p of unique) if (!Number.isInteger(p) || p < 0 || p >= count) throw new PdfError(`There is no page ${p + 1}.`);
  return unique;
}

/** Can this file be changed? Protected PDFs cannot: saving them would drop their protection. */
export async function isEditable(bytes: Uint8Array): Promise<boolean> {
  try {
    await PDFDocument.load(bytes, { updateMetadata: false });
    return true;
  } catch {
    return false;
  }
}

export async function pageCount(bytes: Uint8Array): Promise<number> {
  return (await open(bytes)).getPageCount();
}

/** Joins documents, in the order given, into one. */
export async function merge(sources: Uint8Array[]): Promise<Uint8Array> {
  if (sources.length < 2) throw new PdfError('Choose two or more documents to merge.');
  const out = await PDFDocument.create();
  for (const bytes of sources) {
    const doc = await open(bytes);
    for (const page of await out.copyPages(doc, doc.getPageIndices())) out.addPage(page);
  }
  return save(out);
}

/** A new document with only `pages`, in the order given. */
export async function extract(bytes: Uint8Array, pages: number[]): Promise<Uint8Array> {
  const doc = await open(bytes);
  const chosen = check(doc, pages);
  if (!chosen.length) throw new PdfError('Select one or more pages first.');
  const out = await PDFDocument.create();
  for (const page of await out.copyPages(doc, chosen)) out.addPage(page);
  return save(out);
}

export async function movePage(bytes: Uint8Array, from: number, to: number): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [from, to]);
  if (from === to) return bytes;
  const [page] = await doc.copyPages(doc, [from]);
  doc.removePage(from);
  doc.insertPage(to, page);
  return save(doc);
}

/** Turns pages by a multiple of 90 degrees (positive = clockwise). */
export async function rotatePages(bytes: Uint8Array, pages: number[], by: number): Promise<Uint8Array> {
  if (by % 90 !== 0) throw new PdfError('Pages turn in quarter turns.');
  const doc = await open(bytes);
  for (const index of check(doc, pages)) {
    const page = doc.getPage(index);
    page.setRotation(degrees((((page.getRotation().angle + by) % 360) + 360) % 360));
  }
  return save(doc);
}

/** Puts a copy of each chosen page right after it. */
export async function duplicatePages(bytes: Uint8Array, pages: number[]): Promise<Uint8Array> {
  const doc = await open(bytes);
  const chosen = check(doc, pages).sort((a, b) => b - a); // from the end, so earlier indices stay valid
  for (const index of chosen) {
    const [copy] = await doc.copyPages(doc, [index]);
    doc.insertPage(index + 1, copy);
  }
  return save(doc);
}

export async function deletePages(bytes: Uint8Array, pages: number[]): Promise<Uint8Array> {
  const doc = await open(bytes);
  const chosen = check(doc, pages).sort((a, b) => b - a);
  if (chosen.length >= doc.getPageCount()) throw new PdfError('A document needs at least one page.');
  for (const index of chosen) doc.removePage(index);
  return save(doc);
}

/** Adds an empty page after `after` (-1 = at the start), the same size as its neighbour. */
export async function addBlankPage(bytes: Uint8Array, after: number): Promise<Uint8Array> {
  const doc = await open(bytes);
  const count = doc.getPageCount();
  if (!Number.isInteger(after) || after < -1 || after >= count) throw new PdfError(`There is no page ${after + 1}.`);
  const neighbour = count ? doc.getPage(Math.max(after, 0)) : null;
  const size = neighbour ? displayedSize(neighbour) : { width: LETTER[0], height: LETTER[1] };
  doc.insertPage(after + 1, [size.width, size.height]);
  return save(doc);
}

/** The page's size as a reader shows it: its crop box, turned by its rotation. */
export function displayedSize(page: PDFPage): { width: number; height: number } {
  const box = page.getCropBox();
  return quarterTurns(page) % 2 ? { width: box.height, height: box.width } : { width: box.width, height: box.height };
}

function quarterTurns(page: PDFPage): number {
  return ((Math.round(page.getRotation().angle / 90) % 4) + 4) % 4;
}

/**
 * Where an upright image must be drawn so that it lands on `rect` of the displayed page.
 * Returns the unrotated page coordinates of the image's bottom-left corner, its size, and the angle to draw it at.
 */
export function placement(page: PDFPage, rect: PageRect): { x: number; y: number; width: number; height: number; angle: number } {
  const box = page.getCropBox();
  const turns = quarterTurns(page);
  const shown = displayedSize(page);
  // The image's bottom-left corner on the displayed page, in points from its top-left corner.
  const dx = rect.x * shown.width;
  const dy = (rect.y + rect.height) * shown.height;
  let x: number;
  let y: number;
  if (turns === 0) [x, y] = [dx, box.height - dy];
  else if (turns === 1) [x, y] = [dy, dx];
  else if (turns === 2) [x, y] = [box.width - dx, dy];
  else [x, y] = [box.width - dy, box.height - dx];
  return { x: box.x + x, y: box.y + y, width: rect.width * shown.width, height: rect.height * shown.height, angle: turns * 90 };
}

/** Draws a PNG onto a page: a signature, typed text, a drawing or a highlight. */
export async function stamp(bytes: Uint8Array, pageIndex: number, png: Uint8Array, rect: PageRect, options: StampOptions = {}): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  if (!(rect.width > 0 && rect.height > 0)) throw new PdfError('There is nothing to place.');
  const page = doc.getPage(pageIndex);
  const image = await doc.embedPng(png);
  const at = placement(page, rect);
  page.drawImage(image, {
    x: at.x,
    y: at.y,
    width: at.width,
    height: at.height,
    rotate: degrees(at.angle),
    opacity: options.opacity ?? 1,
    blendMode: options.multiply ? BlendMode.Multiply : BlendMode.Normal,
  });
  return save(doc);
}

/** A new document with one page per image, each page the size of its image (at most A4-like 842 points long). */
export async function imagesToPdf(images: SourceImage[]): Promise<Uint8Array> {
  if (!images.length) throw new PdfError('Choose one or more images.');
  const doc = await PDFDocument.create();
  for (const source of images) {
    let image;
    try {
      image = source.type === 'png' ? await doc.embedPng(source.bytes) : await doc.embedJpg(source.bytes);
    } catch {
      throw new PdfError('One of the images could not be read.');
    }
    const scale = Math.min(1, 842 / Math.max(image.width, image.height));
    const width = image.width * scale;
    const height = image.height * scale;
    doc.addPage([width, height]).drawImage(image, { x: 0, y: 0, width, height });
  }
  return save(doc);
}

/** A one-page guide that the library shows on first launch. */
export async function welcomeGuide(): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const page = doc.addPage(LETTER);
  const { StandardFonts, rgb } = await import('@cantoo/pdf-lib');
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const regular = await doc.embedFont(StandardFonts.Helvetica);
  page.drawRectangle({ x: 0, y: 692, width: 612, height: 100, color: rgb(0.07, 0.08, 0.11) });
  page.drawText('Welcome to PDFoundry', { x: 56, y: 730, size: 26, font: bold, color: rgb(1, 1, 1) });
  const lines: [string, string][] = [
    ['Read', 'Open a PDF from the + button, or drop one onto the window. Search it with the magnifying glass.'],
    ['Merge', 'Select two or more documents in the library, then choose Merge.'],
    ['Edit', 'Highlight, type text and draw on a page. Organize Pages reorders, rotates and deletes pages.'],
    ['Sign', 'Draw your signature once, then drag it onto the line and place it.'],
    ['Private', 'Your documents stay on this computer. PDFoundry only goes online to check for its own updates.'],
  ];
  let y = 630;
  for (const [title, text] of lines) {
    page.drawText(title, { x: 56, y, size: 15, font: bold, color: rgb(0.886, 0.318, 0.106) });
    page.drawText(text, { x: 56, y: y - 22, size: 11.5, font: regular, color: rgb(0.2, 0.22, 0.27), maxWidth: 500, lineHeight: 16 });
    y -= 84;
  }
  page.drawText('Try it: sign on the line below.', { x: 56, y: 190, size: 11.5, font: regular, color: rgb(0.2, 0.22, 0.27) });
  page.drawLine({ start: { x: 56, y: 130 }, end: { x: 300, y: 130 }, thickness: 1, color: rgb(0.2, 0.22, 0.27) });
  page.drawText('Signature', { x: 56, y: 114, size: 9, font: regular, color: rgb(0.45, 0.47, 0.52) });
  return save(doc);
}

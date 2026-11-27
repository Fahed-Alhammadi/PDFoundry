// The PDF engine: every change PDFoundry makes to a document. Pure functions from bytes to bytes (no Electron, no
// DOM), so they run in the window and in the tests (test/pdf.test.ts).
//
// Edits are drawn into the page itself, so they look the same in every other PDF reader. Positions are fractions of
// the page as it is displayed (after its rotation), measured from the top-left corner: the same convention as the
// Android app's PdfEngine.
import {
  BlendMode, PDFArray, PDFDocument, PDFName, PDFPage, PDFRef, PDFStream, decodePDFRawStream, degrees, fill, popGraphicsState, pushGraphicsState, rectangle,
  setFillingRgbColor, setGraphicsState,
} from '@cantoo/pdf-lib';
import type { PDFRawStream } from '@cantoo/pdf-lib';
import { encryptionOf, isOwnerPassword } from './security.ts';

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

/** Joins documents, in the order given, into one. Protected documents are refused: the result would have no obvious password. */
export async function merge(sources: Uint8Array[]): Promise<Uint8Array> {
  if (sources.length < 2) throw new PdfError('Choose two or more documents to merge.');
  const out = await PDFDocument.create();
  for (const bytes of sources) {
    if (await isProtected(bytes)) throw new PdfError('A PDF protected with a password can’t be merged. Use More › Remove Password first.');
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

/** One new document per part (each a list of pages, in order), as Split PDF makes them. The source is only read. */
export async function split(bytes: Uint8Array, parts: number[][]): Promise<Uint8Array[]> {
  if (!parts.length || parts.some((p) => !p.length)) throw new PdfError('There is nothing to split.');
  const doc = await open(bytes);
  const out: Uint8Array[] = [];
  for (const part of parts) {
    for (const p of part) if (!Number.isInteger(p) || p < 0 || p >= doc.getPageCount()) throw new PdfError(`There is no page ${p + 1}.`);
    const piece = await PDFDocument.create();
    for (const page of await piece.copyPages(doc, part)) piece.addPage(page);
    out.push(await save(piece));
  }
  return out;
}

// Passwords. A protected document is decrypted once, when its password is entered; every change works on the
// decrypted bytes, and every write encrypts them again with the same password (`protect`). The password is never
// stored here.

/** Is the file encrypted (an open password, or restrictions only)? */
export async function isProtected(bytes: Uint8Array): Promise<boolean> {
  try {
    return (await PDFDocument.load(bytes, { ignoreEncryption: true, updateMetadata: false })).isEncrypted;
  } catch {
    return false;
  }
}

/**
 * Decrypts a protected file with its password. Null when the password is wrong. `full`: the password is the owner
 * password (or the same as the open password), which is what allows changing the file; a password that only opens
 * it gives `full: false`, and the document must stay read-only. A file that is not protected comes back as it is.
 */
export async function unlock(bytes: Uint8Array, password: string): Promise<{ bytes: Uint8Array; full: boolean } | null> {
  let probe: PDFDocument;
  try {
    probe = await PDFDocument.load(bytes, { ignoreEncryption: true, updateMetadata: false });
  } catch {
    throw new PdfError('This file is not a PDF that PDFoundry can read.');
  }
  const encryption = encryptionOf(probe);
  if (!probe.isEncrypted || !encryption) return { bytes, full: true };
  let doc: PDFDocument;
  try {
    doc = await PDFDocument.load(bytes, { password, updateMetadata: false });
  } catch (e) {
    if (/password/i.test(e instanceof Error ? e.message : String(e))) return null;
    throw new PdfError('This PDF is protected in a way PDFoundry can’t open for changes.');
  }
  const full = await isOwnerPassword(encryption, password);
  // pdf-lib drops the trailer's /Encrypt but keeps the dictionary itself and the old cross-reference streams (which
  // still name it): a reader finding either would take the decrypted file for an encrypted one.
  const ref = probe.context.trailerInfo.Encrypt;
  if (ref instanceof PDFRef) doc.context.delete(ref);
  for (const [at, object] of doc.context.enumerateIndirectObjects()) {
    if (object instanceof PDFStream && object.dict.get(PDFName.of('Type')) === PDFName.of('XRef')) doc.context.delete(at);
  }
  return { bytes: await save(doc), full };
}

/**
 * Encrypts a document with AES-256. The password is both the open (user) and the owner password, and everything is
 * allowed, so whoever has the password can do anything with the file.
 */
export async function protect(bytes: Uint8Array, password: string): Promise<Uint8Array> {
  if (!password) throw new PdfError('Enter a password.');
  const doc = await open(bytes);
  doc.encrypt({
    userPassword: password,
    ownerPassword: password,
    algorithm: 'AES-256',
    permissions: { printing: 'highResolution', modifying: true, copying: true, annotating: true, fillingForms: true, contentAccessibility: true, documentAssembly: true },
  });
  return save(doc);
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

/**
 * Does the page's own drawing leave something behind that would move or clip what is drawn after it? A `cm`,
 * clip (`W`) or `gs` outside any q…Q, or more `q` than `Q`. Returns how many `Q` are missing, or -1 when nothing
 * is left behind. Strings, hex strings, comments and inline images are skipped so their bytes are not read as
 * operators; content that cannot be decoded is treated as unsafe.
 */
export function leftoverState(content: string): number {
  const cleaned = content
    .replace(/\bBI\b[\s\S]*?\bEI\b/g, ' ')
    .replace(/\((?:\\[\s\S]|[^\\()]|\((?:\\[\s\S]|[^\\()])*\))*\)/g, ' ')
    .replace(/<[0-9A-Fa-f\s]*>/g, ' ')
    .replace(/%[^\r\n]*/g, ' ');
  let depth = 0;
  let dirty = false;
  for (const token of cleaned.split(/[\s[\]<>{}/]+/)) {
    if (token === 'q') depth++;
    else if (token === 'Q') depth = Math.max(0, depth - 1);
    else if (depth === 0 && (token === 'cm' || token === 'W' || token === 'W*' || token === 'gs')) dirty = true;
  }
  return dirty || depth > 0 ? depth : -1;
}

/**
 * Makes sure what PDFoundry draws on a page is drawn in the page's own coordinates: when the page's drawing leaves
 * a transformation, clip or graphics state behind, it is wrapped in q…Q, once (pdf-lib would wrap it again on every
 * edit, nesting deeper each time). Must be called before anything else touches the page.
 */
function isolate(doc: PDFDocument, page: PDFPage): void {
  const node = page.node;
  (node as unknown as { autoNormalizeCTM: boolean }).autoNormalizeCTM = false;
  const contents = node.Contents();
  if (!contents) return;
  const streams = contents instanceof PDFArray ? contents.asArray().map((r) => doc.context.lookup(r)) : [contents];
  let missing: number;
  try {
    missing = leftoverState(streams.map((s) => new TextDecoder('latin1').decode(decodePDFRawStream(s as PDFRawStream).decode())).join('\n'));
  } catch {
    missing = 0; // unreadable: wrap it to be safe
  }
  node.normalize();
  if (missing < 0) return;
  const start = doc.context.register(doc.context.contentStream([pushGraphicsState()]));
  const end = doc.context.register(doc.context.contentStream(Array.from({ length: missing + 1 }, () => popGraphicsState())));
  node.wrapContentStreams(start, end);
}

/** Draws a PNG onto a page: a signature, typed text, a drawing or a highlight. */
export async function stamp(bytes: Uint8Array, pageIndex: number, png: Uint8Array, rect: PageRect, options: StampOptions = {}): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  if (!(rect.width > 0 && rect.height > 0)) throw new PdfError('There is nothing to place.');
  const page = doc.getPage(pageIndex);
  isolate(doc, page);
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

/** Marks for selected text: the same three as the iPhone app. */
export type TextMark = 'highlight' | 'underline' | 'strikethrough';
/** Red, green, blue and opacity, each 0…1. */
export type Rgba = [number, number, number, number];

/** `rect` of the displayed page as a box in the page's own coordinates (points, from the bottom-left, unrotated). */
export function userRect(page: PDFPage, rect: PageRect): { x: number; y: number; width: number; height: number } {
  const box = page.getCropBox();
  const turns = quarterTurns(page);
  const shown = displayedSize(page);
  // The same mapping as `placement`, applied to two opposite corners.
  const toPage = (dx: number, dy: number): [number, number] =>
    turns === 0 ? [dx, box.height - dy] : turns === 1 ? [dy, dx] : turns === 2 ? [box.width - dx, dy] : [box.width - dy, box.height - dx];
  const [x1, y1] = toPage(rect.x * shown.width, rect.y * shown.height);
  const [x2, y2] = toPage((rect.x + rect.width) * shown.width, (rect.y + rect.height) * shown.height);
  return { x: box.x + Math.min(x1, x2), y: box.y + Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
}

/**
 * Highlights, underlines or strikes through text: `rects` are the selected words on the displayed page (fractions
 * from its top-left). Text is assumed upright on the unrotated page (a turned page turns its text with it), so the
 * lines are measured across the box's height in page coordinates: an underline at 92% of the way down, a
 * strikethrough at half, each 7% of the text's height thick. A highlight darkens what is under it, like a marker.
 * The marks are drawn into the page, like every other edit.
 */
export async function markText(bytes: Uint8Array, pageIndex: number, rects: PageRect[], mark: TextMark, rgba: Rgba): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const chosen = rects.filter((r) => r.width > 0 && r.height > 0);
  if (!chosen.length) throw new PdfError('Select some text first.');
  const page = doc.getPage(pageIndex);
  isolate(doc, page);
  const [r, g, b, alpha] = rgba;
  const state = page.node.newExtGState('Mark', doc.context.obj({
    Type: 'ExtGState',
    ca: alpha,
    CA: alpha,
    ...(mark === 'highlight' ? { BM: 'Multiply' } : {}),
  }));
  const ops = [pushGraphicsState(), setGraphicsState(state), setFillingRgbColor(r, g, b)];
  for (const rect of chosen) {
    const box = userRect(page, rect);
    if (mark === 'highlight') ops.push(rectangle(box.x, box.y, box.width, box.height));
    else {
      const thickness = box.height * 0.07;
      const centre = box.y + box.height * (mark === 'underline' ? 0.08 : 0.5);
      ops.push(rectangle(box.x, centre - thickness / 2, box.width, thickness));
    }
  }
  ops.push(fill(), popGraphicsState());
  page.pushOperators(...ops);
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
    ['Read', 'Choose Open, or drop a PDF onto the window. It opens where it is: nothing is copied.'],
    ['Merge', 'Tools merges files and extracts pages. Or select two or more recent files and choose Merge.'],
    ['Edit', 'Markup highlights, underlines or strikes through text you select, adds text and draws. Pages reorders pages.'],
    ['Save', 'Your file changes only when you choose Save. Save As keeps the original and makes a new file.'],
    ['Sign', 'Choose Fill & Sign, draw your signature once, then drag it onto the line and place it.'],
    ['Private', 'Your documents stay on this computer. PDFoundry only goes online to check for its own updates.'],
  ];
  let y = 640;
  for (const [title, text] of lines) {
    page.drawText(title, { x: 56, y, size: 15, font: bold, color: rgb(0.886, 0.318, 0.106) });
    page.drawText(text, { x: 56, y: y - 22, size: 11.5, font: regular, color: rgb(0.2, 0.22, 0.27), maxWidth: 500, lineHeight: 16 });
    y -= 72;
  }
  page.drawText('Try it: sign on the line below.', { x: 56, y: 190, size: 11.5, font: regular, color: rgb(0.2, 0.22, 0.27) });
  page.drawLine({ start: { x: 56, y: 130 }, end: { x: 300, y: 130 }, thickness: 1, color: rgb(0.2, 0.22, 0.27) });
  page.drawText('Signature', { x: 56, y: 114, size: 9, font: regular, color: rgb(0.45, 0.47, 0.52) });
  return save(doc);
}

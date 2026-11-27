// The PDF engine: every change PDFoundry makes to a document. Pure functions from bytes to bytes (no Electron, no
// DOM), so they run in the window and in the tests (test/pdf.test.ts).
//
// Marks (highlights, drawings, signatures, typed text, …) are PDF annotations with their own appearance
// (annotations.ts), so every other reader shows them the same way and they can be selected again. Positions are
// fractions of the page as it is displayed (after its rotation), measured from the top-left corner (geometry.ts).
import {
  LineCapStyle, LineJoinStyle, PDFArray, PDFCheckBox, PDFDocument, PDFDropdown, PDFHexString, PDFName, PDFOptionList, PDFPage, PDFRadioGroup, PDFRef, PDFSignature,
  PDFStream, PDFString, PDFTextField, StandardFonts, appendBezierCurve, closePath, decodePDFRawStream, degrees, drawImage, fill, fillAndStroke, lineTo, moveTo, popGraphicsState,
  pushGraphicsState, rectangle, setFillingRgbColor, setGraphicsState, setLineCap, setLineJoin, setLineWidth, setStrokingRgbColor, stroke,
} from '@cantoo/pdf-lib';
import type { PDFField, PDFFont, PDFOperator, PDFRawStream } from '@cantoo/pdf-lib';
import { Encodings } from '@cantoo/pdf-lib/standard-fonts';
import { addAnnotation, findMark, isMovable, marksOf, relocate, remove } from './annotations.ts';
import type { MarkInfo } from './annotations.ts';
import { displayedRect, displayedSize, placement, userPoint, userRect } from './geometry.ts';
import type { PageRect, PagePoint, UserBox } from './geometry.ts';
import { encryptionOf, isOwnerPassword, isUserPassword } from './security.ts';

export class PdfError extends Error {}

export type { PageRect, PagePoint, UserBox } from './geometry.ts';
export { displayedPoint, displayedRect, displayedSize, placement, userPoint, userRect } from './geometry.ts';
export type { MarkInfo, MarkKind } from './annotations.ts';

export type StampOptions = {
  /** Darkens what is underneath instead of covering it (the highlighter). */
  multiply?: boolean;
  opacity?: number;
};

export type SourceImage = { bytes: Uint8Array; type: 'png' | 'jpeg' };

const LETTER: [number, number] = [612, 792];

export async function open(bytes: Uint8Array): Promise<PDFDocument> {
  try {
    return await PDFDocument.load(bytes, { updateMetadata: false });
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new PdfError(/encrypt/i.test(message) ? 'This PDF is protected, so it can only be read.' : 'This file is not a PDF that PDFoundry can read.');
  }
}

export function save(doc: PDFDocument): Promise<Uint8Array> {
  // Form fields are never redrawn behind the caller's back: only `fillFields` draws, and only what it filled.
  return doc.save({ useObjectStreams: true, updateFieldAppearances: false });
}

export function check(doc: PDFDocument, pages: number[]): number[] {
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
 * it gives `full: false`, and the document must stay read-only. `opens`: the password is also the open password, so
 * saving with it as both passwords leaves the way the file opens unchanged (false: saving changes it to this one).
 * A file that is not protected comes back as it is.
 */
export async function unlock(bytes: Uint8Array, password: string): Promise<{ bytes: Uint8Array; full: boolean; opens: boolean } | null> {
  let probe: PDFDocument;
  try {
    probe = await PDFDocument.load(bytes, { ignoreEncryption: true, updateMetadata: false });
  } catch {
    throw new PdfError('This file is not a PDF that PDFoundry can read.');
  }
  const encryption = encryptionOf(probe);
  if (!probe.isEncrypted || !encryption) return { bytes, full: true, opens: true };
  let doc: PDFDocument;
  try {
    doc = await PDFDocument.load(bytes, { password, updateMetadata: false });
  } catch (e) {
    if (/password/i.test(e instanceof Error ? e.message : String(e))) return null;
    throw new PdfError('This PDF is protected in a way PDFoundry can’t open for changes.');
  }
  const full = await isOwnerPassword(encryption, password);
  const opens = await isUserPassword(encryption, password);
  // pdf-lib drops the trailer's /Encrypt but keeps the dictionary itself and the old cross-reference streams (which
  // still name it): a reader finding either would take the decrypted file for an encrypted one.
  const ref = probe.context.trailerInfo.Encrypt;
  if (ref instanceof PDFRef) doc.context.delete(ref);
  for (const [at, object] of doc.context.enumerateIndirectObjects()) {
    if (object instanceof PDFStream && object.dict.get(PDFName.of('Type')) === PDFName.of('XRef')) doc.context.delete(at);
  }
  return { bytes: await save(doc), full, opens };
}

/** How a document can be worked on, once opened: see `access`. */
export type Access = { bytes: Uint8Array; editable: boolean; password: string | null; changesPassword: boolean };

/**
 * What the reader does with a file it has just opened for display, with the password pdf.js was given (null when it
 * asked none). Editable and decrypted when the password gives full access; otherwise read-only, as it is, including
 * a protected file PDFoundry can show but not decrypt itself. Never throws for a protected file.
 * `changesPassword`: saving will make the entered password the open password too (it was only the owner password).
 */
export async function access(bytes: Uint8Array, password: string | null): Promise<Access> {
  const readOnly: Access = { bytes, editable: false, password: null, changesPassword: false };
  if (!(await isProtected(bytes))) return { ...readOnly, editable: await isEditable(bytes) };
  if (password === null) return readOnly;
  try {
    const unlocked = await unlock(bytes, password);
    if (!unlocked?.full) return readOnly;
    return { bytes: unlocked.bytes, editable: true, password, changesPassword: !unlocked.opens };
  } catch {
    return readOnly;
  }
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
export function isolate(doc: PDFDocument, page: PDFPage): void {
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

/** The graphics state of a mark that darkens what is under it (`multiply`) or lets it show through (`opacity`). */
function markState(options: StampOptions): Record<string, unknown> | null {
  const opacity = options.opacity ?? 1;
  if (!options.multiply && opacity >= 1) return null;
  return { Type: 'ExtGState', ca: opacity, CA: opacity, ...(options.multiply ? { BM: 'Multiply' } : {}) };
}

/**
 * Puts a PNG on a page as a stamp annotation: a signature, initials, typed text, the date, ✓ or ✗. The image is the
 * annotation's appearance, so it looks the same everywhere (text the window drew stays shaped, Arabic included).
 */
export async function stamp(bytes: Uint8Array, pageIndex: number, png: Uint8Array, rect: PageRect, options: StampOptions = {}): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  if (!(rect.width > 0 && rect.height > 0)) throw new PdfError('There is nothing to place.');
  const page = doc.getPage(pageIndex);
  const image = await doc.embedPng(png);
  const at = placement(page, rect);
  const state = markState(options);
  addAnnotation(doc, page, {
    subtype: 'Stamp',
    box: userRect(page, rect),
    draw: [
      pushGraphicsState(),
      ...(state ? [setGraphicsState('GS')] : []),
      ...drawImage('Im', { x: at.x, y: at.y, width: at.width, height: at.height, rotate: degrees(at.angle), xSkew: degrees(0), ySkew: degrees(0) }),
      popGraphicsState(),
    ],
    resources: { XObject: { Im: image.ref }, ...(state ? { ExtGState: { GS: state } } : {}) },
    entries: { Name: 'PDFoundry' },
  });
  return save(doc);
}

/** Marks for selected text: the same three as the iPhone app. */
export type TextMark = 'highlight' | 'underline' | 'strikethrough';
/** Red, green, blue and opacity, each 0…1. */
export type Rgba = [number, number, number, number];

const MARKUP_SUBTYPES: Record<TextMark, string> = { highlight: 'Highlight', underline: 'Underline', strikethrough: 'StrikeOut' };

/** The smallest box around boxes. */
function union(boxes: UserBox[]): UserBox {
  const x = Math.min(...boxes.map((b) => b.x));
  const y = Math.min(...boxes.map((b) => b.y));
  return { x, y, width: Math.max(...boxes.map((b) => b.x + b.width)) - x, height: Math.max(...boxes.map((b) => b.y + b.height)) - y };
}

/**
 * Highlights, underlines or strikes through text: `rects` are the selected words on the displayed page (fractions
 * from its top-left). One Highlight, Underline or StrikeOut annotation for all of them, with a quadrilateral per
 * box. Text is assumed upright on the unrotated page (a turned page turns its text with it), so the lines are
 * measured across the box's height in page coordinates: an underline at 92% of the way down, a strikethrough at
 * half, each 7% of the text's height thick. A highlight darkens what is under it, like a marker.
 */
export async function markText(bytes: Uint8Array, pageIndex: number, rects: PageRect[], mark: TextMark, rgba: Rgba): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const chosen = rects.filter((r) => r.width > 0 && r.height > 0);
  if (!chosen.length) throw new PdfError('Select some text first.');
  const page = doc.getPage(pageIndex);
  const [r, g, b, alpha] = rgba;
  const boxes = chosen.map((rect) => userRect(page, rect));
  const ops = [pushGraphicsState(), setGraphicsState('GS'), setFillingRgbColor(r, g, b)];
  for (const box of boxes) {
    if (mark === 'highlight') ops.push(rectangle(box.x, box.y, box.width, box.height));
    else {
      const thickness = box.height * 0.07;
      const centre = box.y + box.height * (mark === 'underline' ? 0.08 : 0.5);
      ops.push(rectangle(box.x, centre - thickness / 2, box.width, thickness));
    }
  }
  ops.push(fill(), popGraphicsState());
  // Each box's corners in reading order (top-left, top-right, bottom-left, bottom-right of the displayed text).
  const quads = chosen.flatMap((rect) => [
    userPoint(page, { x: rect.x, y: rect.y }), userPoint(page, { x: rect.x + rect.width, y: rect.y }),
    userPoint(page, { x: rect.x, y: rect.y + rect.height }), userPoint(page, { x: rect.x + rect.width, y: rect.y + rect.height }),
  ].flat());
  addAnnotation(doc, page, {
    subtype: MARKUP_SUBTYPES[mark],
    box: union(boxes),
    draw: ops,
    resources: { ExtGState: { GS: { Type: 'ExtGState', ca: alpha, CA: alpha, ...(mark === 'highlight' ? { BM: 'Multiply' } : {}) } } },
    entries: { C: [r, g, b], CA: alpha, QuadPoints: quads },
  });
  return save(doc);
}

/** A drawn line: points on the displayed page (fractions), a CSS colour (#rrggbb), its width as a fraction of the page width. */
export type InkStroke = { points: PagePoint[]; color: string; width: number };

export function hexColour(css: string): [number, number, number] {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(css.trim());
  return m ? [parseInt(m[1], 16) / 255, parseInt(m[2], 16) / 255, parseInt(m[3], 16) / 255] : [0, 0, 0];
}

/** A filled circle (four Bézier curves). */
function dot(x: number, y: number, r: number): PDFOperator[] {
  const k = 0.5523 * r;
  return [
    moveTo(x + r, y),
    appendBezierCurve(x + r, y + k, x + k, y + r, x, y + r),
    appendBezierCurve(x - k, y + r, x - r, y + k, x - r, y),
    appendBezierCurve(x - r, y - k, x - k, y - r, x, y - r),
    appendBezierCurve(x + k, y - r, x + r, y - k, x + r, y),
    closePath(),
  ];
}

/**
 * Freehand drawing (Markup › Draw) as one Ink annotation: the strokes as vector lines with round ends. `options`:
 * the highlighter pen darkens what is under it (`multiply`) and lets it show (`opacity`).
 */
export async function ink(bytes: Uint8Array, pageIndex: number, strokes: InkStroke[], options: StampOptions = {}): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const drawn = strokes.filter((s) => s.points.length && s.width > 0);
  if (!drawn.length) throw new PdfError('There is nothing to place.');
  const page = doc.getPage(pageIndex);
  const shown = displayedSize(page);
  const state = markState(options);
  const ops: PDFOperator[] = [pushGraphicsState(), ...(state ? [setGraphicsState('GS')] : []), setLineCap(LineCapStyle.Round), setLineJoin(LineJoinStyle.Round)];
  const boxes: UserBox[] = [];
  const list: number[][] = [];
  for (const s of drawn) {
    const width = s.width * shown.width;
    const [r, g, b] = hexColour(s.color);
    const points = s.points.map((p) => userPoint(page, p));
    list.push(points.flat());
    const xs = points.map((p) => p[0]);
    const ys = points.map((p) => p[1]);
    boxes.push({ x: Math.min(...xs) - width / 2, y: Math.min(...ys) - width / 2, width: Math.max(...xs) - Math.min(...xs) + width, height: Math.max(...ys) - Math.min(...ys) + width });
    if (points.length === 1) ops.push(setFillingRgbColor(r, g, b), ...dot(points[0][0], points[0][1], width / 2), fill());
    else {
      ops.push(setStrokingRgbColor(r, g, b), setLineWidth(width), moveTo(...points[0]));
      for (const p of points.slice(1)) ops.push(lineTo(...p));
      ops.push(stroke());
    }
  }
  ops.push(popGraphicsState());
  addAnnotation(doc, page, {
    subtype: 'Ink',
    box: union(boxes),
    draw: ops,
    resources: state ? { ExtGState: { GS: state } } : undefined,
    entries: {
      C: hexColour(drawn[0].color),
      ...(options.opacity !== undefined ? { CA: options.opacity } : {}),
      BS: { Type: 'Border', W: drawn[0].width * shown.width, S: 'S' },
      InkList: list,
    },
  });
  return save(doc);
}

/** The marks on a page (see annotations.ts), for selecting them. */
export async function marks(bytes: Uint8Array, pageIndex: number): Promise<MarkInfo[]> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  return marksOf(doc, doc.getPage(pageIndex));
}

async function markOn(bytes: Uint8Array, pageIndex: number, id: string) {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const page = doc.getPage(pageIndex);
  const mark = findMark(doc, page, id);
  if (!mark) throw new PdfError('That mark is no longer there.');
  return { doc, page, mark };
}

/** Moves or resizes a mark to `rect` of the displayed page. Its appearance is kept exactly as it is. */
export async function moveMark(bytes: Uint8Array, pageIndex: number, id: string, rect: PageRect): Promise<Uint8Array> {
  const { doc, page, mark } = await markOn(bytes, pageIndex, id);
  if (!isMovable(mark.dict)) throw new PdfError('This mark can’t be moved.');
  if (!(rect.width > 0 && rect.height > 0)) throw new PdfError('A mark can’t be made that small.');
  relocate(doc, mark.dict, userRect(page, rect));
  return save(doc);
}

/** Deletes a mark (PDFoundry's own or another app's), with its appearance. */
export async function deleteMark(bytes: Uint8Array, pageIndex: number, id: string): Promise<Uint8Array> {
  const { doc, page, mark } = await markOn(bytes, pageIndex, id);
  remove(doc, page, mark);
  return save(doc);
}

export type ShapeKind = 'rectangle' | 'ellipse' | 'line' | 'arrow';
/** Every shape's border, in points. */
const SHAPE_WIDTH = 2;
/** How long the arrow head's two strokes are, in points. */
const ARROW = 10;

/** An ellipse inside a box (four Bézier curves). */
function ellipse(box: UserBox): PDFOperator[] {
  const [rx, ry] = [box.width / 2, box.height / 2];
  const [cx, cy] = [box.x + rx, box.y + ry];
  const [kx, ky] = [0.5523 * rx, 0.5523 * ry];
  return [
    moveTo(cx + rx, cy),
    appendBezierCurve(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry),
    appendBezierCurve(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy),
    appendBezierCurve(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry),
    appendBezierCurve(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy),
    closePath(),
  ];
}

/**
 * A shape dragged from `from` to `to` (points of the displayed page): a rectangle or ellipse in the box between them,
 * or a line (an arrow points at `to`). A 2-point border in `rgba`, no fill. Square, Circle and Line annotations.
 */
export async function shape(bytes: Uint8Array, pageIndex: number, kind: ShapeKind, from: PagePoint, to: PagePoint, rgba: Rgba): Promise<Uint8Array> {
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const page = doc.getPage(pageIndex);
  const [r, g, b, alpha] = rgba;
  const [x1, y1] = userPoint(page, from);
  const [x2, y2] = userPoint(page, to);
  if (Math.hypot(x2 - x1, y2 - y1) < 2) throw new PdfError('There is nothing to place.');
  const half = SHAPE_WIDTH / 2;
  const state = alpha < 1 ? { ExtGState: { GS: { Type: 'ExtGState', CA: alpha, ca: alpha } } } : undefined;
  const ops: PDFOperator[] = [pushGraphicsState(), ...(state ? [setGraphicsState('GS')] : []), setStrokingRgbColor(r, g, b), setLineWidth(SHAPE_WIDTH)];
  const entries: Record<string, unknown> = { C: [r, g, b], BS: { Type: 'Border', W: SHAPE_WIDTH, S: 'S' }, ...(alpha < 1 ? { CA: alpha } : {}) };
  let box: UserBox;
  let subtype: string;
  if (kind === 'rectangle' || kind === 'ellipse') {
    const inner = { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
    if (inner.width < 1 || inner.height < 1) throw new PdfError('There is nothing to place.');
    box = { x: inner.x - half, y: inner.y - half, width: inner.width + SHAPE_WIDTH, height: inner.height + SHAPE_WIDTH };
    subtype = kind === 'rectangle' ? 'Square' : 'Circle';
    ops.push(...(kind === 'rectangle' ? [rectangle(inner.x, inner.y, inner.width, inner.height)] : ellipse(inner)), stroke());
  } else {
    subtype = 'Line';
    ops.push(setLineCap(LineCapStyle.Round), setLineJoin(LineJoinStyle.Round), moveTo(x1, y1), lineTo(x2, y2));
    const reach = kind === 'arrow' ? ARROW + half : half;
    if (kind === 'arrow') {
      const angle = Math.atan2(y2 - y1, x2 - x1);
      for (const side of [-1, 1]) {
        const a = angle + Math.PI + (side * Math.PI) / 6;
        ops.push(moveTo(x2, y2), lineTo(x2 + Math.cos(a) * ARROW, y2 + Math.sin(a) * ARROW));
      }
    }
    ops.push(stroke());
    box = { x: Math.min(x1, x2) - reach, y: Math.min(y1, y2) - reach, width: Math.abs(x2 - x1) + 2 * reach, height: Math.abs(y2 - y1) + 2 * reach };
    entries.L = [x1, y1, x2, y2];
    if (kind === 'arrow') entries.LE = ['None', 'OpenArrow'];
  }
  ops.push(popGraphicsState());
  addAnnotation(doc, page, { subtype, box, draw: ops, resources: state, entries });
  return save(doc);
}

/** A comment's note icon, in points on the displayed page. */
const NOTE = 20;
const NOTE_FILL: [number, number, number] = [1, 0.835, 0.29];
const NOTE_LINE: [number, number, number] = [0.071, 0.078, 0.11];

/**
 * A comment (sticky note): the standard note icon with its top-left corner at `at` (a point of the displayed page),
 * and `text` as its contents, shown in other apps' popups. Empty text adds nothing (the same bytes come back).
 */
export async function comment(bytes: Uint8Array, pageIndex: number, at: PagePoint, text: string): Promise<Uint8Array> {
  if (!text.trim()) return bytes;
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const page = doc.getPage(pageIndex);
  const shown = displayedSize(page);
  const [w, h] = [NOTE / shown.width, NOTE / shown.height];
  const corner = { x: Math.min(Math.max(0, at.x), 1 - w), y: Math.min(Math.max(0, at.y), 1 - h) };
  // The icon is drawn upright on the displayed page: each of its points (in points from the icon's top-left) is
  // turned into page coordinates.
  const p = (x: number, y: number) => userPoint(page, { x: corner.x + x / shown.width, y: corner.y + y / shown.height });
  const ops: PDFOperator[] = [
    pushGraphicsState(), setLineJoin(LineJoinStyle.Round), setLineWidth(1),
    setFillingRgbColor(...NOTE_FILL), setStrokingRgbColor(...NOTE_LINE),
    moveTo(...p(2, 2)), lineTo(...p(18, 2)), lineTo(...p(18, 14)), lineTo(...p(9, 14)), lineTo(...p(5, 18)), lineTo(...p(5, 14)), lineTo(...p(2, 14)), closePath(),
    fillAndStroke(),
  ];
  for (const y of [5.5, 8, 10.5]) ops.push(moveTo(...p(5, y)), lineTo(...p(15, y)));
  ops.push(stroke(), popGraphicsState());
  addAnnotation(doc, page, {
    subtype: 'Text',
    box: userRect(page, { ...corner, width: w, height: h }),
    draw: ops,
    entries: { Contents: PDFHexString.fromText(text.trim()), Name: 'Comment', C: NOTE_FILL, Open: false },
  });
  return save(doc);
}

/** The text of a comment. */
export async function commentText(bytes: Uint8Array, pageIndex: number, id: string): Promise<string> {
  const { mark } = await markOn(bytes, pageIndex, id);
  const value = mark.dict.lookup(PDFName.of('Contents'));
  return value instanceof PDFString || value instanceof PDFHexString ? value.decodeText() : '';
}

/** Changes a comment's text. Emptied, the comment is deleted. */
export async function updateComment(bytes: Uint8Array, pageIndex: number, id: string, text: string): Promise<Uint8Array> {
  const { doc, page, mark } = await markOn(bytes, pageIndex, id);
  if (mark.kind !== 'comment') throw new PdfError('That mark is not a comment.');
  if (!text.trim()) remove(doc, page, mark);
  else {
    mark.dict.set(PDFName.of('Contents'), PDFHexString.fromText(text.trim()));
    mark.dict.set(PDFName.of('M'), PDFString.fromDate(new Date()));
  }
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

// Form fields. Values are written as the fields' own values (/V), with appearances so that every reader shows them,
// and the fields stay fields: other apps can still read and change them. Nothing is flattened.

export type FieldKind = 'text' | 'multiline' | 'checkbox' | 'radio' | 'choice' | 'signature';
export type FieldValue = string | boolean | string[];
/** One widget of a field: where it is on its page (displayed, fractions from the top-left) and the field's value. */
export type FieldInfo = { name: string; kind: FieldKind; page: number; rect: PageRect; value: FieldValue; options: string[]; readOnly: boolean };

function kindOf(field: PDFField): FieldKind | null {
  if (field instanceof PDFTextField) return field.isMultiline() ? 'multiline' : 'text';
  if (field instanceof PDFCheckBox) return 'checkbox';
  if (field instanceof PDFRadioGroup) return 'radio';
  if (field instanceof PDFDropdown || field instanceof PDFOptionList) return 'choice';
  if (field instanceof PDFSignature) return 'signature';
  return null; // push buttons: nothing to fill
}

function valueOf(field: PDFField): FieldValue {
  if (field instanceof PDFTextField) return field.getText() ?? '';
  if (field instanceof PDFCheckBox) return field.isChecked();
  if (field instanceof PDFRadioGroup) return field.getSelected() ?? '';
  if (field instanceof PDFDropdown || field instanceof PDFOptionList) {
    const selected = field.getSelected();
    return selected.length > 1 ? selected : selected[0] ?? '';
  }
  return '';
}

/** The fillable fields of a document, one entry per widget, in page order. Empty without a form (or with an XFA-only one). */
export async function fields(bytes: Uint8Array): Promise<FieldInfo[]> {
  const doc = await open(bytes);
  if (!doc.catalog.getAcroForm()) return [];
  const pageOf = new Map<string, number>();
  doc.getPages().forEach((page, index) => {
    for (const ref of page.node.Annots()?.asArray() ?? []) if (ref instanceof PDFRef) pageOf.set(ref.toString(), index);
  });
  const out: FieldInfo[] = [];
  for (const field of doc.getForm().getFields()) {
    const kind = kindOf(field);
    if (!kind) continue;
    for (const widget of field.acroField.getWidgets()) {
      const ref = doc.context.getObjectRef(widget.dict);
      const owner = widget.P();
      const page = (ref ? pageOf.get(ref.toString()) : undefined) ?? (owner ? doc.getPages().findIndex((p) => p.ref === owner) : -1);
      if (page < 0) continue;
      out.push({
        name: field.getName(),
        kind,
        page,
        rect: displayedRect(doc.getPage(page), widget.getRectangle()),
        value: valueOf(field),
        options: field instanceof PDFRadioGroup || field instanceof PDFDropdown || field instanceof PDFOptionList ? field.getOptions() : [],
        readOnly: field.isReadOnly(),
      });
    }
  }
  return out.sort((a, b) => a.page - b.page || a.rect.y - b.rect.y || a.rect.x - b.rect.x);
}

/**
 * Can a field show this text with the standard font (Helvetica, Windows Latin)? When not (Arabic, say), the window
 * draws the text itself and the drawing becomes the field's appearance (`fillFields` `images`). pdf-lib would
 * otherwise draw "?" for every letter it cannot encode.
 */
export function canEncode(text: string): boolean {
  return Array.from(text.replace(/\s/g, ' ')).every((ch) => Encodings.WinAnsi.canEncodeUnicodeCodePoint(ch.codePointAt(0)!));
}

/**
 * Fills fields by name: text and choices take strings (an option list may take several), checkboxes true or false,
 * radio groups the chosen option ('' clears). `images`: for a text field whose text the standard font cannot show,
 * a PNG of the text drawn by the window, used as the field's appearance; its value is still the real text. Refuses
 * read-only fields and names that are not fields.
 *
 * Only the fields given here get new appearances: the others are left exactly as they are (a form that asks readers
 * to draw its fields, with NeedAppearances, keeps asking). A radio button may be named by its option or by its
 * on-state name (what pdf.js reports); a choice by its export value or the text shown, and its export value is what
 * is written.
 */
export async function fillFields(bytes: Uint8Array, values: Record<string, FieldValue>, images: Record<string, Uint8Array> = {}): Promise<Uint8Array> {
  const doc = await open(bytes);
  const form = doc.getForm();
  let font: PDFFont | null = null;
  for (const [name, value] of Object.entries(values)) {
    const field = form.getFieldMaybe(name);
    if (!field || !kindOf(field) || field instanceof PDFSignature) throw new PdfError(`This form has no field “${name}” to fill.`);
    if (field.isReadOnly()) throw new PdfError('That field can’t be changed.');
    try {
      let exports: string[] | null = null; // a choice whose export values differ from the text shown
      if (field instanceof PDFTextField) {
        const text = Array.isArray(value) ? value.join(' ') : value === false ? '' : String(value);
        field.setText(text === '' ? undefined : text);
        if (images[name] && text !== '') field.setImage(await doc.embedPng(images[name]));
        else if (text !== '' && !canEncode(text)) throw new PdfError('This text needs to be drawn by the window first.');
      } else if (field instanceof PDFCheckBox) {
        if (value === true || value === 'true') field.check();
        else field.uncheck();
      } else if (field instanceof PDFRadioGroup) {
        if (value === '' || value === false) field.clear();
        else {
          const options = field.getOptions();
          let option = String(value);
          if (!options.includes(option)) {
            const at = field.acroField.getOnValues().map((n) => n.decodeText()).indexOf(option);
            if (at >= 0 && at < options.length) option = options[at];
          }
          field.select(option);
        }
      } else if (field instanceof PDFDropdown || field instanceof PDFOptionList) {
        const entries = field.acroField.getOptions().map((o) => ({ value: o.value.decodeText(), shown: (o.display ?? o.value).decodeText() }));
        const chosen = (Array.isArray(value) ? value : [String(value)]).filter((v) => v !== '')
          .map((v) => entries.find((e) => e.value === v) ?? entries.find((e) => e.shown === v) ?? { value: v, shown: v });
        if (!chosen.length) field.clear();
        else field.select(chosen.map((c) => c.shown));
        if (chosen.some((c) => c.value !== c.shown)) exports = chosen.map((c) => c.value);
      }
      if (field.needsAppearancesUpdate()) field.defaultUpdateAppearances((font ??= await doc.embedFont(StandardFonts.Helvetica)));
      if (exports) {
        // Drawn with the text shown; the value is the export value, as the form defines it.
        const v = exports.length === 1 ? PDFHexString.fromText(exports[0]) : doc.context.obj(exports.map((e) => PDFHexString.fromText(e)));
        field.acroField.dict.set(PDFName.of('V'), v);
      }
    } catch (e) {
      throw e instanceof PdfError ? e : new PdfError(`“${name}” can’t take that value.`);
    }
  }
  return save(doc);
}

// Marks as PDF annotations. Every mark PDFoundry makes (highlight, underline, strikethrough, drawing, signature,
// initials, text, date, ✓, ✗, shapes, comments) is its own annotation with an appearance stream, so every viewer
// draws it the same way and it can be selected again later to move, resize or delete it.
//
// An appearance is a form whose bounding box is the annotation's /Rect, drawn in the page's own coordinates with no
// transformation. Moving or resizing a mark only changes its /Rect: viewers fit the unchanged appearance into the new
// box (PDF 32000 §12.5.5), so a moved signature is the very same image, never redrawn.
//
// Annotations PDFoundry makes carry `/PDFoundry true` and an /NM of `pdfoundry-<uuid>`; either one marks them as ours.
import { PDFArray, PDFBool, PDFDict, PDFHexString, PDFName, PDFNumber, PDFRef, PDFString } from '@cantoo/pdf-lib';
import type { PDFDocument, PDFOperator, PDFPage } from '@cantoo/pdf-lib';
import { displayedRect } from './geometry.ts';
import type { PageRect, UserBox } from './geometry.ts';

export type MarkKind = 'highlight' | 'underline' | 'strikethrough' | 'ink' | 'stamp' | 'rectangle' | 'ellipse' | 'line' | 'arrow' | 'comment';
/** A mark on a page, as the reader sees it. `rect` is on the displayed page (fractions from the top-left). */
export type MarkInfo = { id: string; kind: MarkKind; rect: PageRect; movable: boolean };

const OURS = PDFName.of('PDFoundry');
const NM = PDFName.of('NM');
const PREFIX = 'pdfoundry-';
/** Annotation flag: print it (PDF 32000 table 165). */
const PRINT = 4;

function uuid(): string {
  return globalThis.crypto.randomUUID();
}

/** Marks an annotation as made by PDFoundry. */
export function tag(dict: PDFDict): void {
  dict.set(OURS, PDFBool.True);
  dict.set(NM, PDFString.of(`${PREFIX}${uuid()}`));
}

function text(value: unknown): string | null {
  return value instanceof PDFString || value instanceof PDFHexString ? value.decodeText() : null;
}

/** Made by PDFoundry: has the tag, or an /NM that starts with `pdfoundry-` (when another app dropped the tag). */
export function isOurs(dict: PDFDict): boolean {
  return dict.get(OURS) === PDFBool.True || !!text(dict.lookup(NM))?.startsWith(PREFIX);
}

function subtype(dict: PDFDict): string {
  const value = dict.get(PDFName.of('Subtype'));
  return value instanceof PDFName ? value.decodeText() : '';
}

/** What kind of mark an annotation is; null for anything else (links, form fields, popups, …). */
export function kindOf(dict: PDFDict): MarkKind | null {
  switch (subtype(dict)) {
    case 'Highlight': return 'highlight';
    case 'Underline': return 'underline';
    case 'StrikeOut': return 'strikethrough';
    case 'Ink': return 'ink';
    case 'Stamp': return 'stamp';
    case 'Square': return 'rectangle';
    case 'Circle': return 'ellipse';
    case 'Text': return 'comment';
    case 'Line': {
      const ends = dict.lookupMaybe(PDFName.of('LE'), PDFArray)?.asArray() ?? [];
      return ends.some((e) => e instanceof PDFName && /Arrow/.test(e.decodeText())) ? 'arrow' : 'line';
    }
    default: return null;
  }
}

const TEXT_MARKUP: MarkKind[] = ['highlight', 'underline', 'strikethrough'];

/** Can the mark be moved and resized? Ours and not text markup (that belongs to its text). */
export function isMovable(dict: PDFDict): boolean {
  const kind = kindOf(dict);
  return !!kind && isOurs(dict) && !TEXT_MARKUP.includes(kind);
}

/** The id the reader uses for a mark: its /NM, or (an annotation without one) its object number. */
export function markId(dict: PDFDict, ref: PDFRef | undefined): string {
  return text(dict.lookup(NM)) ?? (ref ? `ref-${ref.objectNumber}-${ref.generationNumber}` : '');
}

export function rectOf(dict: PDFDict): UserBox {
  const values = (dict.lookupMaybe(PDFName.of('Rect'), PDFArray)?.asArray() ?? []).map((n) => (n instanceof PDFNumber ? n.asNumber() : 0));
  const [x1 = 0, y1 = 0, x2 = 0, y2 = 0] = values;
  return { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
}

/** The page's annotations that are marks, with their references, in page order. */
export function pageMarks(doc: PDFDocument, page: PDFPage): { ref: PDFRef | undefined; dict: PDFDict; kind: MarkKind }[] {
  const out: { ref: PDFRef | undefined; dict: PDFDict; kind: MarkKind }[] = [];
  for (const entry of page.node.Annots()?.asArray() ?? []) {
    const dict = entry instanceof PDFRef ? doc.context.lookup(entry) : entry;
    if (!(dict instanceof PDFDict)) continue;
    const kind = kindOf(dict);
    if (kind) out.push({ ref: entry instanceof PDFRef ? entry : undefined, dict, kind });
  }
  return out;
}

/** The marks on a page, for the reader. */
export function marksOf(doc: PDFDocument, page: PDFPage): MarkInfo[] {
  return pageMarks(doc, page).map(({ ref, dict, kind }) => ({
    id: markId(dict, ref),
    kind,
    rect: displayedRect(page, rectOf(dict)),
    movable: isMovable(dict),
  }));
}

export type NewAnnotation = {
  subtype: string;
  /** Where it is, in the page's own coordinates: the appearance's bounding box too. */
  box: UserBox;
  /** The appearance's drawing, in the page's own coordinates. */
  draw: PDFOperator[];
  /** The appearance's resources (XObjects, graphics states), written inside its dictionary. */
  resources?: Record<string, unknown>;
  /** Further entries of the annotation dictionary (/C, /QuadPoints, /InkList, …). */
  entries?: Record<string, unknown>;
};

/** Adds a tagged annotation with its appearance to a page. Returns its id. */
export function addAnnotation(doc: PDFDocument, page: PDFPage, a: NewAnnotation): string {
  const { x, y, width, height } = a.box;
  const bbox = [x, y, x + width, y + height];
  const ap = doc.context.formXObject(a.draw, { BBox: bbox, ...(a.resources ? { Resources: a.resources } : {}) } as never);
  const dict = doc.context.obj({
    Type: 'Annot',
    Subtype: a.subtype,
    Rect: bbox,
    F: PRINT,
    M: PDFString.fromDate(new Date()),
    ...(a.entries ?? {}),
  }) as PDFDict;
  dict.set(PDFName.of('AP'), doc.context.obj({ N: doc.context.register(ap) }));
  tag(dict);
  appendAnnot(doc, page, doc.context.register(dict));
  return text(dict.lookup(NM))!;
}

/** Adds an annotation to the page's /Annots, touching nothing else (pdf-lib's addAnnot also rewraps the page's drawing). */
export function appendAnnot(doc: PDFDocument, page: PDFPage, ref: PDFRef): void {
  const annots = page.node.lookupMaybe(PDFName.of('Annots'), PDFArray);
  if (annots) annots.push(ref);
  else page.node.set(PDFName.of('Annots'), doc.context.obj([ref]));
}

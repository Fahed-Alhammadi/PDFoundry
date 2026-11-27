// Redaction (Markup › Redact): what is under the boxes is removed for good. The window draws each redacted page as it
// is shown (its content, marks and form fields) at 300 dpi with the boxes painted solid black, as a PNG; here that
// picture becomes the page's only content. The page's old drawing, resources, annotations and form widgets are taken
// out, and then every object nothing refers to any more is dropped before the whole file is written again, so no old
// content stream, annotation text or field value survives anywhere in it (pdf-lib would otherwise keep writing
// unreferenced objects, and object streams from the file as it was read). Other pages are left as they are.
import { PDFArray, PDFDict, PDFName, PDFRef, PDFStream, degrees, drawImage, popGraphicsState, pushGraphicsState } from '@cantoo/pdf-lib';
import type { PDFDocument, PDFObject, PDFPage } from '@cantoo/pdf-lib';
import { placement } from './geometry.ts';
import { PdfError, check, open, save } from './pdf.ts';

/** What a redacted page keeps of its own dictionary: where it is in the document, its boxes and its rotation. */
const KEEP = new Set(['Type', 'Parent', 'MediaBox', 'CropBox', 'BleedBox', 'TrimBox', 'ArtBox', 'Rotate', 'UserUnit']);

/**
 * Replaces each page in `pages` (index → PNG of the page as displayed, boxes painted black) with its picture.
 * Page size, rotation and position stay. Returns the whole file written again.
 */
export async function redact(bytes: Uint8Array, pages: Map<number, Uint8Array>): Promise<Uint8Array> {
  if (!pages.size) throw new PdfError('There is nothing to redact.');
  const doc = await open(bytes);
  check(doc, [...pages.keys()]);
  for (const [index, png] of pages) await flatten(doc, doc.getPage(index), png);
  prune(doc);
  return save(doc);
}

async function flatten(doc: PDFDocument, page: PDFPage, png: Uint8Array): Promise<void> {
  const image = await doc.embedPng(png);
  const at = placement(page, { x: 0, y: 0, width: 1, height: 1 });
  const node = page.node;
  dropWidgets(doc, node.lookupMaybe(PDFName.of('Annots'), PDFArray));
  for (const key of node.keys()) if (!KEEP.has(key.decodeText())) node.delete(key);
  const content = doc.context.contentStream([
    pushGraphicsState(),
    ...drawImage('Page', { x: at.x, y: at.y, width: at.width, height: at.height, rotate: degrees(at.angle), xSkew: degrees(0), ySkew: degrees(0) }),
    popGraphicsState(),
  ]);
  node.set(PDFName.of('Contents'), doc.context.register(content));
  node.set(PDFName.of('Resources'), doc.context.obj({ XObject: { Page: image.ref } }));
}

/** Takes the form widgets among `annots` out of the document's form; a field left with no widgets goes too. */
function dropWidgets(doc: PDFDocument, annots: PDFArray | undefined): void {
  const gone = new Set((annots?.asArray() ?? []).filter((a): a is PDFRef => a instanceof PDFRef).map((r) => r.toString()));
  const form = doc.catalog.lookupMaybe(PDFName.of('AcroForm'), PDFDict);
  const fields = form?.lookupMaybe(PDFName.of('Fields'), PDFArray);
  if (!gone.size || !fields) return;
  // Keeps the entries of `list` that still have a widget somewhere else; returns whether any are left.
  const keep = (list: PDFArray): boolean => {
    for (let i = list.size() - 1; i >= 0; i--) {
      const entry = list.get(i);
      if (entry instanceof PDFRef && gone.has(entry.toString())) {
        list.remove(i);
        continue;
      }
      const node = doc.context.lookup(entry);
      const kids = node instanceof PDFDict ? node.lookupMaybe(PDFName.of('Kids'), PDFArray) : undefined;
      if (kids && kids.size() && !keep(kids)) list.remove(i);
    }
    return list.size() > 0;
  };
  keep(fields);
}

/** Deletes every object that the document no longer refers to (from its catalog and its information dictionary). */
export function prune(doc: PDFDocument): void {
  const { context } = doc;
  const seen = new Set<string>();
  const stack: (PDFObject | undefined)[] = [context.trailerInfo.Root, context.trailerInfo.Info];
  while (stack.length) {
    const object = stack.pop();
    if (object instanceof PDFRef) {
      const key = object.toString();
      if (seen.has(key)) continue;
      seen.add(key);
      stack.push(context.lookup(object));
    } else if (object instanceof PDFDict) {
      for (const [, value] of object.entries()) stack.push(value);
    } else if (object instanceof PDFArray) {
      stack.push(...object.asArray());
    } else if (object instanceof PDFStream) {
      stack.push(object.dict);
    }
  }
  for (const [ref] of context.enumerateIndirectObjects()) if (!seen.has(ref.toString())) context.delete(ref);
}

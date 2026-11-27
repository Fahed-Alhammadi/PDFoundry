// Showing PDFs with pdf.js: opening (with a password when needed), page sizes, rendering and text search.
// Everything pdf.js loads at run time (worker, character maps, fonts, decoders) ships inside the app.
import * as pdfjs from 'pdfjs-dist';
import type { PDFDocumentProxy, PDFPageProxy, RenderTask } from 'pdfjs-dist';

pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdf.worker.min.mjs', location.href).toString();

export type { PDFDocumentProxy, PDFPageProxy };
export type Size = { width: number; height: number };

export class PasswordNeeded extends Error {}

/**
 * Opens a PDF for display. `password` is asked for when the document needs one: it returns the password to try,
 * or null to give up (the open then fails with PasswordNeeded).
 */
export async function openPdf(bytes: Uint8Array, password?: (wrong: boolean) => Promise<string | null>): Promise<PDFDocumentProxy> {
  const task = pdfjs.getDocument({
    data: bytes.slice(), // pdf.js takes the buffer for its worker
    cMapUrl: new URL('pdfjs/cmaps/', location.href).toString(),
    cMapPacked: true,
    standardFontDataUrl: new URL('pdfjs/standard_fonts/', location.href).toString(),
    wasmUrl: new URL('pdfjs/wasm/', location.href).toString(),
    enableXfa: false,
    stopAtErrors: false,
  });
  task.onPassword = (update: (password: string) => void, reason: number) => {
    const wrong = reason === pdfjs.PasswordResponses.INCORRECT_PASSWORD;
    void (password ? password(wrong) : Promise.resolve(null)).then((answer) => {
      if (answer === null) void task.destroy();
      else update(answer);
    });
  };
  try {
    return await task.promise;
  } catch (e) {
    const name = (e as { name?: string })?.name;
    if (name === 'PasswordException' || /destroyed|password/i.test(String((e as Error)?.message))) throw new PasswordNeeded('This PDF needs its password.');
    throw new Error('This file is not a PDF that PDFoundry can read.');
  }
}

/** Every page's size in points, as displayed (after rotation). */
export async function pageSizes(doc: PDFDocumentProxy): Promise<Size[]> {
  const sizes: Size[] = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const viewport = (await doc.getPage(n)).getViewport({ scale: 1 });
    sizes.push({ width: viewport.width, height: viewport.height });
  }
  return sizes;
}

/**
 * Draws page `index` (0-based) into `canvas`, `width` CSS pixels wide. Returns the task so it can be cancelled.
 * Form fields are drawn as they are saved (their appearances), unless `forms` is given: then they are left to the
 * form layer (`renderFormLayer`), and `forms` collects the pictures of checkboxes and radio buttons it needs.
 */
export async function renderPage(doc: PDFDocumentProxy, index: number, canvas: HTMLCanvasElement, width: number, pixelRatio = window.devicePixelRatio || 1, forms?: Map<string, HTMLCanvasElement>): Promise<RenderTask> {
  const page = await doc.getPage(index + 1);
  const base = page.getViewport({ scale: 1 });
  // Sharp on high-density screens, but never more than ~16 megapixels per page.
  const scale = Math.min((width / base.width) * pixelRatio, Math.sqrt(16_000_000 / (base.width * base.height)));
  const viewport = page.getViewport({ scale });
  canvas.width = Math.round(viewport.width);
  canvas.height = Math.round(viewport.height);
  const task = page.render({
    canvas, viewport, background: '#ffffff',
    annotationMode: forms ? pdfjs.AnnotationMode.ENABLE_FORMS : pdfjs.AnnotationMode.ENABLE,
    annotationCanvasMap: forms,
  });
  return task;
}

/** A form widget as pdf.js describes it (the parts the reader uses). */
export type Widget = {
  id: string;
  fieldName: string;
  fieldType: 'Tx' | 'Btn' | 'Ch' | 'Sig';
  rect: [number, number, number, number];
  multiLine?: boolean;
  checkBox?: boolean;
  radioButton?: boolean;
  buttonValue?: string | null;
  exportValue?: string;
  options?: { exportValue: string; displayValue: string }[];
  readOnly?: boolean;
};

// Links in a form layer are not followed (the window never navigates); only the fields are put in it.
const noLinks = {
  externalLinkEnabled: false,
  addLinkAttributes: () => {},
  getDestinationHash: () => '',
  getAnchorUrl: () => '',
  goToDestination: () => {},
  executeNamedAction: () => {},
  executeSetOCGState: () => {},
};

/**
 * Lays the page's form fields over its picture as real inputs (pdf.js's annotation layer, form widgets only), so
 * they can be filled in. What is typed is kept in the document's annotation storage until the reader writes it into
 * the file. `container` gets the class "annotationLayer"; `forms` is the map the page was rendered with. Returns the
 * page's widgets (none: the layer stays empty).
 */
export async function renderFormLayer(doc: PDFDocumentProxy, index: number, container: HTMLElement, forms: Map<string, HTMLCanvasElement>): Promise<Widget[]> {
  const page = await doc.getPage(index + 1);
  const annotations = (await page.getAnnotations({ intent: 'display' })).filter((a) => a.annotationType === pdfjs.AnnotationType.WIDGET);
  container.classList.add('annotationLayer');
  if (!annotations.length) return [];
  const viewport = page.getViewport({ scale: 1 }).clone({ dontFlip: true });
  const layer = new pdfjs.AnnotationLayer({
    div: container, page, viewport, annotationCanvasMap: forms, annotationStorage: doc.annotationStorage,
    accessibilityManager: null, annotationEditorUIManager: null, structTreeLayer: null, commentManager: null, linkService: noLinks,
  } as unknown as ConstructorParameters<typeof pdfjs.AnnotationLayer>[0]);
  await layer.render({
    annotations, page, viewport, linkService: noLinks, renderForms: true, enableScripting: false, hasJSActions: false, fieldObjects: null,
    imageResourcesPath: '', downloadManager: null,
  } as unknown as Parameters<typeof layer.render>[0]);
  return annotations as unknown as Widget[];
}

/**
 * Lays the page's text over its picture as invisible, selectable text (pdf.js's text layer), so the user can select
 * words to mark up or copy. `container` gets the class "textLayer" and fills the page box; the page box must set
 * --total-scale-factor to its CSS pixels per point. Returns how many pieces of text the page has (0 = a scan).
 */
export async function renderTextLayer(doc: PDFDocumentProxy, index: number, container: HTMLElement): Promise<number> {
  const page = await doc.getPage(index + 1);
  const content = await page.getTextContent();
  container.classList.add('textLayer');
  await new pdfjs.TextLayer({ textContentSource: content, container, viewport: page.getViewport({ scale: 1 }) }).render();
  return content.items.filter((item) => 'str' in item && item.str.trim()).length;
}

/** A small picture of the first page, as an object URL, and the page count. */
export async function thumbnail(bytes: Uint8Array, width = 360): Promise<{ url: string; pages: number }> {
  const doc = await openPdf(bytes);
  try {
    const canvas = document.createElement('canvas');
    await (await renderPage(doc, 0, canvas, width, 1)).promise;
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.82));
    return { url: blob ? URL.createObjectURL(blob) : '', pages: doc.numPages };
  } finally {
    void doc.loadingTask.destroy();
  }
}

export type Match = { page: number; rects: { x: number; y: number; width: number; height: number }[] };

/** Pages whose text contains `query` (ignoring case), with where each match sits as fractions of the page. */
export async function search(doc: PDFDocumentProxy, query: string): Promise<Match[]> {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const matches: Match[] = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const viewport = page.getViewport({ scale: 1 });
    const content = await page.getTextContent();
    const rects: Match['rects'] = [];
    for (const item of content.items) {
      if (!('str' in item) || !item.str) continue;
      const text = item.str.toLowerCase();
      for (let at = text.indexOf(needle); at !== -1; at = text.indexOf(needle, at + needle.length)) {
        // The item's box in page space, narrowed to the matched characters by their share of the string.
        const start = (at / text.length) * item.width;
        const length = (needle.length / text.length) * item.width;
        const height = item.height || Math.hypot(item.transform[2], item.transform[3]);
        const angle = Math.atan2(item.transform[1], item.transform[0]);
        const x = item.transform[4] + Math.cos(angle) * start;
        const y = item.transform[5] + Math.sin(angle) * start;
        const corners = [
          [x, y - height * 0.2],
          [x + Math.cos(angle) * length - Math.sin(angle) * height, y + Math.sin(angle) * length + Math.cos(angle) * height],
        ].map(([px, py]) => viewport.convertToViewportPoint(px, py));
        const left = Math.min(corners[0][0], corners[1][0]);
        const top = Math.min(corners[0][1], corners[1][1]);
        rects.push({
          x: left / viewport.width,
          y: top / viewport.height,
          width: Math.abs(corners[0][0] - corners[1][0]) / viewport.width,
          height: Math.abs(corners[0][1] - corners[1][1]) / viewport.height,
        });
      }
    }
    if (rects.length) matches.push({ page: n - 1, rects });
  }
  return matches;
}

// Development only (never reached in a packaged app): the self-test and the screenshots.
//   PDFOUNDRY_SELFTEST=<result.json>  checks, in real pixels, that edits land where they were placed.
//   PDFOUNDRY_SHOTS=<folder>          fills the library with sample documents and captures each screen.
import { PDFDocument, StandardFonts, degrees, rgb } from '@cantoo/pdf-lib';
import { api } from './api.ts';
import type { AppInfo } from './api.ts';
import { placedFromPng, rasterize, textImage, toPng } from './images.ts';
import type { Library } from './library.ts';
import { openPdf, renderPage, search } from './pdfview.ts';
import type { Reader } from './reader.ts';
import type { SettingsView } from './settings.ts';
import type { ToolsView } from './tools.ts';
import type { Route } from './app.ts';
import * as pdf from '../core/pdf.ts';

type App = { library: Library; reader: Reader; settings: SettingsView; tools: ToolsView; show: (route: Route) => void };

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function runDemo(info: AppInfo, app: App): Promise<void> {
  const result: { checks: { name: string; pass: boolean; detail?: string }[]; error?: string } = { checks: [] };
  try {
    if (info.selfTest) await selfTest(result.checks);
    if (info.selfTest && !info.shots) await interfaceTest(result.checks, app);
    if (info.shots) await screenshots(app);
  } catch (e) {
    result.error = e instanceof Error ? `${e.message}\n${e.stack}` : String(e);
  }
  await api.devDone(result);
}

// Self-test

function solid(color: string, width = 40, height = 20): Promise<Uint8Array> {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = color;
  ctx.fillRect(0, 0, width, height);
  return toPng(canvas);
}

/** Renders a page at one pixel per point and returns a reader for its pixels, addressed in page fractions. */
async function pixels(bytes: Uint8Array, index: number): Promise<{ at: (x: number, y: number) => [number, number, number]; width: number; height: number }> {
  const doc = await openPdf(bytes);
  const page = await doc.getPage(index + 1);
  const size = page.getViewport({ scale: 1 });
  const canvas = document.createElement('canvas');
  await (await renderPage(doc, index, canvas, size.width, 1)).promise;
  const ctx = canvas.getContext('2d')!;
  void doc.loadingTask.destroy();
  return {
    width: canvas.width,
    height: canvas.height,
    at: (x, y) => {
      const d = ctx.getImageData(Math.round(x * canvas.width), Math.round(y * canvas.height), 1, 1).data;
      return [d[0], d[1], d[2]];
    },
  };
}

async function blank(width: number, height: number, pages = 1): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  for (let i = 0; i < pages; i++) doc.addPage([width, height]);
  return doc.save();
}

async function selfTest(checks: { name: string; pass: boolean; detail?: string }[]): Promise<void> {
  const check = (name: string, pass: boolean, detail?: string) => checks.push({ name, pass, detail });
  const isRed = (c: number[]) => c[0] > 200 && c[1] < 70 && c[2] < 70;
  const isWhite = (c: number[]) => c[0] > 245 && c[1] > 245 && c[2] > 245;
  const red = await solid('#ff0000');

  // A stamp in the top-right area of the displayed page must show up there, and only there, at every rotation.
  const rect = { x: 0.6, y: 0.1, width: 0.3, height: 0.15 };
  for (const turn of [0, 90, 180, 270]) {
    let doc = await blank(300, 500);
    if (turn) doc = await pdf.rotatePages(doc, [0], turn);
    doc = await pdf.stamp(doc, 0, red, rect);
    const px = await pixels(doc, 0);
    const shown = turn % 180 ? px.width === 500 && px.height === 300 : px.width === 300 && px.height === 500;
    const inside = [px.at(0.62, 0.12), px.at(0.75, 0.175), px.at(0.88, 0.23)];
    const outside = [px.at(0.5, 0.175), px.at(0.95, 0.175), px.at(0.75, 0.05), px.at(0.75, 0.3), px.at(0.25, 0.8), px.at(0.1, 0.1), px.at(0.9, 0.9)];
    check(`stamp lands in place on a page turned ${turn}°`, shown && inside.every(isRed) && outside.every(isWhite),
      JSON.stringify({ size: [px.width, px.height], inside, outside }));
  }

  // The same with a crop box that hides part of the page.
  {
    const made = await PDFDocument.create();
    made.addPage([400, 400]).setCropBox(100, 50, 200, 300);
    const doc = await pdf.stamp(await made.save(), 0, red, rect);
    const px = await pixels(doc, 0);
    check('stamp lands in place on a cropped page', px.width === 200 && px.height === 300 && isRed(px.at(0.75, 0.175)) && isWhite(px.at(0.3, 0.6)) && isWhite(px.at(0.5, 0.175)),
      JSON.stringify({ size: [px.width, px.height], inside: px.at(0.75, 0.175) }));
  }

  // A drawn line is saved where it was drawn.
  {
    const stroke = { points: [{ x: 0.1, y: 0.5 }, { x: 0.9, y: 0.5 }], color: '#000000', width: 6 / 300 };
    const image = (await rasterize([stroke], { width: 300, height: 500 }))!;
    const doc = await pdf.stamp(await blank(300, 500), 0, image.png, image.rect);
    const px = await pixels(doc, 0);
    const on = px.at(0.5, 0.5);
    check('a drawn line is saved where it was drawn', on[0] < 60 && on[1] < 60 && isWhite(px.at(0.5, 0.4)) && isWhite(px.at(0.5, 0.6)), JSON.stringify({ on }));
  }

  // A highlight darkens what is under it instead of hiding it.
  {
    const made = await PDFDocument.create();
    const page = made.addPage([300, 500]);
    page.drawRectangle({ x: 100, y: 240, width: 100, height: 20, color: rgb(0, 0, 0) }); // a black bar, like text
    const yellow = await solid('#ffe14d');
    const doc = await pdf.stamp(await made.save(), 0, yellow, { x: 0.2, y: 0.4, width: 0.6, height: 0.2 }, { multiply: true, opacity: 0.55 });
    const px = await pixels(doc, 0);
    const bar = px.at(0.5, 0.5);
    const paper = px.at(0.25, 0.42);
    check('a highlight tints the paper and keeps the text readable', bar[0] < 40 && bar[1] < 40 && paper[0] > 200 && paper[2] < 200 && paper[2] < paper[0], JSON.stringify({ bar, paper }));
  }

  // Typed text becomes ink, including right-to-left scripts.
  for (const [label, text] of [['Latin', 'Approved'], ['Arabic', 'تمت الموافقة']]) {
    const image = await textImage(text, '#000000', 14);
    const doc = await pdf.stamp(await blank(300, 500), 0, image.png, { x: 0.1, y: 0.1, width: 0.5, height: (0.5 * 300) / image.aspect / 500 });
    const px = await pixels(doc, 0);
    let dark = 0;
    for (let x = 0.1; x < 0.6; x += 0.004) for (let y = 0.1; y < 0.16; y += 0.004) if (px.at(x, y)[0] < 120) dark++;
    check(`typed ${label} text is drawn on the page`, dark > 20 && image.aspect > 1.5, JSON.stringify({ dark, aspect: image.aspect }));
  }

  // The viewer agrees with the engine about page counts after merging.
  {
    const merged = await pdf.merge([await blank(200, 200, 2), await blank(300, 300, 3)]);
    const doc = await openPdf(merged);
    check('a merged document opens with every page', doc.numPages === 5, String(doc.numPages));
    void doc.loadingTask.destroy();
  }
}

// The interface, driven the way a person would: pointer events on the page and clicks on the buttons.

function pointer(el: Element, type: string, x: number, y: number): void {
  el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0, pointerId: 1, isPrimary: true }));
}

function findButton(scope: ParentNode, label: string): HTMLButtonElement | undefined {
  return [...scope.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label);
}

function hasButton(scope: ParentNode, label: string): boolean {
  return !!findButton(scope, label);
}

function clickButton(scope: ParentNode, label: string): void {
  const found = findButton(scope, label);
  if (!found) throw new Error(`No "${label}" button`);
  if (found.disabled) throw new Error(`"${label}" is disabled`);
  found.click();
}

async function until(test: () => boolean, what: string): Promise<void> {
  for (let i = 0; i < 100; i++) {
    if (test()) return;
    await wait(50);
  }
  throw new Error(`Timed out waiting for ${what}`);
}

async function interfaceTest(checks: { name: string; pass: boolean; detail?: string }[], app: App): Promise<void> {
  const check = (name: string, pass: boolean, detail?: string) => checks.push({ name, pass, detail });
  const isWhite = (c: number[]) => c[0] > 245 && c[1] > 245 && c[2] > 245;
  const original = await blank(300, 500, 2);
  const file = await api.devCreate('Interface test', original);
  const onDisk = () => api.devRead(file);
  const same = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((v, i) => v === b[i]);
  await app.library.refresh();
  const card = () => document.querySelector('.card[aria-label="Interface test"]') as HTMLElement | null;
  check('the opened file is listed under Recent with its folder', !!card() && /Test files$/.test(card()!.querySelector('.folder')?.textContent ?? ''), card()?.title);
  card()!.click();
  await until(() => document.querySelectorAll('.page').length === 2, 'the reader');
  const dock = document.querySelector('.dock')!;
  const bar = document.querySelector('.reader-bar')!;
  const save = bar.querySelector('.save') as HTMLButtonElement;
  const page = () => document.querySelector('.page') as HTMLElement;
  const editedShown = () => !bar.querySelector('.edited')!.hasAttribute('hidden');
  const settled = () => until(() => !document.querySelector('.reader.busy') && !!document.querySelector('.dock .tool'), 'the change to be applied');

  // The freehand marker (Markup > Draw > Highlighter): drag across the middle of page 1, then Done. The file must not
  // change until Save.
  clickButton(dock, 'Markup');
  clickButton(dock, 'Draw');
  clickButton(dock, 'Highlighter');
  let box = page().getBoundingClientRect();
  pointer(page(), 'pointerdown', box.left + box.width * 0.2, box.top + box.height * 0.5);
  pointer(page(), 'pointermove', box.left + box.width * 0.5, box.top + box.height * 0.5);
  pointer(page(), 'pointermove', box.left + box.width * 0.8, box.top + box.height * 0.5);
  pointer(page(), 'pointerup', box.left + box.width * 0.8, box.top + box.height * 0.5);
  clickButton(dock, 'Done');
  await settled();
  check('after drawing, the bar is back in Markup', hasButton(dock, 'Underline') && hasButton(dock, 'Close Markup') && !hasButton(dock, 'Fill & Sign'));
  check('an edit does not touch the file until Save', same(await onDisk(), original) && app.reader.dirty && !save.disabled && editedShown());
  let px = await pixels(app.reader.data, 0);
  const tint = px.at(0.5, 0.5);
  check('a dragged highlight shows on the page', tint[0] > 200 && tint[2] < 200 && isWhite(px.at(0.5, 0.3)), JSON.stringify({ tint }));

  // Save writes it to the same file.
  save.click();
  await until(() => !app.reader.dirty, 'the save');
  px = await pixels(await onDisk(), 0);
  check('Save writes the changes back to the same file', px.at(0.5, 0.5)[2] < 200 && save.disabled && !editedShown());
  const afterHighlight = await onDisk();

  // Sign: start placing an image, drag it to the lower left, then Place.
  app.reader.beginPlacement(await placedFromPng(await solid('#0000ff', 120, 40)), 0.3);
  const placed = document.querySelector('.placement') as HTMLElement;
  box = page().getBoundingClientRect();
  let at = placed.getBoundingClientRect();
  pointer(placed, 'pointerdown', at.left + at.width / 2, at.top + at.height / 2);
  pointer(placed, 'pointermove', box.left + box.width * 0.3, box.top + box.height * 0.8);
  pointer(placed, 'pointerup', box.left + box.width * 0.3, box.top + box.height * 0.8);
  at = placed.getBoundingClientRect();
  const centre = { x: (at.left + at.width / 2 - box.left) / box.width, y: (at.top + at.height / 2 - box.top) / box.height };
  clickButton(dock, 'Place');
  await settled();
  px = await pixels(app.reader.data, 0);
  const ink = px.at(centre.x, centre.y);
  check('a dragged signature lands where it was dropped', Math.abs(centre.x - 0.3) < 0.02 && Math.abs(centre.y - 0.8) < 0.02 && ink[2] > 200 && ink[0] < 60 && isWhite(px.at(0.8, 0.8)),
    JSON.stringify({ centre, ink }));

  // Undo takes the signature away again, keeps the highlight, and the document is no longer "edited".
  clickButton(dock, 'Undo');
  await wait(500);
  px = await pixels(app.reader.data, 0);
  check('undo removes the last change only', isWhite(px.at(centre.x, centre.y)) && px.at(0.5, 0.5)[2] < 200);
  check('undoing back to the saved state clears the unsaved mark', !app.reader.dirty && same(await onDisk(), afterHighlight));

  // Organize pages (the reading bar's Pages button): rotate page 2, then delete page 1. Still nothing on disk until Save.
  clickButton(dock, 'Close Markup');
  clickButton(dock, 'Pages');
  await until(() => document.querySelectorAll('.organizer-page').length === 2, 'the organizer');
  const organizerEl = document.querySelector('.dialog.organizer')!;
  (document.querySelectorAll('.organizer-page')[1] as HTMLElement).click();
  clickButton(organizerEl, 'Rotate right');
  await until(() => !document.querySelector('.reader.busy') && document.querySelectorAll('.organizer-page.selected').length === 1, 'the rotation');
  await wait(300);
  (document.querySelectorAll('.organizer-page')[1] as HTMLElement).click(); // deselect page 2
  (document.querySelectorAll('.organizer-page')[0] as HTMLElement).click();
  clickButton(organizerEl, 'Delete');
  await until(() => document.querySelectorAll('.dialog').length === 2, 'the delete question');
  clickButton([...document.querySelectorAll('.dialog')].pop()!, 'Delete');
  await until(() => document.querySelectorAll('.organizer-page').length === 1, 'the page to go');
  clickButton(organizerEl, 'Done');
  let doc = await PDFDocument.load(app.reader.data);
  check('page changes apply to the open document', doc.getPageCount() === 1 && doc.getPage(0).getRotation().angle === 90);
  check('page changes are not on disk before Save', same(await onDisk(), afterHighlight) && app.reader.dirty);

  // Closing with unsaved changes asks. Cancel stays; Don't Save leaves the file alone.
  const closing = app.reader.close();
  await until(() => !!document.querySelector('.dialog'), 'the save question');
  check('closing with unsaved changes asks first', /Save the changes to/.test(document.querySelector('.dialog h2')?.textContent ?? ''));
  clickButton(document.querySelector('.dialog')!, 'Cancel');
  check('Cancel keeps the document open with its changes', (await closing) === false && app.reader.dirty && document.querySelectorAll('.page').length === 1);
  const closing2 = app.reader.close();
  await until(() => !!document.querySelector('.dialog'), 'the save question');
  clickButton(document.querySelector('.dialog')!, 'Don’t Save');
  check('Don’t Save closes and leaves the file as it was', (await closing2) === true && !app.reader.file && same(await onDisk(), afterHighlight));

  // Search finds text and marks it.
  const guide = await api.devCreate('Search test', await pdf.welcomeGuide());
  await app.reader.open(guide);
  app.reader.toggleFind(true);
  const find = document.querySelector('.find-bar input') as HTMLInputElement;
  find.value = 'signature';
  find.dispatchEvent(new Event('input', { bubbles: true }));
  await until(() => document.querySelectorAll('.match').length > 0 || /No matches/.test(document.querySelector('.find-status')!.textContent ?? ''), 'the search');
  check('search marks the matches on the page', document.querySelectorAll('.match').length >= 2, document.querySelector('.find-status')!.textContent ?? '');
  app.reader.toggleFind(false);
  await app.reader.close();

  await modesTest(check, app);
  await toolsTest(check, app);

  // The window may touch only files the user opened, and cannot reach the network.
  let refused = false;
  await api.read(file.replace('Interface test.pdf', '../settings.json')).catch(() => (refused = true));
  let refusedSave = false;
  await api.save(file.replace('Interface test.pdf', 'Never opened.pdf'), original).catch(() => (refusedSave = true));
  check('the window cannot read or write files the user did not open', refused && refusedSave);
  let offline = false;
  await fetch('https://example.com/').catch(() => (offline = true));
  check('the window cannot reach the network', offline);

  // Removing a file from the list does not delete it.
  await api.forget(file);
  await app.library.refresh();
  check('removing a file from the list leaves the file alone', !card() && same(await onDisk(), afterHighlight));
}

/** Three pages: words to mark up, the same on a page turned 90 degrees, and a scanned-looking page with no text. */
async function markupSample(): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  const first = doc.addPage([612, 792]);
  for (const [line, y] of [['Highlight Bravo here', 660], ['Underline Delta here', 560], ['Strike Echo here', 460]] as const) {
    first.drawText(line, { x: 72, y, size: 36, font, color: rgb(0, 0, 0) });
  }
  const turned = doc.addPage([612, 792]);
  turned.drawText('Turned Foxtrot page', { x: 72, y: 660, size: 36, font, color: rgb(0, 0, 0) });
  turned.setRotation(degrees(90));
  const scan = doc.addPage([612, 792]);
  scan.drawRectangle({ x: 72, y: 500, width: 400, height: 40, color: rgb(0.2, 0.2, 0.2) }); // ink, but no text
  return doc.save();
}

const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((v, i) => v === b[i]);

type Px = Awaited<ReturnType<typeof pixels>>;
type Box = { x: number; y: number; width: number; height: number };

/** The share of points on a grid over part of `box` (fractions of its width and height) that pass `test`. */
function share(px: Px, box: Box, from: [number, number], to: [number, number], test: (c: number[]) => boolean): number {
  let hit = 0;
  let all = 0;
  for (let fx = from[0]; fx <= to[0]; fx += 0.05) {
    for (let fy = from[1]; fy <= to[1]; fy += 0.05) {
      all++;
      if (test(px.at(box.x + fx * box.width, box.y + fy * box.height))) hit++;
    }
  }
  return all ? hit / all : 0;
}

/** Reader modes (Markup, Fill & Sign), signing twice in a row, and text markup on selected words. */
async function modesTest(check: (name: string, pass: boolean, detail?: string) => void, app: App): Promise<void> {
  const readerData = () => app.reader.data;
  const reader = (document.querySelector('.reader') as HTMLElement);
  const dock = reader.querySelector('.dock')!;
  const pageAt = (i: number) => reader.querySelectorAll<HTMLElement>('.page')[i];
  const settled = () => until(() => !reader.classList.contains('busy') && !!dock.querySelector('.tool'), 'the change to be applied');
  const isWhite = (c: number[]) => c[0] > 245 && c[1] > 245 && c[2] > 245;
  const isTinted = (c: number[]) => c[0] > 200 && c[2] < 190; // yellow over paper
  const isRed = (c: number[]) => c[0] > 180 && c[1] < 120 && c[2] < 120;

  const sample = await markupSample();
  const file = await api.devCreate('Markup test', sample);
  await app.reader.open(file);
  await until(() => reader.querySelectorAll('.page').length === 3, 'the markup sample');
  check('the reader opens in the reading bar', ['Markup', 'Fill & Sign', 'Pages', 'Undo'].every((l) => hasButton(dock, l)) && !hasButton(dock, 'Highlight'),
    dock.textContent ?? '');

  // Fill & Sign: place a signature, and the bar is still Fill & Sign, so the second one is one click away.
  await api.addSignature(await solid('#0000ff', 120, 40));
  clickButton(dock, 'Fill & Sign');
  const signAt = async (x: number, y: number): Promise<boolean> => {
    clickButton(dock, 'Signature');
    await until(() => !!document.querySelector('.signature-use'), 'the saved signatures');
    (document.querySelector('.signature-use') as HTMLElement).click();
    await until(() => !!document.querySelector('.placement'), 'the signature to place');
    const placed = document.querySelector('.placement') as HTMLElement;
    const box = pageAt(0).getBoundingClientRect();
    const at = placed.getBoundingClientRect();
    pointer(placed, 'pointerdown', at.left + at.width / 2, at.top + at.height / 2);
    pointer(placed, 'pointermove', box.left + box.width * x, box.top + box.height * y);
    pointer(placed, 'pointerup', box.left + box.width * x, box.top + box.height * y);
    clickButton(dock, 'Place');
    await settled();
    return hasButton(dock, 'Signature') && hasButton(dock, 'Close Fill & Sign');
  };
  const first = await signAt(0.3, 0.9);
  const second = await signAt(0.7, 0.9);
  let px = await pixels(readerData(), 0);
  const blue = (c: number[]) => c[2] > 200 && c[0] < 60;
  check('signing twice in a row stays in Fill & Sign', first && second && blue(px.at(0.3, 0.9)) && blue(px.at(0.7, 0.9)),
    JSON.stringify({ first, second, a: px.at(0.3, 0.9), b: px.at(0.7, 0.9) }));
  clickButton(dock, 'Close Fill & Sign');

  // Markup on selected text. Where the words are is worked out by pdf.js's search, not by the text layer that
  // the selection uses, so the check does not share the code it checks.
  const wordBox = async (word: string, index: number): Promise<Box> => {
    const doc = await openPdf(readerData());
    const found = (await search(doc, word)).find((m) => m.page === index)!;
    void doc.loadingTask.destroy();
    return found.rects[0];
  };
  const select = async (word: string, index: number) => {
    window.getSelection()?.removeAllRanges();
    pageAt(index).scrollIntoView();
    await until(() => [...pageAt(index).querySelectorAll('.textLayer span')].some((s) => s.textContent?.includes(word)), `the text of page ${index + 1}`);
    const span = [...pageAt(index).querySelectorAll('.textLayer span')].find((s) => s.textContent?.includes(word))!;
    const text = span.firstChild!;
    const range = document.createRange();
    const start = text.textContent!.indexOf(word);
    range.setStart(text, start);
    range.setEnd(text, start + word.length);
    window.getSelection()!.addRange(range);
  };
  const release = (index: number) => {
    const box = pageAt(index).getBoundingClientRect();
    pointer(pageAt(index), 'pointerup', box.left + box.width / 2, box.top + box.height / 2);
  };
  const changed = async (before: Uint8Array) => {
    await until(() => readerData() !== before && !reader.classList.contains('busy'), 'the mark');
    await wait(100);
  };
  clickButton(dock, 'Markup');

  // Highlight: select first, then choose a colour.
  let before = readerData();
  await select('Bravo', 0);
  clickButton(dock, 'Highlight');
  await until(() => !!document.querySelector('.menu'), 'the highlight colours');
  clickButton(document.querySelector('.menu')!, 'Yellow');
  await changed(before);
  let word = await wordBox('Bravo', 0);
  px = await pixels(readerData(), 0);
  const above = { ...word, y: word.y - word.height * 1.5 };
  const neighbour = { ...word, x: word.x - word.width * 1.3 }; // inside "Highlight", the word before: not marked
  const marked = { word: share(px, word, [0.1, 0.1], [0.9, 0.9], isTinted), above: share(px, above, [0, 0], [1, 1], isWhite), neighbour: share(px, neighbour, [0, 0], [1, 1], isTinted) };
  check('highlight marks the selected text', marked.word > 0.3 && marked.above > 0.9 && marked.neighbour < 0.1 && !window.getSelection()?.toString(),
    JSON.stringify({ box: word, ...marked, selection: window.getSelection()?.toString() }));

  // Underline and strikethrough: choose the tool, then select (applied when the mouse button goes up).
  clickButton(dock, 'Underline');
  before = readerData();
  await select('Delta', 0);
  release(0);
  await changed(before);
  word = await wordBox('Delta', 0);
  px = await pixels(readerData(), 0);
  const lineAt = (from: number, to: number) => {
    let best = 0;
    for (let fy = from; fy <= to; fy += 0.02) best = Math.max(best, share(px, word, [0.2, fy], [0.8, fy], isRed));
    return best;
  };
  check('underline draws a line under the selected text', lineAt(0.6, 1.15) > 0.7 && lineAt(-0.3, 0.3) < 0.2, JSON.stringify({ word, low: lineAt(0.6, 1.15), high: lineAt(-0.3, 0.3) }));
  clickButton(dock, 'Strikethrough');
  before = readerData();
  await select('Echo', 0);
  release(0);
  await changed(before);
  word = await wordBox('Echo', 0);
  px = await pixels(readerData(), 0);
  check('strikethrough draws a line through the selected text', lineAt(0.3, 0.7) > 0.7 && lineAt(1.1, 1.5) < 0.2 && lineAt(-0.5, -0.1) < 0.2,
    JSON.stringify({ word, middle: lineAt(0.3, 0.7) }));

  // On a page turned 90 degrees the words run down the screen; the highlight must still cover them.
  clickButton(dock, 'Highlight');
  await until(() => !!document.querySelector('.menu'), 'the highlight colours');
  clickButton(document.querySelector('.menu')!, 'Yellow');
  before = readerData();
  await select('Foxtrot', 1);
  release(1);
  await changed(before);
  word = await wordBox('Foxtrot', 1);
  px = await pixels(readerData(), 1);
  const beside = { ...word, x: word.x + word.width * 2 };
  check('highlight lands on the selected words on a turned page', word.height > word.width && share(px, word, [0.1, 0.1], [0.9, 0.9], isTinted) > 0.3
    && share(px, beside, [0, 0], [1, 1], isWhite) > 0.9, JSON.stringify({ word, tinted: share(px, word, [0.1, 0.1], [0.9, 0.9], isTinted) }));

  // A page with no text (a scan): nothing is marked, and a hint points to Draw.
  before = readerData();
  window.getSelection()?.removeAllRanges();
  pageAt(2).scrollIntoView();
  await until(() => pageAt(2).dataset.text !== undefined, 'the scanned page');
  const box = pageAt(2).getBoundingClientRect();
  pointer(pageAt(2), 'pointerdown', box.left + box.width * 0.3, box.top + box.height * 0.35);
  pointer(pageAt(2), 'pointerup', box.left + box.width * 0.7, box.top + box.height * 0.35);
  await until(() => /no text to mark up/.test(document.querySelector('.toast')?.textContent ?? ''), 'the no-text hint');
  check('a page with no text shows a hint and marks nothing', readerData() === before && /Use Draw/.test(document.querySelector('.toast')?.textContent ?? ''));

  // Leave without saving: the sample file is left as it was.
  const closing = app.reader.close();
  await until(() => !!document.querySelector('.dialog'), 'the save question');
  clickButton(document.querySelector('.dialog')!, 'Don’t Save');
  check('leaving without saving keeps the file as it was', (await closing) === true && sameBytes(await api.devRead(file), sample) && !app.reader.file);
}

/** The Tools view: today's tools, and Extract Pages from a recent file and from a picked one. Neither source changes. */
async function toolsTest(check: (name: string, pass: boolean, detail?: string) => void, app: App): Promise<void> {
  const rail = document.querySelector('.rail')!;
  const view = app.tools.el;
  const widths = async (bytes: Uint8Array) => (await PDFDocument.load(bytes)).getPages().map((p) => p.getWidth());
  const lastDialog = () => [...document.querySelectorAll<HTMLElement>('.dialog')].pop()!;
  const toastText = () => document.querySelector('.toast')?.textContent ?? '';

  const made = await PDFDocument.create();
  for (const width of [200, 300, 400]) made.addPage([width, 500]);
  const sourceBytes = await made.save();
  const source = await api.devCreate('Extract source', sourceBytes);
  const listedBefore = (await api.recent()).find((f) => f.path === source)!.opened;

  clickButton(rail, 'Tools');
  const sections = [...view.querySelectorAll('.tool-section h2')].map((e) => e.textContent);
  check('the Tools view lists today’s tools in their sections', !view.hidden && JSON.stringify(sections) === '["Create","Combine & split","Protect & optimize"]'
    && ['Create from Images', 'Merge PDFs', 'Extract Pages', 'Split PDF', 'Password-protect'].every((t) => hasButton(view, t)) && !hasButton(view, 'Scan Document'), JSON.stringify(sections));

  // From a recent file: pick it, tick pages 1 and 3, Extract, and say where to save.
  const extractFrom = async (choose: () => void, tick: number[], out: string) => {
    clickButton(view, 'Extract Pages');
    await until(() => !!document.querySelector('.dialog.picker'), 'the document picker');
    await until(() => document.querySelectorAll('.picker-row').length > 1, 'the recent files');
    choose();
    await until(() => document.querySelectorAll('.dialog.extract .organizer-page').length > 0, 'the pages to extract');
    for (const i of tick) (document.querySelectorAll('.dialog.extract .organizer-page')[i] as HTMLElement).click();
    await api.devAnswer({ save: out });
    clickButton(lastDialog(), 'Extract');
    await until(() => !document.querySelector('.dialog.extract') && /^Saved/.test(toastText()), 'the extracted file');
  };
  const out = source.replace(/[^\\/]+$/, 'Extract out.pdf');
  await extractFrom(() => clickButton(document.querySelector('.dialog.picker')!, 'Extract source'), [0, 2], out);
  const entry = (await api.recent()).find((f) => f.path === source);
  check('Extract Pages saves the chosen pages and leaves the source alone',
    sameBytes(await api.devRead(source), sourceBytes) && JSON.stringify(await widths(await api.devRead(out))) === '[200,400]'
    && !app.reader.file && !view.hidden && entry?.opened === listedBefore && /2 pages/.test(toastText()),
    JSON.stringify({ toast: toastText(), reader: app.reader.file, opened: [entry?.opened, listedBefore] }));

  // From Browse…: the picked file is read once and is not added to the recent list.
  const picked = await api.devWrite('Picked source', sourceBytes);
  const out2 = source.replace(/[^\\/]+$/, 'Extract out 2.pdf');
  await api.devAnswer({ pick: picked });
  await extractFrom(() => clickButton(document.querySelector('.dialog.picker')!, 'Browse…'), [1], out2);
  const recent = (await api.recent()).map((f) => f.path);
  let readable = true;
  await api.read(picked).catch(() => (readable = false));
  check('Extract Pages from a picked file does not list it or open it', sameBytes(await api.devRead(picked), sourceBytes) && !recent.includes(picked)
    && !readable && JSON.stringify(await widths(await api.devRead(out2))) === '[300]' && !app.reader.file, JSON.stringify({ recent, readable }));

  // The new file may never replace its source.
  await api.devAnswer({ save: source });
  clickButton(view, 'Extract Pages');
  await until(() => document.querySelectorAll('.picker-row').length > 1, 'the recent files');
  clickButton(document.querySelector('.dialog.picker')!, 'Extract source');
  await until(() => document.querySelectorAll('.dialog.extract .organizer-page').length > 0, 'the pages to extract');
  (document.querySelector('.dialog.extract .organizer-page') as HTMLElement).click();
  clickButton(lastDialog(), 'Extract');
  await until(() => /would replace a file it is made from/.test(toastText()), 'the refusal');
  check('Extract Pages never saves over its source', sameBytes(await api.devRead(source), sourceBytes) && !!document.querySelector('.dialog.extract'));
  clickButton(lastDialog(), 'Cancel');

  // The source open in the reader with an unsaved change (page 2 deleted): Extract takes the pages as they are shown,
  // says so, and its result may not replace the open file.
  await app.reader.open(source);
  await until(() => document.querySelectorAll('.reader .page').length === 3, 'the source in the reader');
  await app.reader.change((bytes) => pdf.deletePages(bytes, [1]));
  clickButton(rail, 'Tools');
  clickButton(view, 'Extract Pages');
  await until(() => document.querySelectorAll('.picker-row').length > 1, 'the recent files');
  clickButton(document.querySelector('.dialog.picker')!, 'Extract source');
  await until(() => document.querySelectorAll('.dialog.extract .organizer-page').length > 0, 'the pages to extract');
  const shown = document.querySelectorAll('.dialog.extract .organizer-page').length;
  const said = /unsaved changes/.test(document.querySelector('.dialog.extract')?.textContent ?? '');
  (document.querySelectorAll('.dialog.extract .organizer-page')[1] as HTMLElement).click();
  await api.devAnswer({ save: source });
  document.querySelector('.toast')?.remove();
  clickButton(lastDialog(), 'Extract');
  await until(() => /would replace a file it is made from, or the open document/.test(toastText()), 'the refusal');
  const out3 = source.replace(/[^\\/]+$/, 'Extract out 3.pdf');
  await api.devAnswer({ save: out3 });
  await until(() => !findButton(lastDialog(), 'Extract')?.disabled, 'Extract to be ready again');
  document.querySelector('.toast')?.remove();
  clickButton(lastDialog(), 'Extract');
  await until(() => !document.querySelector('.dialog.extract') && /^Saved/.test(toastText()), 'the extracted file');
  check('Extract Pages from the open document uses its unsaved changes and never saves over it',
    shown === 2 && said && JSON.stringify(await widths(await api.devRead(out3))) === '[400]' && sameBytes(await api.devRead(source), sourceBytes) && app.reader.dirty,
    JSON.stringify({ shown, said }));
  const closing = app.reader.close();
  await until(() => !!document.querySelector('.dialog'), 'the save question');
  clickButton(document.querySelector('.dialog')!, 'Don’t Save');
  await closing;
  app.show('documents');
}

// Screenshots

async function sampleDocument(heading: string, color: [number, number, number], pages: string[][], signLine = false): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const regular = await doc.embedFont(StandardFonts.Helvetica);
  pages.forEach((paragraphs, index) => {
    const page = doc.addPage([612, 792]);
    page.drawRectangle({ x: 0, y: 742, width: 612, height: 50, color: rgb(...color) });
    page.drawText(heading.toUpperCase(), { x: 56, y: 762, size: 10, font: bold, color: rgb(1, 1, 1) });
    let y = 690;
    paragraphs.forEach((text, i) => {
      if (i === 0) {
        page.drawText(text, { x: 56, y, size: 22, font: bold, color: rgb(0.09, 0.1, 0.13) });
        y -= 44;
      } else {
        page.drawText(text, { x: 56, y, size: 11.5, font: regular, color: rgb(0.2, 0.22, 0.27), maxWidth: 500, lineHeight: 17 });
        y -= 17 * Math.ceil(text.length / 88) + 16;
      }
    });
    if (signLine && index === pages.length - 1) {
      for (const [label, x] of [['Tenant', 56], ['Landlord', 330]] as const) {
        page.drawLine({ start: { x, y: 250 }, end: { x: x + 220, y: 250 }, thickness: 0.8, color: rgb(0.2, 0.22, 0.27) });
        page.drawText(label, { x, y: 234, size: 9.5, font: regular, color: rgb(0.45, 0.47, 0.52) });
      }
    }
    page.drawText(`Page ${index + 1} of ${pages.length}`, { x: 56, y: 40, size: 9, font: regular, color: rgb(0.55, 0.57, 0.62) });
  });
  return doc.save();
}

/** A handwriting-like signature, drawn as a path. */
async function sampleSignature(): Promise<Uint8Array> {
  const canvas = document.createElement('canvas');
  canvas.width = 600;
  canvas.height = 200;
  const ctx = canvas.getContext('2d')!;
  ctx.strokeStyle = '#1a3fb0';
  ctx.lineWidth = 5;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(30, 140);
  ctx.bezierCurveTo(60, 20, 110, 30, 100, 110);
  ctx.bezierCurveTo(95, 170, 150, 60, 175, 100);
  ctx.bezierCurveTo(195, 135, 210, 70, 240, 95);
  ctx.bezierCurveTo(270, 120, 280, 60, 315, 90);
  ctx.bezierCurveTo(350, 120, 370, 40, 400, 95);
  ctx.bezierCurveTo(430, 150, 480, 80, 570, 70);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(60, 165);
  ctx.quadraticCurveTo(300, 140, 540, 150);
  ctx.stroke();
  return toPng(canvas);
}

async function screenshots(app: App): Promise<void> {
  const shot = async (name: string) => {
    await wait(700);
    await api.devCapture(name);
  };
  const text = [
    'This agreement is made between the landlord and the tenant named below for the property described in section one. It begins on the first day of the month and runs for twelve months.',
    'The tenant agrees to pay the monthly rent on or before the first day of each month, to keep the property in good condition, and to give sixty days of written notice before leaving.',
    'The landlord agrees to keep the building, its plumbing, heating and electrical systems in working order, and to return the deposit within thirty days of the end of the tenancy.',
  ];
  await api.setAppearance('light');
  await api.clearRecent();
  await api.devCreate('Invoice 2041', await sampleDocument('Invoice', [0.11, 0.42, 0.33], [['Invoice 2041', 'Design work for the spring catalogue, forty hours at the agreed rate.', 'Payment is due within thirty days of the invoice date.']]));
  await api.devCreate('Project Proposal', await sampleDocument('Proposal', [0.36, 0.2, 0.55], [['Project Proposal', text[0], text[1]], ['Timeline', text[2], text[0]]]));
  await api.devCreate('Welcome to PDFoundry', await pdf.welcomeGuide());
  const rental = await api.devCreate('Rental Agreement', await sampleDocument('Rental agreement', [0.13, 0.33, 0.56],
    [['Rental Agreement', text[0], text[1], text[2]], ['Terms', text[1], text[2], text[0]], ['Signatures', 'Signed by the tenant and the landlord on the dates shown.']], true));

  await app.library.refresh();
  await wait(1500);
  await shot('01-library');

  // Sign and highlight the last page.
  const signature = await sampleSignature();
  await api.addSignature(signature);
  let bytes = await api.read(rental);
  bytes = await pdf.stamp(bytes, 2, signature, { x: 0.1, y: 0.61, width: 0.3, height: (0.3 * 612) / 3 / 792 });
  const mark = (await rasterize([{ points: [{ x: 0.1, y: 0.183 }, { x: 0.72, y: 0.183 }], color: '#ffe14d', width: 14 / 612 }], { width: 612, height: 792 }))!;
  bytes = await pdf.stamp(bytes, 2, mark.png, mark.rect, { multiply: true, opacity: 0.55 });
  await api.save(rental, bytes);
  await app.reader.open(rental);
  await wait(600);
  app.reader.zoomBy(0.6); // the whole page, so the highlight and the signature are both in view
  await wait(500);
  app.reader.scrollToPage(2);
  await shot('02-signed');

  // Markup: words selected on page 1 and highlighted, with the Markup bar showing.
  const dock = document.querySelector('.dock')!;
  app.reader.scrollToPage(0);
  app.reader.setMode('markup');
  const first = document.querySelectorAll<HTMLElement>('.page')[0];
  await until(() => !!first.querySelector('.textLayer span'), 'the text of page 1');
  const span = [...first.querySelectorAll('.textLayer span')].find((e) => e.textContent?.includes('monthly rent'));
  if (span?.firstChild) {
    const at = span.textContent!.indexOf('monthly rent');
    const range = document.createRange();
    range.setStart(span.firstChild, at);
    range.setEnd(span.firstChild, at + 'monthly rent'.length);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);
    const before = app.reader.data;
    clickButton(dock, 'Highlight');
    await until(() => !!document.querySelector('.menu'), 'the highlight colours');
    clickButton(document.querySelector('.menu')!, 'Yellow');
    await until(() => app.reader.data !== before && !document.querySelector('.reader.busy'), 'the highlight');
  }
  await shot('09-markup');

  // Fill & Sign on the last page, then placing a signature from it.
  app.reader.setMode('fillSign');
  app.reader.scrollToPage(2);
  await shot('10-fill-sign');
  app.reader.beginPlacement(await placedFromPng(signature), 0.3);
  await shot('03-place-signature');
  (document.querySelector('.dock .button:not(.primary)') as HTMLElement).click(); // Cancel
  app.reader.setMode('none');

  app.reader.organize();
  await wait(900);
  (document.querySelectorAll('.organizer-page')[1] as HTMLElement).click();
  await shot('04-pages');
  (document.querySelector('.dialog-actions .primary') as HTMLElement).click();

  await app.reader.undo(); // the highlight: leave the file as it is on disk
  await app.reader.close();
  await wait(500);
  (document.querySelector('.select-toggle') as HTMLElement).click();
  const cards = [...document.querySelectorAll<HTMLElement>('.card')];
  cards.filter((c) => /Rental|Invoice/.test(c.getAttribute('aria-label') ?? '')).forEach((c) => c.click());
  (document.querySelector('.selection-bar .primary') as HTMLElement).click();
  await shot('05-merge');
  (document.querySelector('.dialog-actions .button') as HTMLElement).click();
  (document.querySelector('.select-toggle') as HTMLElement).click();

  // Tools, and Extract Pages from the rental agreement.
  app.show('tools');
  await shot('11-tools');
  void app.tools.run('extract');
  await until(() => !!document.querySelector('.dialog.picker [aria-label="Rental Agreement"]'), 'the document picker');
  clickButton(document.querySelector('.dialog.picker')!, 'Rental Agreement');
  await until(() => document.querySelectorAll('.dialog.extract .organizer-page').length === 3, 'the pages to extract');
  for (const i of [0, 2]) (document.querySelectorAll('.dialog.extract .organizer-page')[i] as HTMLElement).click();
  await wait(600);
  await shot('12-extract');
  clickButton([...document.querySelectorAll('.dialog')].pop()!, 'Cancel');

  app.show('settings');
  await shot('06-settings');
  app.settings.showAbout();
  await shot('07-about');

  await api.setAppearance('dark');
  app.show('documents');
  await wait(400);
  await shot('08-library-dark');
  await api.setAppearance('system');
}

// Changing marks after they are placed (src/core/pdf.ts): move, resize and delete them; shapes and comments.
// A move or resize keeps the very same appearance (never redrawn), and text markup can only be deleted.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFDocument, PDFName, PDFRef, PDFStream, degrees } from '@cantoo/pdf-lib';
import type { PDFDict } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';
import { annotations, appearance, near, numbers, png, rectOf, streamText, subtype } from './helpers.ts';

async function page(width = 300, height = 400, setup?: (p: ReturnType<PDFDocument['addPage']>) => void): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const added = doc.addPage([width, height]);
  setup?.(added);
  return doc.save();
}

/** The annotation's appearance: its drawing and the bytes of every image it uses. */
function look(doc: PDFDocument, a: PDFDict): string {
  const ap = appearance(doc, a)!;
  const xobjects = (ap.dict.get(PDFName.of('Resources')) as PDFDict | undefined)?.get(PDFName.of('XObject')) as PDFDict | undefined;
  const parts = [streamText(ap)];
  for (const [, ref] of xobjects?.entries() ?? []) parts.push(Buffer.from((doc.context.lookup(ref as PDFRef) as PDFStream & { contents: Uint8Array }).contents).toString('base64'));
  return parts.join('|');
}

test('move, resize and delete, then Undo three times, give back the exact mark', async () => {
  // Undo in the reader is going back to the previous bytes; this checks each step changes only what it should.
  const placed = await pdf.stamp(await page(300, 400, (p) => { p.setRotation(degrees(90)); p.setCropBox(10, 20, 280, 360); }), 0, png(8, 4), { x: 0.2, y: 0.3, width: 0.2, height: 0.1 });
  const [mark] = await pdf.marks(placed, 0);
  const moved = await pdf.moveMark(placed, 0, mark.id, { x: 0.5, y: 0.6, width: 0.2, height: 0.1 });
  const resized = await pdf.moveMark(moved, 0, mark.id, { x: 0.5, y: 0.6, width: 0.4, height: 0.2 });
  const deleted = await pdf.deleteMark(resized, 0, mark.id);
  const docs = await Promise.all([placed, moved, resized].map((b) => PDFDocument.load(b)));
  const [a0, a1, a2] = docs.map((d) => annotations(d)[0]);
  assert.equal(look(docs[1], a1), look(docs[0], a0), 'moving keeps the appearance');
  assert.equal(look(docs[2], a2), look(docs[0], a0), 'resizing keeps the appearance');
  near((await pdf.marks(moved, 0))[0].rect, { x: 0.5, y: 0.6, width: 0.2, height: 0.1 }, 'moved', 1e-6);
  near((await pdf.marks(resized, 0))[0].rect, { x: 0.5, y: 0.6, width: 0.4, height: 0.2 }, 'resized', 1e-6);
  assert.equal((await pdf.marks(deleted, 0)).length, 0);
  assert.equal(annotations(await PDFDocument.load(deleted)).length, 0);
  // The deleted mark's appearance is gone from the file too.
  assert.ok(deleted.length < placed.length - 100, `${deleted.length} vs ${placed.length}`);
  // Same id all along, and the original is untouched by everything after it.
  assert.equal((await pdf.marks(resized, 0))[0].id, mark.id);
  near((await pdf.marks(placed, 0))[0].rect, mark.rect, 'original', 1e-9);
});

test('a moved drawing or line moves its points with it', async () => {
  let bytes = await pdf.ink(await page(), 0, [{ points: [{ x: 0.1, y: 0.1 }, { x: 0.3, y: 0.1 }], color: '#000000', width: 2 / 300 }]);
  bytes = await pdf.shape(bytes, 0, 'arrow', { x: 0.1, y: 0.5 }, { x: 0.3, y: 0.5 }, [0, 0, 0, 1]);
  const [inkMark, arrow] = await pdf.marks(bytes, 0);
  bytes = await pdf.moveMark(bytes, 0, inkMark.id, { ...inkMark.rect, x: inkMark.rect.x + 0.5 });
  bytes = await pdf.moveMark(bytes, 0, arrow.id, { ...arrow.rect, x: arrow.rect.x + 0.5 });
  const doc = await PDFDocument.load(bytes);
  const [i, l] = annotations(doc);
  const inkList = (i.lookup(PDFName.of('InkList')) as unknown as { asArray(): { asArray(): { asNumber(): number }[] }[] }).asArray()[0].asArray().map((n) => n.asNumber());
  assert.ok(Math.abs(inkList[0] - 180) < 0.5 && Math.abs(inkList[2] - 240) < 0.5, `ink points ${inkList}`);
  const line = numbers(l, 'L');
  assert.ok(Math.abs(line[0] - 180) < 0.5 && Math.abs(line[2] - 240) < 0.5, `line ${line}`);
});

test('text markup can be deleted but not moved', async () => {
  const marked = await pdf.markText(await page(), 0, [{ x: 0.1, y: 0.1, width: 0.3, height: 0.05 }], 'highlight', [1, 0.8, 0, 0.5]);
  const [mark] = await pdf.marks(marked, 0);
  assert.equal(mark.movable, false);
  await assert.rejects(pdf.moveMark(marked, 0, mark.id, { x: 0.5, y: 0.5, width: 0.3, height: 0.05 }), /can’t be moved/);
  assert.equal((await pdf.marks(await pdf.deleteMark(marked, 0, mark.id), 0)).length, 0);
});

test('a mark made by another app can be deleted, not moved', async () => {
  // An old-style stamp with no PDFoundry tag and no /NM.
  const doc = await PDFDocument.create();
  const p = doc.addPage([300, 400]);
  const ap = doc.context.register(doc.context.formXObject([], { BBox: [10, 10, 60, 40] }));
  p.node.set(PDFName.of('Annots'), doc.context.obj([doc.context.register(doc.context.obj({ Type: 'Annot', Subtype: 'Stamp', Rect: [10, 10, 60, 40], AP: { N: ap } }))]));
  const bytes = await doc.save();
  const [mark] = await pdf.marks(bytes, 0);
  assert.equal(mark.kind, 'stamp');
  assert.equal(mark.movable, false);
  assert.match(mark.id, /^ref-/);
  assert.equal((await pdf.marks(await pdf.deleteMark(bytes, 0, mark.id), 0)).length, 0);
  await assert.rejects(pdf.deleteMark(bytes, 0, 'pdfoundry-nothing'), /no longer there/);
});

test('marks written inline with no /NM get ids of their own, and Delete takes the right one', async () => {
  const doc = await PDFDocument.create();
  const p = doc.addPage([300, 400]);
  const stamp = (x: number) => doc.context.obj({ Type: 'Annot', Subtype: 'Stamp', Rect: [x, 10, x + 40, 40] });
  p.node.set(PDFName.of('Annots'), doc.context.obj([stamp(10), stamp(100)])); // dictionaries in the array, not references
  const bytes = await doc.save();
  const marks = await pdf.marks(bytes, 0);
  assert.equal(new Set(marks.map((m) => m.id)).size, 2, JSON.stringify(marks.map((m) => m.id)));
  assert.ok(marks.every((m) => m.id));
  const left = await pdf.marks(await pdf.deleteMark(bytes, 0, marks[1].id), 0);
  assert.equal(left.length, 1);
  assert.ok(Math.abs(left[0].rect.x - 10 / 300) < 1e-6, 'the first stamp is the one left');
});

test('shapes are real annotations with their colour and a 2 pt border', async () => {
  const graphite: pdf.Rgba = [0.07, 0.08, 0.11, 1];
  let bytes = await page();
  bytes = await pdf.shape(bytes, 0, 'rectangle', { x: 0.1, y: 0.1 }, { x: 0.4, y: 0.3 }, graphite);
  bytes = await pdf.shape(bytes, 0, 'ellipse', { x: 0.6, y: 0.3 }, { x: 0.9, y: 0.1 }, [1, 0.8, 0, 1]);
  bytes = await pdf.shape(bytes, 0, 'line', { x: 0.1, y: 0.5 }, { x: 0.9, y: 0.6 }, graphite);
  bytes = await pdf.shape(bytes, 0, 'arrow', { x: 0.1, y: 0.8 }, { x: 0.9, y: 0.7 }, graphite);
  const doc = await PDFDocument.load(bytes);
  const annots = annotations(doc);
  assert.deepEqual(annots.map(subtype), ['Square', 'Circle', 'Line', 'Line']);
  for (const a of annots) {
    assert.ok(appearance(doc, a));
    assert.equal((a.lookup(PDFName.of('BS')) as PDFDict).lookup(PDFName.of('W'))!.toString(), '2');
  }
  assert.deepEqual(numbers(annots[1], 'C'), [1, 0.8, 0]);
  assert.equal(annots[3].lookup(PDFName.of('LE'))!.toString(), '[ /None /OpenArrow ]');
  assert.equal(annots[2].lookup(PDFName.of('LE')), undefined);
  const marks = await pdf.marks(bytes, 0);
  assert.deepEqual(marks.map((m) => m.kind), ['rectangle', 'ellipse', 'line', 'arrow']);
  assert.ok(marks.every((m) => m.movable));
  // The rectangle's box is what was dragged (whichever way), plus half the border.
  near(marks[0].rect, { x: 0.1 - 1 / 300, y: 0.1 - 1 / 400, width: 0.3 + 2 / 300, height: 0.2 + 2 / 400 }, 'rectangle', 1e-6);
  near(marks[1].rect, { x: 0.6 - 1 / 300, y: 0.1 - 1 / 400, width: 0.3 + 2 / 300, height: 0.2 + 2 / 400 }, 'ellipse', 1e-6);
  // A line is from where the drag started to where it ended, in page space.
  assert.deepEqual(numbers(annots[2], 'L').map((n) => Math.round(n)), [30, 200, 270, 160]);
  await assert.rejects(pdf.shape(await page(), 0, 'rectangle', { x: 0.1, y: 0.1 }, { x: 0.1, y: 0.1 }, graphite), /nothing to place/);
});

test('a comment keeps its text, Arabic included, and can be changed', async () => {
  const arabic = 'مرحبا بالعالم';
  const bytes = await pdf.comment(await page(), 0, { x: 0.5, y: 0.5 }, arabic);
  const doc = await PDFDocument.load(bytes);
  const [note] = annotations(doc);
  assert.equal(subtype(note), 'Text');
  assert.equal(note.lookup(PDFName.of('Name'))!.toString(), '/Comment');
  assert.ok(appearance(doc, note));
  const [mark] = await pdf.marks(bytes, 0);
  assert.equal(mark.kind, 'comment');
  assert.equal(await pdf.commentText(bytes, 0, mark.id), arabic);
  // Its icon's top-left corner is where the page was clicked.
  assert.ok(Math.abs(mark.rect.x - 0.5) < 1e-6 && Math.abs(mark.rect.y - 0.5) < 1e-6, JSON.stringify(mark.rect));
  const changed = await pdf.updateComment(bytes, 0, mark.id, 'Check this figure');
  assert.equal(await pdf.commentText(changed, 0, mark.id), 'Check this figure');
  // Emptied, the comment goes.
  assert.equal((await pdf.marks(await pdf.updateComment(bytes, 0, mark.id, '  '), 0)).length, 0);
});

test('an empty comment is not created', async () => {
  const before = await page();
  assert.equal(await pdf.comment(before, 0, { x: 0.5, y: 0.5 }, '   '), before);
});

test('a comment on a turned page is placed and read back where it was clicked', async () => {
  const turned = await page(300, 400, (p) => p.setRotation(degrees(270)));
  const [mark] = await pdf.marks(await pdf.comment(turned, 0, { x: 0.2, y: 0.7 }, 'Hello'), 0);
  assert.ok(Math.abs(mark.rect.x - 0.2) < 1e-6 && Math.abs(mark.rect.y - 0.7) < 1e-6, JSON.stringify(mark.rect));
  // The icon is the same size on the screen whichever way the page is turned.
  assert.ok(Math.abs(mark.rect.width * 400 - mark.rect.height * 300) < 1e-6, JSON.stringify(mark.rect));
  const doc = await PDFDocument.load(await pdf.comment(turned, 0, { x: 0.2, y: 0.7 }, 'Hello'));
  assert.ok(rectOf(annotations(doc)[0]).width > 0);
});

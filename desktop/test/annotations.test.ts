// Marks are real PDF annotations (src/core/annotations.ts, pdf.ts): every kind is its own annotation with an
// appearance, tagged as PDFoundry's, and lands where it was placed on turned and cropped pages. The page's own
// drawing is never touched by a mark.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFBool, PDFDocument, PDFName, PDFNumber, PDFString, PDFHexString, degrees } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';
import { annotations, appearance, near, numbers, pageContent, png, rectOf, subtype } from './helpers.ts';

const YELLOW: pdf.Rgba = [1, 0.8, 0, 0.5];
const RED: pdf.Rgba = [1, 0.23, 0.19, 1];

async function page(width: number, height: number, setup?: (p: ReturnType<PDFDocument['addPage']>) => void): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const p = doc.addPage([width, height]);
  setup?.(p);
  return doc.save();
}

/** A 300 x 400 page cropped to x 20…280, y 40…360, turned 90°: shown 320 wide, 260 tall. */
const turnedCropped = () => page(300, 400, (p) => {
  p.setCropBox(20, 40, 260, 320);
  p.setRotation(degrees(90));
});

/** One of every mark kind there is so far, on page 0. */
async function everyKind(bytes: Uint8Array): Promise<Uint8Array> {
  let out = bytes;
  const word = { x: 0.1, y: 0.1, width: 0.3, height: 0.05 };
  out = await pdf.markText(out, 0, [word], 'highlight', YELLOW);
  out = await pdf.markText(out, 0, [{ ...word, y: 0.2 }], 'underline', RED);
  out = await pdf.markText(out, 0, [{ ...word, y: 0.3 }], 'strikethrough', RED);
  out = await pdf.stamp(out, 0, png(4, 2), { x: 0.5, y: 0.5, width: 0.2, height: 0.1 });
  out = await pdf.ink(out, 0, [{ points: [{ x: 0.1, y: 0.7 }, { x: 0.4, y: 0.8 }], color: '#1a3fb0', width: 2 / 300 }]);
  out = await pdf.ink(out, 0, [{ points: [{ x: 0.6, y: 0.7 }, { x: 0.9, y: 0.7 }], color: '#ffe14d', width: 14 / 300 }], { multiply: true, opacity: 0.55 });
  return out;
}

test('every mark kind is an annotation after save and reload', async () => {
  const saved = await everyKind(await page(300, 400));
  const doc = await PDFDocument.load(saved);
  const annots = annotations(doc);
  assert.deepEqual(annots.map(subtype), ['Highlight', 'Underline', 'StrikeOut', 'Stamp', 'Ink', 'Ink']);
  for (const a of annots) {
    assert.ok(appearance(doc, a), `${subtype(a)} has an appearance`);
    assert.equal((a.get(PDFName.of('F')) as PDFNumber).asNumber() & 4, 4, `${subtype(a)} prints`);
    assert.equal(a.get(PDFName.of('PDFoundry')), PDFBool.True, `${subtype(a)} is tagged`);
    const nm = a.lookup(PDFName.of('NM')) as PDFString | PDFHexString;
    assert.match(nm.decodeText(), /^pdfoundry-[0-9a-f-]{36}$/);
  }
  // Text markup has quad points; ink has its strokes.
  for (const a of annots.slice(0, 3)) assert.equal(numbers(a, 'QuadPoints').length, 8);
  assert.ok(annots[4].lookup(PDFName.of('InkList')));
  // What the app reads back.
  const marks = await pdf.marks(saved, 0);
  assert.deepEqual(marks.map((m) => m.kind), ['highlight', 'underline', 'strikethrough', 'stamp', 'ink', 'ink']);
  assert.deepEqual(marks.map((m) => m.movable), [false, false, false, true, true, true]);
  assert.equal(new Set(marks.map((m) => m.id)).size, 6);
  near(marks[3].rect, { x: 0.5, y: 0.5, width: 0.2, height: 0.1 }, 'stamp read back');
});

test('a mark never changes the page’s own drawing', async () => {
  const before = await page(300, 400, (p) => p.drawRectangle({ x: 1, y: 1, width: 5, height: 5 }));
  const content = async (bytes: Uint8Array) => {
    const d = await PDFDocument.load(bytes);
    return pageContent(d, 0);
  };
  assert.equal(await content(await everyKind(before)), await content(before));
});

test('marks land where they were placed on a turned, cropped page', async () => {
  const saved = await everyKind(await turnedCropped());
  const doc = await PDFDocument.load(saved);
  const p = doc.getPage(0);
  const expected = [
    { x: 0.1, y: 0.1, width: 0.3, height: 0.05 },
    { x: 0.1, y: 0.2, width: 0.3, height: 0.05 },
    { x: 0.1, y: 0.3, width: 0.3, height: 0.05 },
    { x: 0.5, y: 0.5, width: 0.2, height: 0.1 },
  ];
  const marks = await pdf.marks(saved, 0);
  expected.forEach((rect, i) => {
    near(marks[i].rect, rect, `${marks[i].kind} read back`, 0.02);
    near(rectOf(annotations(doc)[i]), pdf.userRect(p, rect), `${marks[i].kind} /Rect`, 0.5);
  });
  // Worked out by hand for this page: displayed x 0.5…0.7 of 320 = 160…224, y 0.5…0.6 of 260 = 130…156; turned 90°
  // the displayed x runs up the page from the crop box's bottom and the displayed y runs across from its left.
  near(rectOf(annotations(doc)[3]), { x: 20 + 130, y: 40 + 160, width: 26, height: 64 }, 'stamp by hand', 0.5);
  // The ink stroke covers its points (plus half its width) and nothing else.
  const ink = marks[4].rect;
  assert.ok(Math.abs(ink.x - 0.1) < 0.02 && Math.abs(ink.x + ink.width - 0.4) < 0.02 && Math.abs(ink.y - 0.7) < 0.02 && Math.abs(ink.y + ink.height - 0.8) < 0.02, JSON.stringify(ink));
});

test('the appearance is drawn in the box it is shown in', async () => {
  // Each appearance's bounding box is its /Rect with no transformation, so every viewer draws it in the same place.
  const doc = await PDFDocument.load(await everyKind(await turnedCropped()));
  for (const a of annotations(doc)) {
    const ap = appearance(doc, a)!;
    const [x1, y1, x2, y2] = numbers(ap.dict, 'BBox');
    near({ x: x1, y: y1, width: x2 - x1, height: y2 - y1 }, rectOf(a), `${subtype(a)} BBox`, 0.01);
  }
});

test('a highlight darkens like a marker; underline and strikethrough do not', async () => {
  const doc = await PDFDocument.load(await everyKind(await page(300, 400)));
  const [highlight, underline] = annotations(doc);
  // The appearance's resources are written inside its dictionary.
  const text = (a: typeof highlight) => appearance(doc, a)!.dict.toString();
  assert.match(text(highlight), /\/BM\s*\/Multiply/);
  assert.doesNotMatch(text(underline), /\/Multiply/);
});

test('every appearance has its own resources, so Apple’s PDF viewers draw it', async () => {
  // PDFKit (iPhone, iPad, Preview) draws nothing for a form XObject appearance without /Resources: ink, shapes and
  // comments were invisible there while pdf.js showed them.
  let bytes = await everyKind(await page(300, 400));
  for (const kind of ['rectangle', 'ellipse', 'line', 'arrow'] as const) {
    bytes = await pdf.shape(bytes, 0, kind, { x: 0.1, y: 0.85 }, { x: 0.3, y: 0.95 }, RED);
  }
  bytes = await pdf.comment(bytes, 0, { x: 0.8, y: 0.1 }, 'A note');
  const doc = await PDFDocument.load(bytes);
  const annots = annotations(doc);
  assert.equal(annots.length, 11);
  for (const a of annots) {
    const ap = appearance(doc, a);
    assert.ok(ap, `${subtype(a)} has an appearance`);
    assert.ok(ap.dict.lookup(PDFName.of('Resources')), `${subtype(a)} appearance has /Resources`);
  }
});

// The PDF engine (src/core/pdf.ts): pages, merging and where stamped images land.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { PDFDocument, StandardFonts, degrees } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';

/** A PDF whose pages are 100, 200, 300… points wide, so every page can be told apart after an operation. */
async function sample(pages: number, base = 100): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  for (let i = 0; i < pages; i++) doc.addPage([base * (i + 1), 500]).drawText(`page ${i + 1}`, { x: 20, y: 400, size: 12, font });
  return doc.save();
}

async function widths(bytes: Uint8Array): Promise<number[]> {
  return (await PDFDocument.load(bytes)).getPages().map((p) => p.getWidth());
}

/** A valid 1x1 red PNG. */
function png(): Uint8Array {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (b: Buffer) => (b.reduce((c, x) => crcTable[(c ^ x) & 0xff] ^ (c >>> 8), 0xffffffff) ^ 0xffffffff) >>> 0;
  const chunk = (type: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(type), data]);
    const out = Buffer.alloc(body.length + 8);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(crc(body), body.length + 4);
    return out;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(1, 0);
  header.writeUInt32BE(1, 4);
  header.set([8, 6, 0, 0, 0], 8);
  const pixels = deflateSync(Buffer.from([0, 255, 0, 0, 255]));
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', pixels), chunk('IEND', Buffer.alloc(0))]);
}

test('merge keeps every page in order', async () => {
  const merged = await pdf.merge([await sample(2), await sample(3, 10)]);
  assert.deepEqual(await widths(merged), [100, 200, 10, 20, 30]);
  await assert.rejects(pdf.merge([await sample(1)]), pdf.PdfError);
});

test('unreadable files are refused with a plain message', async () => {
  await assert.rejects(pdf.merge([await sample(1), new TextEncoder().encode('not a pdf')]), /not a PDF/);
  assert.equal(await pdf.isEditable(new TextEncoder().encode('not a pdf')), false);
  assert.equal(await pdf.isEditable(await sample(1)), true);
});

test('extract copies only the chosen pages and leaves the source alone', async () => {
  const source = await sample(4);
  assert.deepEqual(await widths(await pdf.extract(source, [3, 1])), [400, 200]);
  assert.equal(await pdf.pageCount(source), 4);
  await assert.rejects(pdf.extract(source, [9]), /no page 10/);
});

test('page operations change the document', async () => {
  let doc = await sample(3);
  doc = await pdf.movePage(doc, 0, 2);
  assert.deepEqual(await widths(doc), [200, 300, 100]);
  doc = await pdf.duplicatePages(doc, [0, 2]);
  assert.deepEqual(await widths(doc), [200, 200, 300, 100, 100]);
  doc = await pdf.deletePages(doc, [1, 4]);
  assert.deepEqual(await widths(doc), [200, 300, 100]);
  doc = await pdf.addBlankPage(doc, 0);
  assert.deepEqual(await widths(doc), [200, 200, 300, 100]);
  doc = await pdf.addBlankPage(doc, -1);
  assert.equal(await pdf.pageCount(doc), 5);
  await assert.rejects(pdf.deletePages(doc, [0, 1, 2, 3, 4]), /at least one page/);
});

test('rotation is kept between 0 and 270 and a blank page matches a turned neighbour', async () => {
  let doc = await pdf.rotatePages(await sample(1), [0], -90);
  assert.equal((await PDFDocument.load(doc)).getPage(0).getRotation().angle, 270);
  doc = await pdf.rotatePages(doc, [0], 180);
  assert.equal((await PDFDocument.load(doc)).getPage(0).getRotation().angle, 90);
  doc = await pdf.addBlankPage(doc, 0);
  const blank = (await PDFDocument.load(doc)).getPage(1);
  assert.deepEqual([blank.getWidth(), blank.getHeight()], [500, 100]); // the neighbour is shown 500 wide
});

test('an image lands where it was placed, whatever the page rotation', async () => {
  // A 200 x 400 page and an off-centre rectangle, so each rotation gives a different answer.
  // Worked out by hand from the displayed page: turned 90 degrees it is shown 400 wide and 200 tall, with the
  // unrotated bottom-left corner at the top-left of the display. (The app's self-test checks the same thing in pixels.)
  const rect = { x: 0.5, y: 0.1, width: 0.25, height: 0.2 };
  const expected: Record<number, { x: number; y: number; width: number; height: number; angle: number }> = {
    0: { x: 100, y: 280, width: 50, height: 80, angle: 0 },
    90: { x: 60, y: 200, width: 100, height: 40, angle: 90 },
    180: { x: 100, y: 120, width: 50, height: 80, angle: 180 },
    270: { x: 140, y: 200, width: 100, height: 40, angle: 270 },
  };
  for (const angle of [0, 90, 180, 270]) {
    const doc = await PDFDocument.create();
    const page = doc.addPage([200, 400]);
    page.setRotation(degrees(angle));
    const at = pdf.placement(page, rect);
    for (const key of ['x', 'y', 'width', 'height', 'angle'] as const) {
      assert.ok(Math.abs(at[key] - expected[angle][key]) < 1e-6, `rotation ${angle}: ${key} is ${at[key]}, expected ${expected[angle][key]}`);
    }
  }
});

test('placement follows the crop box', async () => {
  const doc = await PDFDocument.create();
  const page = doc.addPage([300, 300]);
  page.setCropBox(50, 100, 200, 100);
  assert.deepEqual(pdf.placement(page, { x: 0, y: 0, width: 1, height: 1 }), { x: 50, y: 100, width: 200, height: 100, angle: 0 });
});

test('a stamp is saved into the page', async () => {
  const before = await sample(1);
  const after = await pdf.stamp(before, 0, png(), { x: 0.2, y: 0.7, width: 0.3, height: 0.1 }, { multiply: true, opacity: 0.5 });
  assert.ok(after.length > before.length, 'the image is embedded');
  assert.match(Buffer.from(after).toString('latin1'), /\/Subtype\s*\/Image/);
  await assert.rejects(pdf.stamp(before, 0, png(), { x: 0, y: 0, width: 0, height: 0.1 }), /nothing to place/);
  await assert.rejects(pdf.stamp(before, 3, png(), { x: 0, y: 0, width: 0.1, height: 0.1 }), /no page 4/);
});

test('images become pages of their own size', async () => {
  const doc = await pdf.imagesToPdf([{ bytes: png(), type: 'png' }, { bytes: png(), type: 'png' }]);
  assert.deepEqual(await widths(doc), [1, 1]);
  await assert.rejects(pdf.imagesToPdf([{ bytes: new Uint8Array([1, 2, 3]), type: 'jpeg' }]), /could not be read/);
});

test('the welcome guide is a one-page PDF', async () => {
  assert.equal(await pdf.pageCount(await pdf.welcomeGuide()), 1);
});

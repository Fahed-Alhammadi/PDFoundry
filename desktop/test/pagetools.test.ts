// Page Numbers, Watermark (src/core/pagestamps.ts), Compress (src/core/compress.ts) and the names Export as Images
// gives its files (src/core/ranges.ts). Numbers and watermarks are drawn into the page content, where the reader
// sees them on turned pages too; Compress only re-encodes images and never touches the source.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { PDFDocument, PDFName, PDFNumber, PDFRawStream, StandardFonts, concatTransformationMatrix, degrees, drawObject, popGraphicsState, pushGraphicsState } from '@cantoo/pdf-lib';
import { compress } from '../src/core/compress.ts';
import type { CompressImage } from '../src/core/compress.ts';
import { addPageNumbers, addWatermark } from '../src/core/pagestamps.ts';
import * as pdf from '../src/core/pdf.ts';
import { exportNames, pixelSize } from '../src/core/ranges.ts';
import { everyStream, pageContent, pageTexts, png } from './helpers.ts';

/** Where pdf.js puts each piece of text, on the page as displayed: its middle and whether it reads across. */
type Placed = { page: number; text: string; x: number; y: number; across: boolean; angle: number; middle: { x: number; y: number } };
async function placed(bytes: Uint8Array, password?: string): Promise<Placed[]> {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const doc = await pdfjs.getDocument({ data: bytes.slice(), password, verbosity: 0 }).promise;
  const out: Placed[] = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const viewport = page.getViewport({ scale: 1 });
    for (const item of (await page.getTextContent()).items) {
      if (!('str' in item) || !item.str.trim()) continue;
      const [a, b, , , e, f] = item.transform;
      const length = Math.hypot(a, b);
      const start = viewport.convertToViewportPoint(e, f);
      const end = viewport.convertToViewportPoint(e + (a / length) * item.width, f + (b / length) * item.width);
      const angle = (Math.atan2(-(end[1] - start[1]), end[0] - start[0]) * 180) / Math.PI;
      // The middle of its capital letters (Helvetica's are 0.72 of the size tall), above the middle of its baseline.
      const raise = 0.359 * length;
      const middle = viewport.convertToViewportPoint(e + (a / length) * (item.width / 2) - (b / length) * raise, f + (b / length) * (item.width / 2) + (a / length) * raise);
      out.push({
        page: n - 1, text: item.str,
        x: (start[0] + end[0]) / 2 / viewport.width, y: (start[1] + end[1]) / 2 / viewport.height,
        across: Math.abs(end[1] - start[1]) < 0.5 && end[0] > start[0], angle,
        middle: { x: middle[0] / viewport.width, y: middle[1] / viewport.height },
      });
    }
  }
  await doc.loadingTask.destroy();
  return out;
}

async function pages(count: number, setup?: (doc: PDFDocument) => void): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  for (let i = 0; i < count; i++) doc.addPage([612, 792]);
  setup?.(doc);
  return doc.save();
}

test('page numbers land at the foot of each page as the reader sees it, turned pages too', async () => {
  const source = await pages(3, (doc) => doc.getPage(1).setRotation(degrees(90)));
  const out = await addPageNumbers(source, { position: 'bottom-center', format: 'page-n-of-total', startAt: 1, skipFirst: false });
  const found = await placed(out);
  assert.deepEqual(found.map((t) => t.text), ['Page 1 of 3', 'Page 2 of 3', 'Page 3 of 3']);
  for (const t of found) {
    assert.ok(t.across, `${t.text} reads across the page as shown`);
    assert.ok(Math.abs(t.x - 0.5) < 0.02, `${t.text} is centred: ${t.x}`);
  }
  // 36 pt from the foot of the page as shown: 792 tall upright, 612 tall turned.
  assert.ok(Math.abs(found[0].y - (792 - 36) / 792) < 0.01, `upright: ${found[0].y}`);
  assert.ok(Math.abs(found[1].y - (612 - 36) / 612) < 0.01, `turned: ${found[1].y}`);
});

test('page numbers: every position and format, a start number, and skipping the first page', async () => {
  const source = await pages(3);
  const corners = await placed(await addPageNumbers(source, { position: 'top-right', format: 'n', startAt: 1, skipFirst: false }));
  assert.deepEqual(corners.map((t) => t.text), ['1', '2', '3']);
  assert.ok(corners.every((t) => t.x > 0.9 && t.x < 1 - 36 / 612 + 0.01 && t.y < 0.1), JSON.stringify(corners[0]));
  const left = (await placed(await addPageNumbers(source, { position: 'bottom-left', format: 'page-n', startAt: 1, skipFirst: false })))[0];
  assert.equal(left.text, 'Page 1');
  assert.ok(left.x < 0.15 && left.y > 0.9, JSON.stringify(left));
  const skipped = await placed(await addPageNumbers(source, { position: 'bottom-center', format: 'n-of-total', startAt: 5, skipFirst: true }));
  assert.deepEqual(skipped.map((t) => [t.page, t.text]), [[1, '5 of 6'], [2, '6 of 6']]);
  await assert.rejects(addPageNumbers(await pages(1), { position: 'bottom-center', format: 'n', startAt: 1, skipFirst: true }), /no pages to number/);
});

test('a watermark is drawn on top, centred and see-through, diagonally or across', async () => {
  const source = await pages(2, (doc) => doc.getPage(1).setRotation(degrees(270)));
  const out = await addWatermark(source, { text: 'CONFIDENTIAL', opacity: 0.25, diagonal: true, color: 'graphite' });
  const found = await placed(out);
  assert.deepEqual(found.map((t) => t.text), ['CONFIDENTIAL', 'CONFIDENTIAL']);
  for (const t of found) {
    assert.ok(Math.abs(t.middle.x - 0.5) < 0.01 && Math.abs(t.middle.y - 0.5) < 0.01, `centred: ${JSON.stringify(t)}`);
  }
  // Bottom-left to top-right of the page as shown.
  assert.ok(Math.abs(found[0].angle - (Math.atan2(792, 612) * 180) / Math.PI) < 1, `angle ${found[0].angle}`);
  assert.ok(Math.abs(found[1].angle - (Math.atan2(612, 792) * 180) / Math.PI) < 1, `turned page angle ${found[1].angle}`);
  const doc = await PDFDocument.load(out);
  assert.match(pageContent(doc, 0), /\sgs\s/);
  // See-through: its graphics state (in the page's resources, written in an object stream).
  assert.match((await everyStream(out)).join('\n') + Buffer.from(out).toString('latin1'), /\/ca\s+0\.25/);
  const across = await placed(await addWatermark(source, { text: 'DRAFT', opacity: 0.5, diagonal: false, color: 'ember' }));
  assert.ok(across[0].across && Math.abs(across[0].angle) < 0.5, JSON.stringify(across[0]));
});

test('a watermark the standard font cannot write (Arabic) is drawn from the window’s picture', async () => {
  const source = await pages(2);
  await assert.rejects(addWatermark(source, { text: 'سري', opacity: 0.25, diagonal: true, color: 'graphite' }), /drawn by the window/);
  const image = png(40, 10, [20, 20, 30, 255]);
  const out = await addWatermark(source, { text: 'سري', opacity: 0.25, diagonal: true, color: 'graphite' }, image);
  const doc = await PDFDocument.load(out);
  for (const i of [0, 1]) assert.match(pageContent(doc, i), /\/\S+ Do/);
  assert.match(Buffer.from(out).toString('latin1'), /\/Subtype\s*\/Image/);
});

test('page numbers and a watermark keep a protected document’s password', async () => {
  const locked = await pdf.protect(await pages(2), 'secret');
  const open = (await pdf.unlock(locked, 'secret'))!;
  let bytes = await addPageNumbers(open.bytes, { position: 'bottom-center', format: 'n', startAt: 1, skipFirst: false });
  bytes = await addWatermark(bytes, { text: 'COPY', opacity: 0.25, diagonal: true, color: 'ember' });
  const saved = await pdf.protect(bytes, 'secret');
  assert.ok(await pdf.isProtected(saved));
  assert.equal(await pdf.unlock(saved, 'nope'), null);
  const texts = await pageTexts(saved, 'secret');
  assert.ok(texts[0].includes('1') && texts[0].includes('COPY') && texts[1].includes('2'), JSON.stringify(texts));
});

test('numbers are not moved by what a page’s drawing leaves behind, and repeated stamps do not nest', async () => {
  // A page whose content scales everything by 2 and never restores it (some PDF writers leave that behind).
  const doc = await PDFDocument.create();
  const p = doc.addPage([612, 792]);
  p.node.set(PDFName.of('Contents'), doc.context.register(doc.context.flateStream('2 0 0 2 0 0 cm 0 0 0 rg 10 10 20 20 re f')));
  const style = { position: 'bottom-center', format: 'n', startAt: 1, skipFirst: false } as const;
  let out = await addPageNumbers(await doc.save(), style);
  const [number] = await placed(out);
  assert.ok(Math.abs(number.x - 0.5) < 0.02 && Math.abs(number.y - (792 - 36) / 792) < 0.01, JSON.stringify(number));
  for (let i = 0; i < 4; i++) out = await addWatermark(out, { text: 'DRAFT', opacity: 0.25, diagonal: true, color: 'graphite' });
  let depth = 0;
  let most = 0;
  for (const t of pageContent(await PDFDocument.load(out)).split(/\s+/)) {
    depth += t === 'q' ? 1 : t === 'Q' ? -1 : 0;
    most = Math.max(most, depth);
  }
  assert.ok(most <= 3, `graphics states nest ${most} deep`);
});

// Compress

/** A page of text and a photo-like image (random pixels, which Flate cannot shrink), optionally with a soft mask. */
async function photoPdf(withMask = false): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const page = doc.addPage([612, 792]);
  page.drawText('Quarterly report text stays', { x: 72, y: 720, size: 18, font: await doc.embedFont(StandardFonts.Helvetica) });
  const [w, h] = [600, 400];
  const dict: Record<string, unknown> = { Type: 'XObject', Subtype: 'Image', Width: w, Height: h, ColorSpace: 'DeviceRGB', BitsPerComponent: 8 };
  if (withMask) {
    const mask = doc.context.flateStream(new Uint8Array(w * h).fill(200), { Type: 'XObject', Subtype: 'Image', Width: w, Height: h, ColorSpace: 'DeviceGray', BitsPerComponent: 8 });
    dict.SMask = doc.context.register(mask);
  }
  const image = doc.context.register(doc.context.flateStream(randomBytes(w * h * 3), dict as never));
  page.node.setXObject(PDFName.of('Photo'), image);
  page.pushOperators(pushGraphicsState(), concatTransformationMatrix(300, 0, 0, 200, 72, 300), drawObject('Photo'), popGraphicsState());
  return doc.save();
}

/** A 1 x 1 JPEG: what the window's canvas gives back, standing in for it here. */
const TINY_JPEG = Buffer.from('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=', 'base64');

test('Compress makes a smaller file with the text still there, and leaves the source alone', async () => {
  const source = await photoPdf(true);
  const copy = source.slice();
  const asked: CompressImage[] = [];
  const out = await compress(source, 'smaller', async (image) => {
    asked.push(image);
    return { jpeg: new Uint8Array(TINY_JPEG), width: 1, height: 1 };
  });
  assert.ok(out, 'a result');
  assert.ok(out!.length < source.length * 0.95, `${out!.length} vs ${source.length}`);
  assert.deepEqual(source, copy, 'the source bytes are not changed');
  assert.match((await pageTexts(out!))[0], /Quarterly report text stays/);
  // The photo was offered with its pixels, its size and the largest size the level allows (150 dpi of the page).
  const photo = asked.find((a) => a.width === 600)!;
  assert.equal(photo.components, 3);
  assert.equal(photo.pixels!.length, 600 * 400 * 3);
  assert.equal(photo.maxSide, Math.round((792 / 72) * 150));
  assert.equal(photo.quality, 0.6);
  // The mask itself is not offered (it stays), and the photo keeps it.
  assert.equal(asked.length, 1);
  const doc = await PDFDocument.load(out!);
  const images = [...doc.context.enumerateIndirectObjects()].map(([, o]) => o).filter((o): o is PDFRawStream => o instanceof PDFRawStream && o.dict.get(PDFName.of('Subtype')) === PDFName.of('Image'));
  const jpeg = images.find((i) => i.dict.get(PDFName.of('Filter')) === PDFName.of('DCTDecode'))!;
  assert.ok(jpeg, 'the photo is a JPEG now');
  assert.equal((jpeg.dict.get(PDFName.of('Width')) as PDFNumber).asNumber(), 1);
  assert.ok(jpeg.dict.get(PDFName.of('SMask')), 'it keeps its soft mask');
  assert.equal(jpeg.dict.get(PDFName.of('ColorSpace')), PDFName.of('DeviceRGB'));
});

test('Compress Smallest asks for 100 dpi at quality 0.4', async () => {
  let seen: CompressImage | null = null;
  await compress(await photoPdf(), 'smallest', async (image) => {
    seen = image;
    return { jpeg: new Uint8Array(TINY_JPEG), width: 1, height: 1 };
  });
  assert.equal(seen!.maxSide, Math.round((792 / 72) * 100));
  assert.equal(seen!.quality, 0.4);
});

test('Compress gives nothing back when the file would not be at least 5% smaller', async () => {
  const text = await pages(2, (doc) => doc.getPage(0).drawText('Only text here'));
  assert.equal(await compress(text, 'smaller', async () => ({ jpeg: new Uint8Array(TINY_JPEG), width: 1, height: 1 })), null);
  // An image the window can't re-encode (it says null) is left as it is.
  assert.equal(await compress(await photoPdf(), 'smaller', async () => null), null);
});

test('Compress keeps a protected source’s password on the new file', async () => {
  const locked = await pdf.protect(await photoPdf(), 'pw');
  const open = (await pdf.unlock(locked, 'pw'))!;
  const out = await compress(open.bytes, 'smaller', async () => ({ jpeg: new Uint8Array(TINY_JPEG), width: 1, height: 1 }));
  const saved = await pdf.protect(out!, 'pw');
  assert.ok(await pdf.isProtected(saved));
  assert.ok((await pdf.unlock(saved, 'pw'))!.full);
  assert.ok(saved.length < locked.length * 0.95);
});

// Export as Images

test('exported pages are named so they sort, and sized by the dots per inch', () => {
  assert.deepEqual(exportNames('Report', [0, 1, 2], 3, 'png'), ['Report page 1.png', 'Report page 2.png', 'Report page 3.png']);
  const twelve = exportNames('Report', Array.from({ length: 12 }, (_, i) => i), 12, 'jpg');
  assert.equal(twelve.length, 12);
  assert.equal(twelve[0], 'Report page 01.jpg');
  assert.equal(twelve[11], 'Report page 12.jpg');
  assert.deepEqual(exportNames('Big', [4, 99], 120, 'png'), ['Big page 005.png', 'Big page 100.png']);
  assert.deepEqual(pixelSize({ width: 612, height: 792 }, 300), { width: 2550, height: 3300 });
  assert.deepEqual(pixelSize({ width: 612, height: 792 }, 150), { width: 1275, height: 1650 });
});

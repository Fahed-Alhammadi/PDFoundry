// Text markup in the engine (src/core/pdf.ts): highlight, underline and strikethrough over selected words. The
// rectangles come from the window's text layer (fractions of the displayed page); they must land on the same words
// in the page's own coordinates, whatever its rotation and crop box.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFArray, PDFDocument, decodePDFRawStream, degrees } from '@cantoo/pdf-lib';
import type { PDFRawStream } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';

type Box = { x: number; y: number; width: number; height: number };

/** The page's drawing instructions as text. */
async function content(bytes: Uint8Array, index = 0): Promise<string> {
  const doc = await PDFDocument.load(bytes);
  const contents = doc.getPage(index).node.Contents();
  const streams = contents instanceof PDFArray ? contents.asArray().map((r) => doc.context.lookup(r)) : [contents];
  return streams.map((s) => new TextDecoder().decode(decodePDFRawStream(s as PDFRawStream).decode())).join('\n');
}

/** Every filled rectangle ("x y w h re") drawn on the page. */
async function rectangles(bytes: Uint8Array): Promise<Box[]> {
  const found = [...(await content(bytes)).matchAll(/(-?[\d.]+) (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) re/g)];
  return found.map((m) => ({ x: Number(m[1]), y: Number(m[2]), width: Number(m[3]), height: Number(m[4]) }));
}

function near(actual: Box, expected: Box, what: string): void {
  for (const key of ['x', 'y', 'width', 'height'] as const) {
    assert.ok(Math.abs(actual[key] - expected[key]) < 1e-3, `${what}: ${key} is ${actual[key]}, expected ${expected[key]}`);
  }
}

async function page(width: number, height: number, setup?: (p: ReturnType<PDFDocument['addPage']>) => void): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const p = doc.addPage([width, height]);
  setup?.(p);
  return doc.save();
}

const YELLOW: [number, number, number, number] = [1, 0.8, 0, 0.5];
const RED: [number, number, number, number] = [1, 0.23, 0.19, 1];

test('marks land on the rect on a rotated page', async () => {
  // A 200 x 400 page turned 90 degrees is shown 400 wide and 200 tall; upright text on it runs down the screen.
  // Worked out by hand: the displayed box x 200…220, y 20…120 is the page's own box x 20…120, y 200…220.
  const word = { x: 0.5, y: 0.1, width: 0.05, height: 0.5 };
  const turned = await page(200, 400, (p) => p.setRotation(degrees(90)));
  near(pdf.userRect((await PDFDocument.load(turned)).getPage(0), word), { x: 20, y: 200, width: 100, height: 20 }, 'user rect');
  const [highlight] = await rectangles(await pdf.markText(turned, 0, [word], 'highlight', YELLOW));
  near(highlight, { x: 20, y: 200, width: 100, height: 20 }, 'highlight');
  // The underline sits near the foot of the text (92% down), the strikethrough through its middle; both 7% thick.
  const [underline] = await rectangles(await pdf.markText(turned, 0, [word], 'underline', RED));
  near(underline, { x: 20, y: 200 + 20 * 0.08 - 0.7, width: 100, height: 1.4 }, 'underline');
  const [strike] = await rectangles(await pdf.markText(turned, 0, [word], 'strikethrough', RED));
  near(strike, { x: 20, y: 210 - 0.7, width: 100, height: 1.4 }, 'strikethrough');
});

test('marks land on the rect on a page turned 180 and 270 degrees', async () => {
  const word = { x: 0.5, y: 0.1, width: 0.25, height: 0.05 };
  // Turned 180: shown 200 x 400 upside down. Displayed x 100…150, y 40…60 is the page's x 50…100, y 40…60 (the top of the screen is the foot of the page).
  const upside = await page(200, 400, (p) => p.setRotation(degrees(180)));
  near((await rectangles(await pdf.markText(upside, 0, [word], 'highlight', YELLOW)))[0], { x: 50, y: 40, width: 50, height: 20 }, '180');
  // Turned 270: shown 400 x 200. Displayed x 200…300, y 20…30 is the page's x 170…180, y 100…200.
  const left = await page(200, 400, (p) => p.setRotation(degrees(270)));
  near((await rectangles(await pdf.markText(left, 0, [word], 'highlight', YELLOW)))[0], { x: 170, y: 100, width: 10, height: 100 }, '270');
});

test('marks land on the rect on a cropped page', async () => {
  // A 300 x 300 page cropped to x 50…250, y 100…200 is shown 200 x 100.
  const cropped = await page(300, 300, (p) => p.setCropBox(50, 100, 200, 100));
  const word = { x: 0.25, y: 0.5, width: 0.5, height: 0.25 };
  near((await rectangles(await pdf.markText(cropped, 0, [word], 'highlight', YELLOW)))[0], { x: 100, y: 125, width: 100, height: 25 }, 'cropped');
});

test('underline and strikethrough are lines; highlight darkens like a marker', async () => {
  const plain = await page(300, 500);
  const word = { x: 0.1, y: 0.1, width: 0.4, height: 0.04 }; // x 30…150, y 50…70 shown, so the page's y 430…450
  const highlighted = await pdf.markText(plain, 0, [word], 'highlight', YELLOW);
  near((await rectangles(highlighted))[0], { x: 30, y: 430, width: 120, height: 20 }, 'highlight');
  assert.match(Buffer.from(highlighted).toString('latin1'), /\/BM\s*\/Multiply/);
  assert.match(await content(highlighted), /1 0\.8 0 rg/);

  const under = await pdf.markText(plain, 0, [word], 'underline', RED);
  const [line] = await rectangles(under);
  near(line, { x: 30, y: 430 + 1.6 - 0.7, width: 120, height: 1.4 }, 'underline');
  assert.doesNotMatch(Buffer.from(under).toString('latin1'), /\/Multiply/);
  near((await rectangles(await pdf.markText(plain, 0, [word], 'strikethrough', RED)))[0], { x: 30, y: 440 - 0.7, width: 120, height: 1.4 }, 'strikethrough');

  // Several words are marked in one change.
  const two = await pdf.markText(plain, 0, [word, { x: 0.1, y: 0.2, width: 0.2, height: 0.04 }], 'highlight', YELLOW);
  assert.equal((await rectangles(two)).length, 2);
});

test('nothing selected, or a page that does not exist, is refused', async () => {
  const plain = await page(300, 500);
  await assert.rejects(pdf.markText(plain, 0, [], 'highlight', YELLOW), /Select some text/);
  await assert.rejects(pdf.markText(plain, 2, [{ x: 0, y: 0, width: 0.1, height: 0.1 }], 'highlight', YELLOW), /no page 3/);
  await assert.rejects(pdf.markText(plain, 0, [{ x: 0, y: 0, width: 0, height: 0.1 }], 'highlight', YELLOW), /Select some text/);
});

// Text markup in the engine (src/core/pdf.ts): highlight, underline and strikethrough over selected words. The
// rectangles come from the window's text layer (fractions of the displayed page); they must land on the same words
// in the page's own coordinates, whatever its rotation and crop box.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { PDFArray, PDFDocument, PDFName, decodePDFRawStream, degrees } from '@cantoo/pdf-lib';
import type { PDFRawStream } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';
import { joinBoxes } from '../src/core/rects.ts';

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

/** A valid 1x1 PNG. */
const PNG = (() => {
  const table = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (b: Buffer) => (b.reduce((c, x) => table[(c ^ x) & 0xff] ^ (c >>> 8), 0xffffffff) ^ 0xffffffff) >>> 0;
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
  return new Uint8Array(Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.from([0, 255, 0, 0, 255]))), chunk('IEND', Buffer.alloc(0))]));
})();

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

test('selection boxes on one line become one box, other lines stay apart', () => {
  // A range reports a fully selected span twice (its element box and its text box) and a word split over two spans.
  const line = { x: 0.1, y: 0.2, width: 0.3, height: 0.03 };
  const joined = joinBoxes([line, { ...line }, { x: 0.405, y: 0.201, width: 0.1, height: 0.029 }, { x: 0.1, y: 0.3, width: 0.2, height: 0.03 }], 'across');
  assert.equal(joined.length, 2);
  near(joined[0], { x: 0.1, y: 0.2, width: 0.405, height: 0.03 }, 'first line');
  near(joined[1], { x: 0.1, y: 0.3, width: 0.2, height: 0.03 }, 'second line');
  // Two lines set close together stay two lines.
  assert.equal(joinBoxes([{ x: 0.1, y: 0.2, width: 0.5, height: 0.03 }, { x: 0.1, y: 0.24, width: 0.5, height: 0.03 }], 'across').length, 2);
  // Slivers (empty line ends) are dropped.
  assert.equal(joinBoxes([{ x: 0.1, y: 0.1, width: 0, height: 0.03 }], 'across').length, 0);
});

test('narrow boxes on lines below each other are never joined', () => {
  // 12 pt text on 14.4 pt leading on a 612 x 792 page: "I" at the start of line 1, "a" at the start of line 2.
  const pt = (x: number, y: number, w: number, h: number) => ({ x: x / 612, y: y / 792, width: w / 612, height: h / 792 });
  assert.equal(joinBoxes([pt(72, 100, 3.3, 12), pt(72, 114.4, 6.7, 12)], 'across').length, 2);
  // A numbered list: "1." "2." "3." stay one box per line, and a selection over the whole list stays per line too.
  const numbers = [0, 1, 2].map((i) => pt(72, 100 + i * 14.4, 9, 12));
  assert.equal(joinBoxes(numbers, 'across').length, 3);
  const lines = [0, 1, 2].flatMap((i) => [pt(72, 100 + i * 14.4, 9, 12), pt(86, 100 + i * 14.4, 300, 12)]);
  const joined = joinBoxes(lines, 'across');
  assert.equal(joined.length, 3);
  for (const box of joined) assert.ok(box.height * 792 < 12.5, `a joined box spans more than one line: ${box.height * 792} pt`);
});

test('text that runs down a turned page is joined along its column, and columns stay apart', () => {
  assert.equal(joinBoxes([{ x: 0.5, y: 0.1, width: 0.04, height: 0.2 }, { x: 0.5, y: 0.305, width: 0.04, height: 0.1 }], 'down').length, 1);
  // Two lines of a turned page side by side.
  assert.equal(joinBoxes([{ x: 0.5, y: 0.1, width: 0.02, height: 0.3 }, { x: 0.525, y: 0.1, width: 0.02, height: 0.3 }], 'down').length, 2);
  // Single characters on neighbouring lines of a turned page stay apart too.
  assert.equal(joinBoxes([{ x: 0.5, y: 0.1, width: 0.02, height: 0.01 }, { x: 0.525, y: 0.1, width: 0.02, height: 0.012 }], 'down').length, 2);
});

/** Plays the page's drawing instructions and returns the transformation in force at each "q" made at the top level. */
async function topLevelSaves(bytes: Uint8Array): Promise<number[][]> {
  const tokens = (await content(bytes)).split(/\s+/).filter(Boolean);
  const multiply = (a: number[], b: number[]) => [
    a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
    a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5],
  ];
  let ctm = [1, 0, 0, 1, 0, 0];
  const stack: number[][] = [];
  const saves: number[][] = [];
  const operands: number[] = [];
  for (const token of tokens) {
    if (/^-?[\d.]+$/.test(token)) operands.push(Number(token));
    else {
      if (token === 'q') {
        if (!stack.length) saves.push(ctm);
        stack.push(ctm);
      } else if (token === 'Q') ctm = stack.pop() ?? ctm;
      else if (token === 'cm') ctm = multiply(operands.slice(-6), ctm);
      operands.length = 0;
    }
  }
  return saves;
}

/** How deep "q" nests at most in the page's drawing instructions. */
async function nesting(bytes: Uint8Array): Promise<number> {
  let depth = 0;
  let most = 0;
  for (const t of (await content(bytes)).split(/\s+/)) {
    depth += t === 'q' ? 1 : t === 'Q' ? -1 : 0;
    most = Math.max(most, depth);
  }
  return most;
}

/** A page whose content scales everything by 2 and never restores it (some PDF writers leave that behind). */
async function careless(): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const p = doc.addPage([300, 500]);
  p.node.set(PDFName.of('Contents'), doc.context.register(doc.context.flateStream('2 0 0 2 0 0 cm 0 0 0 rg 10 10 20 20 re f')));
  return doc.save();
}

test('marks and stamps are not moved by what a page leaves in its graphics state', async () => {
  const identity = [1, 0, 0, 1, 0, 0];
  const word = { x: 0.1, y: 0.1, width: 0.4, height: 0.04 };
  const marked = await pdf.markText(await careless(), 0, [word], 'highlight', YELLOW);
  assert.deepEqual((await topLevelSaves(marked)).pop(), identity, 'the highlight is drawn in the page’s own coordinates');
  const stamped = await pdf.stamp(await careless(), 0, PNG, { x: 0.1, y: 0.1, width: 0.2, height: 0.1 });
  assert.deepEqual((await topLevelSaves(stamped)).pop(), identity, 'the stamp is drawn in the page’s own coordinates');
  // Further edits do not wrap the page's content again: it is isolated once.
  let again = marked;
  for (let i = 0; i < 5; i++) again = await pdf.markText(again, 0, [word], 'underline', RED);
  assert.ok((await nesting(again)) <= 2, `graphics states nest ${await nesting(again)} deep`);
  // A page that already keeps its state to itself is left as it was.
  const tidy = await page(300, 500, (p) => p.drawRectangle({ x: 1, y: 1, width: 5, height: 5 }));
  const before = (await content(tidy)).trim();
  assert.ok((await content(await pdf.markText(tidy, 0, [word], 'highlight', YELLOW))).trim().startsWith(before));
});

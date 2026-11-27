// Make Searchable (OCR): the engine (src/main/ocr.ts, tesseract.js with the bundled English and Arabic data) finds
// words and where they are, with no network at all; the invisible text (src/core/ocrtext.ts) makes them findable
// without changing how the page looks; Cancel changes nothing; a protected file keeps its password.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { PDFDocument, StandardFonts } from '@cantoo/pdf-lib';
import { addInvisibleText, pagesWithoutText } from '../src/core/ocrtext.ts';
import * as pdf from '../src/core/pdf.ts';
import { pageContent, pageTexts } from './helpers.ts';

// Every thread OCR starts gets the network blocked too (tesseract.js reads its data in a worker thread).
const require = createRequire(import.meta.url);
const blocker = path.resolve('test/fixtures/no-network.cjs');
require(blocker);
const threads = require('node:worker_threads');
const Original = threads.Worker;
threads.Worker = class extends Original {
  constructor(file: string, options: { execArgv?: string[] } = {}) {
    super(file, { ...options, execArgv: [...(options.execArgv ?? []), '--require', blocker] });
  }
};
const { OcrEngine, ocrPaths } = await import('../src/main/ocr.ts');

const english = new Uint8Array(readFileSync('test/fixtures/ocr-english.png'));
const arabic = new Uint8Array(readFileSync('test/fixtures/ocr-arabic.png'));

/** Three pages the size of the pictures: a scan of English, a page with real text, a scan of Arabic. */
async function mixed(): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  for (const [i, picture] of [english, null, arabic].entries()) {
    const page = doc.addPage([900, 160]);
    if (picture) page.drawImage(await doc.embedPng(picture), { x: 0, y: 0, width: 900, height: 160 });
    else page.drawText(`Real text on page ${i + 1}`, { x: 40, y: 70, size: 30, font: await doc.embedFont(StandardFonts.Helvetica) });
  }
  return doc.save();
}

async function pdfjsDoc(bytes: Uint8Array) {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  return pdfjs.getDocument({ data: bytes.slice(), verbosity: 0 }).promise;
}

// The bundled data, copied the way the build copies it (to out/ocr), here into a folder of the test's own.
const { copyOcrData } = await import('../scripts/ocrdata.mjs' as string);
const data = mkdtempSync(path.join(tmpdir(), 'pdfoundry-ocr-'));
copyOcrData(path.join(data, 'out', 'ocr'));
const engine = new OcrEngine(ocrPaths({ appPath: data, packaged: false }));

test('the network really is blocked in OCR’s threads, and the engine refuses anything but a local folder', async () => {
  const error = await new Promise<Error>((resolve) => {
    const probe = new threads.Worker('fetch("https://example.com/eng.traineddata.gz")', { eval: true });
    probe.on('error', resolve);
    probe.on('exit', () => resolve(new Error('no error')));
  });
  assert.match(error.message, /tried to use the network/);
  assert.throws(() => new OcrEngine({ langPath: '' }), /folder on this computer/);
  assert.throws(() => new OcrEngine({ langPath: 'https://cdn.jsdelivr.net/npm/@tesseract.js-data/eng' }), /folder on this computer/);
});

test('only pages with no text need OCR', async () => {
  const doc = await pdfjsDoc(await mixed());
  assert.deepEqual(await pagesWithoutText(doc), [0, 2]);
  await doc.loadingTask.destroy();
});

test('English and Arabic words are found with where they are, using only the bundled files', { timeout: 120_000 }, async () => {
  const found = await engine.recognize([{ index: 0, png: english }, { index: 2, png: arabic }], () => {}, () => false);
  assert.ok(found, 'not cancelled');
  const [first, third] = found!;
  const words = first.words.map((w) => w.text);
  assert.ok(words.includes('Invoice') && words.includes('total'), JSON.stringify(words));
  const invoice = first.words.find((w) => w.text === 'Invoice')!;
  // Drawn from x 40 of 900, in the middle of a 160-high picture.
  assert.ok(Math.abs(invoice.box.x - 40 / 900) < 0.02 && invoice.box.y < 0.5 && invoice.box.y + invoice.box.height > 0.5, JSON.stringify(invoice.box));
  assert.ok(third.words.some((w) => w.text === 'فاتورة' && w.rtl), JSON.stringify(third.words));
});

test('invisible text makes scanned words findable without drawing anything', { timeout: 120_000 }, async () => {
  const source = await mixed();
  const found = (await engine.recognize([{ index: 0, png: english }, { index: 2, png: arabic }], () => {}, () => false))!;
  let out = source;
  for (const page of found) out = await addInvisibleText(out, page.index, page.words);
  const texts = await pageTexts(out);
  assert.match(texts[0], /Invoice/);
  assert.match(texts[0], /total/);
  assert.ok(texts[2].includes('فاتورة'), `Arabic in reading order: ${texts[2]}`);
  assert.equal(texts[1], (await pageTexts(source))[1], 'the page with text is not touched');
  // Invisible: text render mode 3, and nothing else is painted.
  const content = pageContent(await PDFDocument.load(out), 0);
  assert.match(content, /\b3 Tr\b/);
  assert.doesNotMatch(content.slice(content.indexOf('BT')), /\b(re|f|S|Do)\b/);
});

test('Cancel stops OCR and nothing comes back', { timeout: 120_000 }, async () => {
  let asked = 0;
  const found = await engine.recognize([{ index: 0, png: english }, { index: 2, png: arabic }], () => {}, () => ++asked > 1);
  assert.equal(found, null);
});

test('a protected file made searchable keeps its password', { timeout: 120_000 }, async () => {
  const locked = await pdf.protect(await mixed(), 'pw');
  const open = (await pdf.unlock(locked, 'pw'))!;
  const [page] = (await engine.recognize([{ index: 0, png: english }], () => {}, () => false))!;
  const saved = await pdf.protect(await addInvisibleText(open.bytes, 0, page.words), 'pw');
  assert.ok(await pdf.isProtected(saved));
  assert.equal(await pdf.unlock(saved, 'nope'), null);
  assert.match((await pageTexts(saved, 'pw'))[0], /Invoice/);
});

test('nothing found adds nothing', async () => {
  const source = await mixed();
  await assert.rejects(addInvisibleText(source, 0, []), /No text was found/);
});

test.after(async () => {
  await engine.terminate();
  rmSync(data, { recursive: true, force: true });
});

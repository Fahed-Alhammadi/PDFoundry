// Split PDF: reading the page ranges the user types, cutting a document into parts, and never touching the source.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFDocument } from '@cantoo/pdf-lib';
import { everyN, parseRanges } from '../src/core/ranges.ts';
import * as pdf from '../src/core/pdf.ts';

async function sample(pages: number): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  for (let i = 0; i < pages; i++) doc.addPage([100 * (i + 1), 500]);
  return doc.save();
}

const widths = async (bytes: Uint8Array) => (await PDFDocument.load(bytes)).getPages().map((p) => p.getWidth());

test('page ranges are read as 0-based page lists', () => {
  assert.deepEqual(parseRanges('1-3, 4-6, 9', 10), { parts: [[0, 1, 2], [3, 4, 5], [8]] });
  assert.deepEqual(parseRanges('2', 2), { parts: [[1]] });
  assert.deepEqual(parseRanges(' 1 - 2 ,2', 3), { parts: [[0, 1], [1]] }, 'spaces are ignored, overlaps allowed');
  assert.deepEqual(parseRanges('1-3,', 5), { parts: [[0, 1, 2]] }, 'a trailing comma is fine while typing');
});

test('bad page ranges say what is wrong', () => {
  assert.deepEqual(parseRanges('', 10), { error: 'Enter page ranges, such as 1-3, 4-6.' });
  assert.deepEqual(parseRanges('  ', 10), { error: 'Enter page ranges, such as 1-3, 4-6.' });
  assert.deepEqual(parseRanges('0', 10), { error: "Page 0 doesn't exist. This PDF has 10 pages." });
  assert.deepEqual(parseRanges('1-11', 10), { error: "Page 11 doesn't exist. This PDF has 10 pages." });
  assert.deepEqual(parseRanges('2', 1), { error: "Page 2 doesn't exist. This PDF has 1 page." });
  assert.deepEqual(parseRanges('5-3', 10), { error: 'Write ranges from low to high, such as 3-5.' });
  assert.deepEqual(parseRanges('a', 10), { error: 'Use numbers, dashes and commas, such as 1-3, 4-6.' });
  assert.deepEqual(parseRanges('1-2-3', 10), { error: 'Use numbers, dashes and commas, such as 1-3, 4-6.' });
  assert.deepEqual(parseRanges('1;2', 10), { error: 'Use numbers, dashes and commas, such as 1-3, 4-6.' });
});

test('every N pages makes consecutive parts, the last one shorter', () => {
  assert.deepEqual(everyN(5, 2), [[0, 1], [2, 3], [4]]);
  assert.deepEqual(everyN(3, 1), [[0], [1], [2]]);
  assert.deepEqual(everyN(3, 10), [[0, 1, 2]]);
  assert.throws(() => everyN(3, 0));
  assert.throws(() => everyN(3, 1.5));
});

test('split makes one document per part, with the right pages', async () => {
  const source = await sample(5);
  const parts = await pdf.split(source, everyN(5, 2));
  assert.equal(parts.length, 3);
  assert.deepEqual(await Promise.all(parts.map(widths)), [[100, 200], [300, 400], [500]]);
  const custom = await pdf.split(source, (parseRanges('5, 1-2', 5) as { parts: number[][] }).parts);
  assert.deepEqual(await Promise.all(custom.map(widths)), [[500], [100, 200]]);
  await assert.rejects(pdf.split(source, [[7]]), /no page 8/);
  await assert.rejects(pdf.split(source, []), /nothing to split/i);
});

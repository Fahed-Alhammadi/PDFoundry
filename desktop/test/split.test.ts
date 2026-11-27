// Split PDF: reading the page ranges the user types, cutting a document into parts, and never touching the source.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { PDFDocument } from '@cantoo/pdf-lib';
import { everyN, parseRanges } from '../src/core/ranges.ts';
import { Store } from '../src/main/store.ts';
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

const md5 = (bytes: Uint8Array) => createHash('md5').update(bytes).digest('hex');

test('splitNeverWritesTheSource', async () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-split-'));
  try {
    const desk = join(root, 'Desk');
    mkdirSync(desk);
    const source = join(desk, 'Report.pdf');
    writeFileSync(source, await sample(5));
    const before = md5(readFileSync(source));
    const store = new Store(join(root, 'data'));
    const bytes = store.readOnce(source); // picked with Browse…: read once, not listed
    const parts = await pdf.split(bytes, everyN(5, 2));
    const named = parts.map((b, i) => ({ name: `Report (part ${i + 1})`, bytes: b }));

    // Nothing exists yet; all three are written and listed, and the source is the same.
    assert.deepEqual(store.existing(desk, named.map((p) => p.name)), []);
    const written = store.saveParts(desk, named, false, [source]);
    assert.deepEqual(written.map((f) => basename(f)), ['Report (part 1).pdf', 'Report (part 2).pdf', 'Report (part 3).pdf']);
    assert.deepEqual(await Promise.all(written.map((f) => widths(readFileSync(f)))), [[100, 200], [300, 400], [500]]);
    assert.equal(md5(readFileSync(source)), before);
    assert.equal(store.knows(source), false, 'the source is not listed');
    assert.deepEqual(store.recent(), [], 'nor are the parts: a long split must not push the open document off the list');

    // Again: the names are taken now. Keep both adds a number; Replace writes over the old parts.
    assert.equal(store.existing(desk, named.map((p) => p.name)).length, 3);
    const kept = store.saveParts(desk, named, true, [source]);
    assert.deepEqual(kept.map((f) => basename(f)), ['Report (part 1) 2.pdf', 'Report (part 2) 2.pdf', 'Report (part 3) 2.pdf']);
    writeFileSync(written[0], 'old');
    store.saveParts(desk, named, false, [source]);
    assert.deepEqual(await widths(readFileSync(written[0])), [100, 200]);

    // A part named like the source is refused before anything is written.
    const clash = [{ name: 'Other', bytes: parts[0] }, { name: 'Report', bytes: parts[1] }];
    assert.throws(() => store.saveParts(desk, clash, false, [source]), /would replace the file it is made from/);
    assert.equal(existsSync(join(desk, 'Other.pdf')), false);
    assert.equal(md5(readFileSync(source)), before, 'the source is byte for byte the same');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('the open document stays on the recent list, and parts never replace a recent file', async () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-recent-'));
  try {
    const store = new Store(join(root, 'data'));
    const one = await sample(1);
    const open = join(root, 'Open.pdf');
    writeFileSync(open, one);
    store.remember(open);
    store.setCurrent(open);
    for (let i = 0; i < 70; i++) {
      const f = join(root, `File ${i}.pdf`);
      writeFileSync(f, one);
      store.remember(f);
    }
    assert.equal(store.knows(open), true, 'pushed past 60 files, the open document is still known');
    store.save(open, await sample(2));
    assert.equal(store.knows(join(root, 'File 0.pdf')), false, 'others are trimmed as before');
    // Parts named like a recent file (or the open document) are refused before anything is written.
    const recentName = [{ name: 'Fresh', bytes: one }, { name: 'File 69', bytes: one }];
    assert.throws(() => store.saveParts(root, recentName, false, []), /recent files/);
    assert.throws(() => store.saveParts(root, [{ name: 'Open', bytes: one }], false, []), /open document/);
    assert.equal(existsSync(join(root, 'Fresh.pdf')), false);
    store.setCurrent(null);
    store.setCurrent(join(root, 'Never listed.pdf'));
    assert.equal(store.knows(join(root, 'Never listed.pdf')), false, 'only a listed file can be the current one');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('aFailedProtectedSaveLeavesTheOldFile', async () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-protected-save-'));
  const fs = createRequire(import.meta.url)('node:fs') as typeof import('node:fs');
  const real = { rename: fs.renameSync, write: fs.writeFileSync };
  try {
    const file = join(root, 'Lease.pdf');
    writeFileSync(file, await pdf.protect(await sample(3), 'pw'));
    const before = readFileSync(file);
    const store = new Store(join(root, 'data'));
    store.remember(file);
    const open = (await pdf.unlock(before, 'pw'))!;
    const edited = await pdf.protect(await pdf.deletePages(open.bytes, [0]), 'pw');

    // The disk fills up half way through writing, and then the last step (the rename) fails.
    fs.writeFileSync = ((target: string, data: Uint8Array, ...rest: unknown[]) => {
      if (String(target).endsWith('.part')) {
        real.write(target, data.subarray(0, data.length >> 1));
        throw Object.assign(new Error('no space left'), { code: 'ENOSPC' });
      }
      return (real.write as (...a: unknown[]) => void)(target, data, ...rest);
    }) as typeof fs.writeFileSync;
    syncBuiltinESMExports();
    assert.throws(() => store.save(file, edited), /no space left/);
    fs.writeFileSync = real.write;
    fs.renameSync = (() => {
      throw Object.assign(new Error('rename failed'), { code: 'EIO' });
    }) as typeof fs.renameSync;
    syncBuiltinESMExports();
    assert.throws(() => store.save(file, edited), /rename failed/);
    fs.renameSync = real.rename;
    syncBuiltinESMExports();

    const after = readFileSync(file);
    assert.equal(md5(after), md5(before), 'the old protected file is untouched');
    assert.equal(await pdf.isProtected(after), true);
    assert.equal(await pdf.pageCount((await pdf.unlock(after, 'pw'))!.bytes), 3);
    assert.deepEqual(readdirSync(root).filter((f) => f.endsWith('.part')), [], 'no half-written file is left behind');

    // And a save that works keeps the password.
    store.save(file, edited);
    assert.equal(await pdf.unlock(readFileSync(file), 'wrong'), null);
    assert.equal(await pdf.pageCount((await pdf.unlock(readFileSync(file), 'pw'))!.bytes), 2);
  } finally {
    fs.renameSync = real.rename;
    fs.writeFileSync = real.write;
    syncBuiltinESMExports();
    rmSync(root, { recursive: true, force: true });
  }
});

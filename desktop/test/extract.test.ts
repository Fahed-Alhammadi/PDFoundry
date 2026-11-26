// Extract Pages from the Tools view: the source is only read. It is never written, never listed as recent (which
// would also give the window lasting access to it), and the new file can never be saved over it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PDFDocument } from '@cantoo/pdf-lib';
import { Store } from '../src/main/store.ts';
import * as pdf from '../src/core/pdf.ts';

const md5 = (bytes: Uint8Array) => createHash('md5').update(bytes).digest('hex');

async function sample(pages: number): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  for (let i = 0; i < pages; i++) doc.addPage([100 * (i + 1), 500]);
  return doc.save();
}

test('extractLeavesTheSourceUnchanged', async () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-extract-'));
  try {
    const desk = join(root, 'Desk');
    mkdirSync(desk);
    const source = join(desk, 'Report.pdf');
    writeFileSync(source, await sample(4));
    const before = md5(readFileSync(source));
    const store = new Store(join(root, 'data'));

    // Read once, right after the user picked it: not on the recent list, so the window cannot read or save it later.
    const bytes = store.readOnce(source);
    assert.equal(md5(bytes), before);
    assert.deepEqual(store.recent(), []);
    assert.equal(store.knows(source), false);
    assert.throws(() => store.read(source), /not open/);
    assert.throws(() => store.save(source, bytes), /not open/);

    // The new file: pages 1 and 3. Saving it over the source is refused; anywhere else is fine.
    const out = await pdf.extract(bytes, [0, 2]);
    assert.throws(() => store.saveNew(source, out, [source]), /would replace a file it is made from/);
    assert.throws(() => store.saveNew(join(desk, "Report"), out, [source]), /would replace a file it is made from/); // ".pdf" is added
    const target = store.saveNew(join(desk, "Report pages.pdf"), out, [source]);

    assert.equal(md5(readFileSync(source)), before, 'the source is byte for byte the same');
    assert.deepEqual((await PDFDocument.load(readFileSync(target))).getPages().map((p) => p.getWidth()), [100, 300]);
    assert.deepEqual(store.recent().map((f) => f.path), [target], 'only the new file is listed');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a merged file is never saved over one of the files it is made from', async () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-merge-'));
  try {
    const store = new Store(join(root, 'data'));
    const [a, b] = [join(root, 'A.pdf'), join(root, 'B.pdf')];
    writeFileSync(a, await sample(1));
    writeFileSync(b, await sample(2));
    const [ha, hb] = [md5(readFileSync(a)), md5(readFileSync(b))];
    const merged = await pdf.merge([readFileSync(a), readFileSync(b)]);
    assert.throws(() => store.saveNew(b, merged, [a, b]), /would replace a file it is made from/);
    assert.throws(() => store.saveNew(join(root, 'a.pdf'), merged, [a, b]), process.platform === 'linux' ? /already exists/ : /would replace/);
    assert.deepEqual([md5(readFileSync(a)), md5(readFileSync(b))], [ha, hb]);
    store.saveNew(join(root, 'Merged.pdf'), merged, [a, b]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a name typed without ".pdf" never silently replaces an existing file', async () => {
  // The Linux save dialog does not add the extension, so it never asked about replacing "Report.pdf".
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-name-'));
  try {
    const store = new Store(join(root, 'data'));
    const existing = join(root, 'Report.pdf');
    writeFileSync(existing, await sample(3));
    const before = md5(readFileSync(existing));
    const one = await sample(1);
    assert.throws(() => store.saveNew(join(root, 'Report'), one), /“Report.pdf” already exists/);
    assert.equal(md5(readFileSync(existing)), before);
    // With the extension typed, the system dialog has already asked, so the file is replaced as the user chose.
    store.saveNew(existing, await sample(1));
    assert.notEqual(md5(readFileSync(existing)), before);
    // A new name without ".pdf" is fine.
    assert.match(store.saveNew(join(root, 'Fresh'), await sample(1)), /Fresh\.pdf$/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a file that is not a PDF is not read', () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-extract-'));
  try {
    const store = new Store(join(root, 'data'));
    const note = join(root, 'note.pdf');
    writeFileSync(note, 'hello');
    assert.throws(() => store.readOnce(note), /not a PDF/);
    assert.throws(() => store.readOnce('relative.pdf'), /could not be found/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

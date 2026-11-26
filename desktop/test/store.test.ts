// The library on disk (src/main/store.ts): names, and bringing files in without duplicating them.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, utimesSync, writeFileSync, renameSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store, cleanName } from '../src/main/store.ts';

function setup() {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-store-'));
  const outside = join(root, 'outside');
  mkdirSync(outside);
  const store = new Store(join(root, 'data'));
  const file = (name: string, body: string) => {
    const path = join(outside, name);
    writeFileSync(path, `%PDF-1.7\n${body}`);
    return path;
  };
  return { store, file, outside, done: () => rmSync(root, { recursive: true, force: true }) };
}

const names = (store: Store) => store.list().map((d) => d.name).sort();

test('opening the same file again does not add it again', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'one');
    assert.deepEqual(t.store.importFile(report), { name: 'Report.pdf', existing: false });
    assert.deepEqual(t.store.importFile(report), { name: 'Report.pdf', existing: true });
    assert.deepEqual(t.store.importFile(report), { name: 'Report.pdf', existing: true });
    assert.deepEqual(names(t.store), ['Report.pdf']);
  } finally {
    t.done();
  }
});

test('the same file is still recognised after its document was edited or renamed here', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'one');
    t.store.importFile(report);
    t.store.write('Report.pdf', new TextEncoder().encode('%PDF-1.7\nsigned in the app'));
    assert.deepEqual(t.store.importFile(report), { name: 'Report.pdf', existing: true });
    const renamed = t.store.rename('Report.pdf', 'Signed report');
    assert.deepEqual(t.store.importFile(report), { name: renamed, existing: true });
    assert.deepEqual(names(t.store), ['Signed report.pdf']);
  } finally {
    t.done();
  }
});

test('the same contents from another place are not added twice', () => {
  const t = setup();
  try {
    const first = t.file('Report.pdf', 'same contents');
    t.store.importFile(first);
    const moved = join(t.outside, 'Report (copy).pdf');
    renameSync(first, moved);
    assert.deepEqual(t.store.importFile(moved), { name: 'Report.pdf', existing: true });
    assert.deepEqual(names(t.store), ['Report.pdf']);
  } finally {
    t.done();
  }
});

test('a file that changed on disk, or a different file with the same name, is added as a new document', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'version one');
    t.store.importFile(report);
    writeFileSync(report, '%PDF-1.7\nversion two, longer');
    utimesSync(report, new Date(), new Date(Date.now() + 5000));
    assert.deepEqual(t.store.importFile(report), { name: 'Report 2.pdf', existing: false });
    assert.deepEqual(t.store.importFile(report), { name: 'Report 2.pdf', existing: true });
    assert.deepEqual(names(t.store), ['Report 2.pdf', 'Report.pdf']);
  } finally {
    t.done();
  }
});

test('after a document is deleted, its file can be brought in again', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'one');
    t.store.importFile(report);
    rmSync(t.store.path('Report.pdf'));
    assert.deepEqual(t.store.importFile(report), { name: 'Report.pdf', existing: false });
  } finally {
    t.done();
  }
});

test('files that are not PDFs are refused, and names stay inside the library', () => {
  const t = setup();
  try {
    const notPdf = join(t.outside, 'notes.pdf');
    writeFileSync(notPdf, 'plain text');
    assert.throws(() => t.store.importFile(notPdf), /not a PDF/);
    assert.equal(cleanName('../../etc/passwd'), 'etc passwd');
    assert.equal(cleanName('  Invoice.pdf '), 'Invoice');
    assert.throws(() => t.store.read('../settings.json'), /not a document/);
    assert.equal(t.store.create('a/b:c', new Uint8Array([1])), 'a b c.pdf');
  } finally {
    t.done();
  }
});

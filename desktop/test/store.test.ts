// File access (src/main/store.ts): documents are opened in place, saved back to the same file, and the window may
// touch only files the user opened.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store, cleanName } from '../src/main/store.ts';

const PDF = (body: string) => `%PDF-1.7\n${body}`;
const bytes = (text: string) => new TextEncoder().encode(text);

function setup() {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-store-'));
  const desk = join(root, 'Desk');
  mkdirSync(desk);
  const file = (name: string, body: string) => {
    const path = join(desk, name);
    writeFileSync(path, PDF(body));
    return path;
  };
  return { root, desk, file, store: new Store(join(root, 'data')), done: () => rmSync(root, { recursive: true, force: true }) };
}

test('opening a file lists it where it is and copies nothing', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'one');
    assert.equal(t.store.remember(report), report);
    t.store.remember(report); // opening it again
    const recent = t.store.recent();
    assert.deepEqual(recent.map((f) => [f.path, f.name, f.folder]), [[report, 'Report.pdf', t.desk]]);
    assert.deepEqual(readdirSync(t.store.ownFolder), [], 'nothing is copied into the app folder');
    assert.deepEqual(readdirSync(t.desk), ['Report.pdf']);
  } finally {
    t.done();
  }
});

test('Save writes back to the same file, and leaves no stray files beside it', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'original');
    t.store.remember(report);
    t.store.save(report, bytes(PDF('signed')));
    assert.equal(readFileSync(report, 'utf8'), PDF('signed'));
    assert.equal(new TextDecoder().decode(t.store.read(report)), PDF('signed'));
    assert.deepEqual(readdirSync(t.desk), ['Report.pdf']);
  } finally {
    t.done();
  }
});

test('Save As writes a new file, keeps the original, and lists the new one first', () => {
  const t = setup();
  try {
    const report = t.file('Report.pdf', 'original');
    t.store.remember(report);
    const copy = t.store.saveNew(join(t.desk, 'Report signed'), bytes(PDF('signed')));
    assert.equal(copy, join(t.desk, 'Report signed.pdf'), '.pdf is added when it is missing');
    assert.equal(readFileSync(report, 'utf8'), PDF('original'));
    assert.equal(readFileSync(copy, 'utf8'), PDF('signed'));
    assert.deepEqual(t.store.recent().map((f) => f.name), ['Report signed.pdf', 'Report.pdf']);
  } finally {
    t.done();
  }
});

test('the window can read and save only files the user opened', () => {
  const t = setup();
  try {
    const secret = t.file('Secret.pdf', 'not opened');
    assert.throws(() => t.store.read(secret), /not open in PDFoundry/);
    assert.throws(() => t.store.save(secret, bytes(PDF('overwritten'))), /not open in PDFoundry/);
    assert.throws(() => t.store.read(join(t.root, 'data', 'settings.json')), /not open in PDFoundry/);
    assert.equal(readFileSync(secret, 'utf8'), PDF('not opened'));
    assert.equal(t.store.knows(secret), false);
  } finally {
    t.done();
  }
});

test('files that are not PDFs are refused', () => {
  const t = setup();
  try {
    const notes = join(t.desk, 'notes.pdf');
    writeFileSync(notes, 'plain text');
    assert.throws(() => t.store.remember(notes), /not a PDF/);
    assert.throws(() => t.store.remember(join(t.desk, 'missing.pdf')));
    assert.deepEqual(t.store.recent(), []);
  } finally {
    t.done();
  }
});

test('removing a file from the list, or clearing the list, never deletes the file', () => {
  const t = setup();
  try {
    const a = t.file('A.pdf', 'a');
    const b = t.file('B.pdf', 'b');
    t.store.remember(a);
    t.store.remember(b);
    t.store.forget(a);
    assert.deepEqual(t.store.recent().map((f) => f.name), ['B.pdf']);
    assert.throws(() => t.store.read(a), /not open/, 'a forgotten file can no longer be read by the window');
    t.store.clearRecent();
    assert.deepEqual(t.store.recent(), []);
    assert.ok(existsSync(a) && existsSync(b));
  } finally {
    t.done();
  }
});

test('a file that was moved or deleted simply drops off the list', () => {
  const t = setup();
  try {
    const a = t.file('A.pdf', 'a');
    t.store.remember(a);
    rmSync(a);
    assert.deepEqual(t.store.recent(), []);
  } finally {
    t.done();
  }
});

test('documents kept by versions before 1.1 show up as recent files', () => {
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-store-'));
  try {
    const old = join(root, 'data', 'Documents');
    mkdirSync(old, { recursive: true });
    writeFileSync(join(old, 'Old contract.pdf'), PDF('kept by 1.0'));
    const store = new Store(join(root, 'data'));
    assert.deepEqual(store.recent().map((f) => f.name), ['Old contract.pdf']);
    assert.equal(new TextDecoder().decode(store.read(join(old, 'Old contract.pdf'))), PDF('kept by 1.0'));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('suggested names are cleaned', () => {
  assert.equal(cleanName('../../etc/passwd'), 'etc passwd');
  assert.equal(cleanName('  Invoice.pdf '), 'Invoice');
  assert.equal(cleanName('a/b:c'), 'a b c');
  assert.equal(cleanName('...'), 'Document');
});

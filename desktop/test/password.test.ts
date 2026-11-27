// Passwords: protecting a PDF, unlocking it for editing, and telling the owner password (full access) from a user
// password that only opens it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFDict, PDFDocument, PDFName, StandardFonts } from '@cantoo/pdf-lib';
import type { EncryptionAlgorithm } from '@cantoo/pdf-lib';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/main/store.ts';
import * as pdf from '../src/core/pdf.ts';

async function sample(pages = 2): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  for (let i = 0; i < pages; i++) doc.addPage([100 * (i + 1), 500]).drawText(`page ${i + 1}`, { x: 10, y: 400, size: 12, font });
  return doc.save();
}

/** A file made by another app: separate user and owner passwords, with restrictions. */
async function locked(user: string, owner: string, algorithm: EncryptionAlgorithm = 'AES-256'): Promise<Uint8Array> {
  const doc = await PDFDocument.load(await sample());
  doc.encrypt({ userPassword: user, ownerPassword: owner, algorithm, allowWeakCryptography: true, permissions: { modifying: false, copying: false } });
  return doc.save();
}

/** Opens the file with pdf.js (as the window does) and returns its page count and the first page's text; null when refused. */
async function pdfjsOpens(bytes: Uint8Array, password?: string): Promise<string | null> {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const task = pdfjs.getDocument({ data: bytes.slice(), password, verbosity: 0 });
  try {
    const doc = await task.promise;
    const text = (await (await doc.getPage(1)).getTextContent()).items.map((i) => ('str' in i ? i.str : '')).join('');
    return `${doc.numPages}: ${text}`;
  } catch {
    return null;
  } finally {
    await task.destroy();
  }
}

test('protect: the file needs the password, and opens with it', async () => {
  const plain = await sample(2);
  assert.equal(await pdf.isProtected(plain), false);
  const locked = await pdf.protect(plain, 'secret word');
  assert.equal(await pdf.isProtected(locked), true);
  assert.equal(await pdf.isEditable(locked), false);
  await assert.rejects(PDFDocument.load(locked), /encrypted/);
  assert.equal(await pdfjsOpens(locked), null, 'pdf.js refuses it without the password');
  assert.equal(await pdfjsOpens(locked, 'wrong'), null);
  assert.equal(await pdfjsOpens(locked, 'secret word'), '2: page 1', 'pdf.js opens it with the password');
  const text = new TextDecoder('latin1').decode(locked);
  assert.match(text, /\/V 5/, 'AES-256');
  assert.match(text, /\/R 6/);
  await assert.rejects(pdf.protect(plain, ''), /Enter a password/);
});

test('unlock: the right password gives the decrypted document with full access', async () => {
  const locked = await pdf.protect(await sample(3), 'pässwörd ✓');
  assert.equal(await pdf.unlock(locked, 'wrong'), null);
  const open = (await pdf.unlock(locked, 'pässwörd ✓'))!;
  assert.equal(open.full, true);
  assert.equal(await pdf.isProtected(open.bytes), false);
  assert.equal(await pdf.isEditable(open.bytes), true);
  assert.equal(await pdf.pageCount(open.bytes), 3);
  // Edits work on the decrypted bytes, and protecting again keeps the same password.
  const edited = await pdf.deletePages(open.bytes, [0]);
  const again = await pdf.protect(edited, 'pässwörd ✓');
  const reopened = (await pdf.unlock(again, 'pässwörd ✓'))!;
  assert.equal(await pdf.pageCount(reopened.bytes), 2);
  assert.equal(await pdfjsOpens(again, 'pässwörd ✓'), '2: page 2');
});

test('unlock: a user password opens with limited access, the owner password with full access', async () => {
  // RC4-40 is revision 2, RC4-128 revision 3, AES-128 revision 4, AES-256 revision 6.
  for (const algorithm of ['AES-256', 'AES-128', 'RC4-128', 'RC4-40'] as EncryptionAlgorithm[]) {
    const bytes = await locked('u', 'o', algorithm);
    const user = await pdf.unlock(bytes, 'u');
    const owner = await pdf.unlock(bytes, 'o');
    assert.deepEqual([user?.full, user?.opens], [false, true], `${algorithm}: user password`);
    assert.deepEqual([owner?.full, owner?.opens], [true, false], `${algorithm}: owner password`);
    assert.equal(await pdf.unlock(bytes, 'x'), null, `${algorithm}: wrong password`);
    assert.equal(await pdf.pageCount(owner!.bytes), 2);
  }
  assert.match(new TextDecoder('latin1').decode(await locked('u', 'o', 'RC4-40')), /\/R 2\b/, 'the RC4-40 file is revision 2');
  // The same password for both: full access, and saving with it changes nothing about how it opens.
  const same = await locked('both', 'both');
  assert.deepEqual([(await pdf.unlock(same, 'both'))?.full, (await pdf.unlock(same, 'both'))?.opens], [true, true]);
});

test('access: what the reader may do with a file it opened', async () => {
  const plain = await sample(1);
  assert.deepEqual({ ...(await pdf.access(plain, null)), bytes: null }, { bytes: null, editable: true, password: null, changesPassword: false });
  const mine = await pdf.protect(plain, 'pw');
  const full = await pdf.access(mine, 'pw');
  assert.equal(full.editable && full.password === 'pw' && !full.changesPassword && !(await pdf.isProtected(full.bytes)), true);
  const theirs = await locked('u', 'o');
  assert.deepEqual([(await pdf.access(theirs, 'u')).editable, (await pdf.access(theirs, 'u')).bytes], [false, theirs], 'user password: read-only');
  const owner = await pdf.access(theirs, 'o');
  assert.deepEqual([owner.editable, owner.changesPassword], [true, true], 'owner password: editable, and saving sets the open password to it');
  // Protected in a way pdf-lib can't decrypt (another security handler): shown read-only, never an error.
  const raw = await PDFDocument.load(mine, { ignoreEncryption: true });
  (raw.context.lookup(raw.context.trailerInfo.Encrypt) as PDFDict).set(PDFName.of('Filter'), PDFName.of('Unknown'));
  const odd = await raw.save({ useObjectStreams: false, updateFieldAppearances: false });
  assert.equal(await pdf.isProtected(odd), true);
  await assert.rejects(pdf.unlock(odd, 'pw'), /can’t open for changes/);
  const fallback = await pdf.access(odd, 'pw');
  assert.deepEqual([fallback.editable, fallback.password, fallback.bytes], [false, null, odd]);
});

test('Review focus 1 and 3: every write of a protected document keeps its password', async () => {
  // The reader's write path, step by step: decrypted bytes in memory (undo steps too), `protect` on every save.
  const root = mkdtempSync(join(tmpdir(), 'pdfoundry-focus-'));
  try {
    const store = new Store(join(root, 'data'));
    const file = store.saveNew(join(root, 'Doc.pdf'), await sample(3));
    const save = async (plain: Uint8Array, password: string) => store.save(file, await pdf.protect(plain, password));
    const needs = async (password: string, pages: number) => {
      const disk = readFileSync(file);
      assert.equal(await pdf.isProtected(disk), true);
      assert.equal(await pdf.unlock(disk, 'wrong'), null);
      assert.equal(await pdf.pageCount((await pdf.unlock(disk, password))!.bytes), pages);
    };
    // Focus 3: protect the open (unprotected) document; the next edit is saved protected.
    let open = (await pdf.access(readFileSync(file), null)).bytes;
    await save(open, 'pw');
    await needs('pw', 3);
    const undo = [open];
    open = await pdf.deletePages(open, [0]);
    await save(open, 'pw');
    await needs('pw', 2);
    // Focus 1: reopen, edit, undo, save: still protected, content as expected.
    const reopened = await pdf.access(readFileSync(file), 'pw');
    assert.equal(reopened.editable, true);
    undo.push(reopened.bytes);
    open = await pdf.deletePages(reopened.bytes, [0]);
    await save(open, 'pw');
    await needs('pw', 1);
    open = undo.pop()!;
    await save(open, 'pw');
    await needs('pw', 2);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a file with restrictions only (no open password) is not given full access', async () => {
  const doc = await PDFDocument.load(await sample());
  doc.encrypt({ userPassword: '', ownerPassword: 'owner', permissions: { modifying: false } });
  const bytes = await doc.save();
  assert.equal(await pdf.isProtected(bytes), true);
  assert.equal((await pdf.unlock(bytes, ''))?.full, false);
  assert.equal((await pdf.unlock(bytes, 'owner'))?.full, true);
});

test('a restrictions-only file never loses its restrictions through Extract, Split or other changes', async () => {
  const doc = await PDFDocument.load(await sample(3));
  doc.encrypt({ userPassword: '', ownerPassword: 'o', permissions: { modifying: false, copying: false } });
  const bytes = await doc.save();
  // pdf-lib decrypts it with the empty open password, but that gives no right to change it.
  const empty = (await pdf.unlock(bytes, ''))!;
  assert.deepEqual([empty.full, empty.opens], [false, true]);
  // What the reader and the tools see (opened without being asked for a password): read-only, as it is.
  const access = await pdf.access(bytes, null);
  assert.deepEqual([access.editable, access.password, access.bytes], [false, null, bytes]);
  assert.deepEqual([(await pdf.access(bytes, '')).editable], [false]);
  // The engine itself refuses to make anything from the still-encrypted bytes, so no plain copy can come out.
  for (const attempt of [pdf.extract(bytes, [0]), pdf.split(bytes, [[0], [1, 2]]), pdf.deletePages(bytes, [0]), pdf.fillFields(bytes, {})]) {
    await assert.rejects(attempt, /protected, so it can only be read/);
  }
  await assert.rejects(pdf.merge([bytes, await sample(1)]), /Remove Password/);
  // Only the owner password gives the bytes that may be changed, and then output is protected with it again.
  const owner = await pdf.access(bytes, 'o');
  assert.equal(owner.editable, true);
  const part = await pdf.protect((await pdf.split(owner.bytes, [[0]]))[0], 'o');
  assert.equal(await pdf.unlock(part, ''), null, 'the part needs the password');
});

test('an unprotected file unlocks as itself', async () => {
  const plain = await sample(1);
  const open = (await pdf.unlock(plain, 'anything'))!;
  assert.equal(open.full, true);
  assert.equal(open.bytes, plain);
});

test('merge refuses a protected file and says what to do', async () => {
  const locked = await pdf.protect(await sample(1), 'pw');
  await assert.rejects(pdf.merge([await sample(1), locked]), /Use More › Remove Password first\.$/);
});

test('protect keeps text strings readable (title, form values) for other readers', async () => {
  const doc = await PDFDocument.create();
  doc.addPage([300, 300]);
  doc.setTitle('Lease – 2026');
  doc.setCreator('Someone');
  const field = doc.getForm().createTextField('name');
  field.setText('Ada Lovelace');
  field.addToPage(doc.getPage(0), { x: 20, y: 200, width: 200, height: 24 });
  const locked = await pdf.protect(await doc.save(), 'pw');
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
  const task = pdfjs.getDocument({ data: locked.slice(), password: 'pw', verbosity: 0 });
  const opened = await task.promise;
  const info = (await opened.getMetadata()).info as { Title?: string; Creator?: string };
  const fields = (await (await opened.getPage(1)).getAnnotations()) as { fieldName?: string; fieldValue?: unknown }[];
  await task.destroy();
  assert.equal(info.Title, 'Lease – 2026');
  assert.equal(info.Creator, 'Someone');
  assert.equal(fields.find((f) => f.fieldName === 'name')?.fieldValue, 'Ada Lovelace');
  // And back through unlock (what the reader edits).
  const plain = (await pdf.unlock(locked, 'pw'))!.bytes;
  const again = await PDFDocument.load(plain);
  assert.equal(again.getTitle(), 'Lease – 2026');
  assert.equal(again.getForm().getTextField('name').getText(), 'Ada Lovelace');
});

test('the MD5 used for older encryption matches Node’s', async () => {
  const { createHash } = await import('node:crypto');
  const { md5 } = await import('../src/core/security.ts');
  for (const length of [0, 1, 55, 56, 63, 64, 65, 200]) {
    const data = Uint8Array.from({ length }, (_, i) => (i * 37 + 11) & 0xff);
    assert.equal(Buffer.from(md5(data)).toString('hex'), createHash('md5').update(data).digest('hex'), `length ${length}`);
  }
});

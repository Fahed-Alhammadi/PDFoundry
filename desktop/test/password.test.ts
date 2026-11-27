// Passwords: protecting a PDF, unlocking it for editing, and telling the owner password (full access) from a user
// password that only opens it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFDocument, StandardFonts } from '@cantoo/pdf-lib';
import type { EncryptionAlgorithm } from '@cantoo/pdf-lib';
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
  for (const algorithm of ['AES-256', 'AES-128', 'RC4-128'] as EncryptionAlgorithm[]) {
    const bytes = await locked('u', 'o', algorithm);
    const user = await pdf.unlock(bytes, 'u');
    const owner = await pdf.unlock(bytes, 'o');
    assert.equal(user?.full, false, `${algorithm}: user password`);
    assert.equal(owner?.full, true, `${algorithm}: owner password`);
    assert.equal(await pdf.unlock(bytes, 'x'), null, `${algorithm}: wrong password`);
    assert.equal(await pdf.pageCount(owner!.bytes), 2);
  }
  // The same password for both: full access.
  const same = await locked('both', 'both');
  assert.equal((await pdf.unlock(same, 'both'))?.full, true);
});

test('a file with restrictions only (no open password) is not given full access', async () => {
  const doc = await PDFDocument.load(await sample());
  doc.encrypt({ userPassword: '', ownerPassword: 'owner', permissions: { modifying: false } });
  const bytes = await doc.save();
  assert.equal(await pdf.isProtected(bytes), true);
  assert.equal((await pdf.unlock(bytes, ''))?.full, false);
  assert.equal((await pdf.unlock(bytes, 'owner'))?.full, true);
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

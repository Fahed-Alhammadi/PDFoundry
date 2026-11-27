// Redaction (src/core/redaction.ts): a redacted page becomes an image of itself with the boxes painted black (the
// window draws it), and nothing of what was on it is left anywhere in the saved file: not in its text, not in an
// old content stream, an annotation, a form field or an object nothing refers to any more.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFString, StandardFonts, degrees } from '@cantoo/pdf-lib';
import * as pdf from '../src/core/pdf.ts';
import { redact } from '../src/core/redaction.ts';
import { annotations, pageTexts, png, traces } from './helpers.ts';

const WORD = 'Swordfish';

/** Three pages; page 2 has the secret word in its text, in a comment, in a form field and in a highlight's note. */
async function secret(): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  for (const line of ['Visible page one', `The password is ${WORD}`, 'Visible page three']) {
    doc.addPage([612, 792]).drawText(line, { x: 72, y: 700, size: 18, font });
  }
  const field = doc.getForm().createTextField('code');
  field.setText(WORD);
  field.addToPage(doc.getPage(1), { x: 72, y: 600, width: 200, height: 24 });
  let bytes: Uint8Array = await doc.save();
  bytes = await pdf.comment(bytes, 1, { x: 0.5, y: 0.5 }, `Remember ${WORD}`);
  bytes = await pdf.markText(bytes, 1, [{ x: 0.1, y: 0.1, width: 0.3, height: 0.03 }], 'highlight', [1, 0.8, 0, 0.5]);
  return bytes;
}

/** A stand-in for what the window draws: a grey picture of the page. */
const picture = () => png(51, 66, [128, 128, 128, 255]);

test('the fixture really has the word in every place a redaction must clear', async () => {
  const bytes = await secret();
  assert.ok((await pageTexts(bytes))[1].includes(WORD));
  const where = await traces(bytes, WORD);
  assert.ok(where.some((w) => /hex|[0-9a-f]{8}/i.test(w)), where.join('\n'));
  assert.ok(where.length >= 3, where.join('\n'));
});

test('a redacted word is gone from the text and from every stream, and the other pages are untouched', async () => {
  const bytes = await secret();
  const before = await pageTexts(bytes);
  const out = await redact(bytes, new Map([[1, picture()]]));
  const after = await pageTexts(out);
  assert.equal(after.length, 3);
  assert.equal(after[1], '', 'the redacted page has no text');
  assert.equal(after[0], before[0]);
  assert.equal(after[2], before[2]);
  assert.deepEqual(await traces(out, WORD), [], 'no trace of the word anywhere in the file');
  // Its marks and its form field went with it; the page is one image.
  const doc = await PDFDocument.load(out);
  assert.equal(annotations(doc, 1).length, 0);
  assert.equal(doc.getForm().getFieldMaybe('code'), undefined);
  assert.match(Buffer.from(out).toString('latin1'), /\/Subtype\s*\/Image/);
});

test('a redacted page keeps its size, rotation and place', async () => {
  const doc = await PDFDocument.create();
  for (let i = 0; i < 3; i++) doc.addPage([400 + i * 10, 600]);
  doc.getPage(1).setRotation(degrees(90));
  doc.getPage(1).setCropBox(20, 30, 300, 500);
  const out = await PDFDocument.load(await redact(await doc.save(), new Map([[1, picture()]])));
  assert.equal(out.getPageCount(), 3);
  assert.deepEqual(out.getPages().map((p) => p.getWidth()), [400, 410, 420]);
  const page = out.getPage(1);
  assert.equal(page.getRotation().angle, 90);
  assert.deepEqual(page.getCropBox(), { x: 20, y: 30, width: 300, height: 500 });
  assert.deepEqual(page.getMediaBox(), { x: 0, y: 0, width: 410, height: 600 });
});

test('a protected document is redacted and saved with its password', async () => {
  // The reader works on the decrypted bytes and encrypts every write with the document's password.
  const locked = await pdf.protect(await secret(), 'hunter2');
  const open = (await pdf.unlock(locked, 'hunter2'))!;
  const saved = await pdf.protect(await redact(open.bytes, new Map([[1, picture()]])), 'hunter2');
  assert.ok(await pdf.isProtected(saved));
  assert.equal(await pdf.unlock(saved, 'wrong'), null);
  const again = (await pdf.unlock(saved, 'hunter2'))!;
  assert.ok(again.full);
  assert.equal((await pageTexts(again.bytes))[1], '');
  assert.deepEqual(await traces(again.bytes, WORD), []);
});

test('a tagged form with XFA keeps nothing of the redacted page: no struct text, no XFA data, no calculation order', async () => {
  const doc = await PDFDocument.create();
  const page = doc.addPage([300, 300]);
  doc.addPage([300, 300]);
  const form = doc.getForm();
  const field = form.createTextField('secret');
  field.setText('SECRETVALUE123');
  field.addToPage(page, { x: 10, y: 10, width: 100, height: 20 });
  const ctx = doc.context;
  const widget = page.node.lookup(PDFName.of('Annots'), PDFArray).get(0);
  // Tagged: an element pointing at the widget, and one on the page with its own text.
  const pointing = ctx.register(ctx.obj({ Type: 'StructElem', S: 'Form', Pg: page.ref, K: [ctx.obj({ Type: 'OBJR', Obj: widget, Pg: page.ref })] }));
  const spoken = ctx.register(ctx.obj({ Type: 'StructElem', S: 'P', Pg: page.ref, ActualText: PDFString.of('SECRETACTUAL'), Alt: PDFString.of('SECRETALT'), K: 0 }));
  doc.catalog.set(PDFName.of('StructTreeRoot'), ctx.register(ctx.obj({ Type: 'StructTreeRoot', K: [pointing, spoken] })));
  doc.catalog.set(PDFName.of('MarkInfo'), ctx.obj({ Marked: true }));
  form.acroForm.dict.set(PDFName.of('XFA'), ctx.register(ctx.flateStream('<xfa:datasets><secret>SECRETXFA</secret></xfa:datasets>')));
  form.acroForm.dict.set(PDFName.of('CO'), ctx.obj([widget]));
  const out = await redact(await doc.save({ useObjectStreams: false, updateFieldAppearances: false }), new Map([[0, picture()]]));
  for (const word of ['SECRETVALUE123', 'SECRETACTUAL', 'SECRETALT', 'SECRETXFA']) assert.deepEqual(await traces(out, word), [], word);
  // Every object as written, not only streams.
  const back = await PDFDocument.load(out);
  const objects = back.context.enumerateIndirectObjects().map(([, o]) => o.toString()).join('\n');
  for (const word of ['SECRETVALUE123', 'SECRETACTUAL', 'SECRETALT', 'SECRETXFA']) assert.ok(!objects.includes(word), `${word} in an object`);
  const acro = back.catalog.lookup(PDFName.of('AcroForm'), PDFDict);
  assert.equal(acro.get(PDFName.of('XFA')), undefined);
  assert.equal(acro.lookupMaybe(PDFName.of('CO'), PDFArray)?.size() ?? 0, 0);
});

test('nothing to redact, or a page that is not there, is refused', async () => {
  const bytes = await secret();
  await assert.rejects(redact(bytes, new Map()), /nothing to redact/i);
  await assert.rejects(redact(bytes, new Map([[7, picture()]])), /no page 8/);
});

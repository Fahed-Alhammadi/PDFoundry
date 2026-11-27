// Form fields (Fill & Sign): reading a form's fields and filling them. Values must stay field values (other apps can
// still change them), with appearances, and nothing is flattened. Also today's date for the Date stamp.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { PDFCheckBox, PDFDocument, PDFDropdown, PDFName, PDFRadioGroup, PDFStream, PDFTextField, degrees } from '@cantoo/pdf-lib';
import { todayText } from '../src/core/dates.ts';
import * as pdf from '../src/core/pdf.ts';

/** One page with every kind of field. `turn`: the page's rotation. */
async function form(turn = 0): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  const page = doc.addPage([600, 800]);
  if (turn) page.setRotation(degrees(turn));
  const f = doc.getForm();
  const name = f.createTextField('name');
  name.addToPage(page, { x: 50, y: 700, width: 300, height: 24 });
  const notes = f.createTextField('notes');
  notes.enableMultiline();
  notes.addToPage(page, { x: 50, y: 560, width: 300, height: 100 });
  f.createCheckBox('agree').addToPage(page, { x: 50, y: 500, width: 20, height: 20 });
  const size = f.createRadioGroup('size');
  for (const [option, x] of [['S', 50], ['M', 100], ['L', 150]] as const) size.addOptionToPage(option, page, { x, y: 450, width: 20, height: 20 });
  const colour = f.createDropdown('colour');
  colour.setOptions(['Red', 'Green', 'Blue']);
  colour.addToPage(page, { x: 50, y: 400, width: 150, height: 24 });
  const ref = f.createTextField('ref');
  ref.setText('A-17');
  ref.enableReadOnly();
  ref.addToPage(page, { x: 400, y: 700, width: 150, height: 24 });
  // A signature field: pdf-lib cannot make one, so a text field is turned into one.
  const sig = f.createTextField('sig');
  sig.addToPage(page, { x: 300, y: 100, width: 200, height: 50 });
  sig.acroField.dict.set(PDFName.of('FT'), PDFName.of('Sig'));
  return doc.save();
}

/** A valid PNG, `w` x `h`, of dark pixels. */
function png(w = 4, h = 2): Uint8Array {
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
  header.writeUInt32BE(w, 0);
  header.writeUInt32BE(h, 4);
  header.set([8, 6, 0, 0, 0], 8);
  const rows = Buffer.concat(Array.from({ length: h }, () => Buffer.from([0, ...Array.from({ length: w }, () => [20, 20, 30, 255]).flat()])));
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}

/** Within `by`: pdf-lib widens a widget by its border, half a point each side. */
const near = (a: number, b: number, by = 1e-6) => Math.abs(a - b) <= by;

test('todayText is the medium date of the given locale', () => {
  const day = new Date(2026, 10, 27, 12);
  assert.equal(todayText(day, 'en-US'), 'Nov 27, 2026');
  assert.equal(todayText(day, 'en-GB'), '27 Nov 2026');
  assert.ok(todayText(day).length > 5, 'the system locale by default');
});

test('fields lists every kind, with where each widget is', async () => {
  const list = await pdf.fields(await form());
  const by = (name: string) => list.filter((f) => f.name === name);
  assert.equal(by('name')[0].kind, 'text');
  assert.equal(by('notes')[0].kind, 'multiline');
  assert.equal(by('agree')[0].kind, 'checkbox');
  assert.equal(by('size').length, 3, 'one entry per radio button');
  assert.deepEqual(by('size')[0].options, ['S', 'M', 'L']);
  assert.equal(by('colour')[0].kind, 'choice');
  assert.deepEqual(by('colour')[0].options, ['Red', 'Green', 'Blue']);
  assert.equal(by('sig')[0].kind, 'signature');
  assert.deepEqual([by('ref')[0].readOnly, by('ref')[0].value, by('name')[0].readOnly], [true, 'A-17', false]);
  const r = by('name')[0].rect; // x 50..350 of 600, y 700..724 of 800 from the bottom
  const pt = 1.01;
  assert.ok(near(r.x * 600, 50, pt) && near(r.width * 600, 300, pt) && near(r.y * 800, 800 - 724, pt) && near(r.height * 800, 24, pt), JSON.stringify(r));
  assert.deepEqual(await pdf.fields(await (await PDFDocument.create()).save().then(async () => {
    const d = await PDFDocument.create();
    d.addPage();
    return d.save();
  })), [], 'no form, no fields');
});

test('field rectangles follow the page rotation', async () => {
  for (const turn of [90, 180, 270]) {
    const bytes = await form(turn);
    const doc = await PDFDocument.load(bytes);
    const page = doc.getPage(0);
    const name = (await pdf.fields(bytes)).find((f) => f.name === 'name')!;
    const back = pdf.userRect(page, name.rect);
    const widget = doc.getForm().getTextField('name').acroField.getWidgets()[0].getRectangle();
    assert.ok(near(back.x, widget.x) && near(back.y, widget.y) && near(back.width, widget.width) && near(back.height, widget.height), `${turn}°: ${JSON.stringify(back)}`);
  }
});

test('filled values stay form fields after saving, with appearances', async () => {
  const source = await form();
  const count = (await PDFDocument.load(source)).getForm().getFields().length;
  const filled = await pdf.fillFields(source, { name: 'Ada Lovelace', notes: 'Line one\nLine two', agree: true, size: 'M', colour: 'Green' });
  const doc = await PDFDocument.load(filled);
  const f = doc.getForm();
  assert.equal(f.getFields().length, count, 'no field was flattened or lost');
  assert.ok(f.getField('name') instanceof PDFTextField);
  assert.equal(f.getTextField('name').getText(), 'Ada Lovelace');
  assert.equal(f.getTextField('notes').getText(), 'Line one\nLine two');
  assert.ok(f.getField('agree') instanceof PDFCheckBox && f.getCheckBox('agree').isChecked());
  assert.ok(f.getField('size') instanceof PDFRadioGroup && f.getRadioGroup('size').getSelected() === 'M');
  assert.ok(f.getField('colour') instanceof PDFDropdown);
  assert.deepEqual(f.getDropdown('colour').getSelected(), ['Green']);
  const widget = f.getTextField('name').acroField.getWidgets()[0];
  assert.ok(doc.context.lookup(widget.getNormalAppearance() as never) instanceof PDFStream, 'the value has an appearance');
  // The engine reads them back too, and they can be changed again (unticked, cleared).
  const listed = await pdf.fields(filled);
  assert.deepEqual(['name', 'agree', 'size', 'colour'].map((n) => listed.find((x) => x.name === n)!.value), ['Ada Lovelace', true, 'M', 'Green']);
  const again = await PDFDocument.load(await pdf.fillFields(filled, { agree: false, name: '', size: '' }));
  assert.equal(again.getForm().getCheckBox('agree').isChecked(), false);
  assert.equal(again.getForm().getTextField('name').getText(), undefined);
  assert.equal(again.getForm().getRadioGroup('size').getSelected(), undefined);
});

test('an Arabic value is kept as real text, shown by an image the window drew', async () => {
  const arabic = 'مرحبا بالعالم';
  assert.equal(pdf.canEncode('Ada Lovelace, café – “quoted” €5'), true);
  assert.equal(pdf.canEncode(arabic), false);
  await assert.rejects(pdf.fillFields(await form(), { name: arabic }), /drawn by the window/, 'never a blank field');
  const filled = await pdf.fillFields(await form(), { name: arabic }, { name: png(120, 10) });
  const loaded = await PDFDocument.load(filled);
  const field = loaded.getForm().getTextField('name');
  assert.equal(field.getText(), arabic, '/V is the typed text');
  const appearance = loaded.context.lookup(field.acroField.getWidgets()[0].getNormalAppearance() as never) as PDFStream;
  assert.ok(appearance instanceof PDFStream);
  const resources = appearance.dict.lookup(PDFName.of('Resources')) as unknown as { lookup: (n: PDFName) => { keys: () => PDFName[] } | undefined };
  assert.ok(resources.lookup(PDFName.of('XObject'))?.keys().length, 'the appearance draws the image');
  assert.equal((await pdf.fields(filled)).find((f) => f.name === 'name')?.value, arabic);
});

test('read-only fields, signature fields and unknown names are refused', async () => {
  const source = await form();
  await assert.rejects(pdf.fillFields(source, { ref: 'B-2' }), /can’t be changed/);
  await assert.rejects(pdf.fillFields(source, { sig: 'x' }), /no field “sig” to fill/);
  await assert.rejects(pdf.fillFields(source, { nothing: 'x' }), /no field “nothing”/);
  await assert.rejects(pdf.fillFields(source, { size: 'XL' }), /can’t take that value/);
});

test('a filled form stays a form when protected and unlocked again', async () => {
  const filled = await pdf.fillFields(await form(), { name: 'Grace' });
  const open = (await pdf.unlock(await pdf.protect(filled, 'pw'), 'pw'))!;
  const f = (await PDFDocument.load(open.bytes)).getForm();
  assert.equal(f.getTextField('name').getText(), 'Grace');
  assert.equal(f.getFields().length, (await PDFDocument.load(filled)).getForm().getFields().length);
});

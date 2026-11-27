// Make Searchable (OCR), the writing half: recognised words become invisible text (render mode 3) over where they
// are on the page, so search, selection and copy work and the page looks exactly as before.
//
// The text uses a "glyphless" font, as Tesseract's own PDFs do: a Type0 font with Identity-H codes, no glyphs embedded
// (nothing is ever drawn with it) and a ToUnicode map from each code to its character. So any script works, Arabic
// included, with no font file in the app. Each word is stretched (Tz) to the width of its box.
import {
  PDFHexString, PDFString, TextRenderingMode, beginText, endText, popGraphicsState, pushGraphicsState, setCharacterSqueeze, setFontAndSize,
  setTextMatrix, setTextRenderingMode, showText,
} from '@cantoo/pdf-lib';
import type { PDFDocument, PDFOperator, PDFRef } from '@cantoo/pdf-lib';
import { displayedSize, quarterTurns, userPoint } from './geometry.ts';
import type { PageRect } from './geometry.ts';
import { PdfError, check, isolate, open, save } from './pdf.ts';

/** A word to write: its text in reading order and its box on the displayed page (fractions from the top-left). */
export type InvisibleWord = { text: string; box: PageRect };

/** The parts of a pdf.js document this needs (so the window and the tests can both pass theirs). */
type TextSource = { numPages: number; getPage(n: number): Promise<{ getTextContent(): Promise<{ items: unknown[] }> }> };

/** The pages (0-based) with no text at all: the ones OCR works on. Pages that already have text are skipped. */
export async function pagesWithoutText(doc: TextSource): Promise<number[]> {
  const out: number[] = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const { items } = await (await doc.getPage(n)).getTextContent();
    if (!items.some((item) => typeof (item as { str?: unknown }).str === 'string' && (item as { str: string }).str.trim())) out.push(n - 1);
  }
  return out;
}

const RTL = /[֐-ࣿﭐ-﷿ﹰ-﻾]/;

/** Every glyph of the glyphless font is half an em wide. */
const ADVANCE = 0.5;

const hex4 = (n: number) => n.toString(16).toUpperCase().padStart(4, '0');

/** A character as UTF-16, in hex, for the ToUnicode map. */
function utf16(ch: string): string {
  let out = '';
  for (let i = 0; i < ch.length; i++) out += hex4(ch.charCodeAt(i));
  return out;
}

/** The glyphless Type0 font for these characters (code n + 1 is the n-th character). */
function glyphlessFont(doc: PDFDocument, chars: string[]): PDFRef {
  const blocks: string[] = [];
  for (let i = 0; i < chars.length; i += 100) {
    const part = chars.slice(i, i + 100);
    blocks.push(`${part.length} beginbfchar\n${part.map((ch, j) => `<${hex4(i + j + 1)}> <${utf16(ch)}>`).join('\n')}\nendbfchar`);
  }
  const cmap = [
    '/CIDInit /ProcSet findresource begin', '12 dict begin', 'begincmap',
    '/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def', '/CMapName /Adobe-Identity-UCS def', '/CMapType 2 def',
    '1 begincodespacerange', '<0000> <FFFF>', 'endcodespacerange', ...blocks,
    'endcmap', 'CMapName currentdict /CMap defineresource pop', 'end', 'end',
  ].join('\n');
  const toUnicode = doc.context.register(doc.context.flateStream(cmap));
  const descriptor = doc.context.register(doc.context.obj({
    Type: 'FontDescriptor', FontName: 'GlyphLessFont', Flags: 5, FontBBox: [0, 0, 500, 1000], ItalicAngle: 0, Ascent: 1000, Descent: 0, CapHeight: 1000, StemV: 80,
  }));
  const cid = doc.context.register(doc.context.obj({
    Type: 'Font', Subtype: 'CIDFontType2', BaseFont: 'GlyphLessFont',
    CIDSystemInfo: { Registry: PDFString.of('Adobe'), Ordering: PDFString.of('Identity'), Supplement: 0 },
    FontDescriptor: descriptor, DW: ADVANCE * 1000, CIDToGIDMap: 'Identity',
  }));
  return doc.context.register(doc.context.obj({
    Type: 'Font', Subtype: 'Type0', BaseFont: 'GlyphLessFont', Encoding: 'Identity-H', DescendantFonts: [cid], ToUnicode: toUnicode,
  }));
}

/**
 * Writes recognised words as invisible text on a page. Boxes are on the page as displayed, so the text follows the
 * page's rotation and crop box. Refuses an empty list ("No text was found on these pages.").
 */
export async function addInvisibleText(bytes: Uint8Array, pageIndex: number, words: InvisibleWord[]): Promise<Uint8Array> {
  const chosen = words.filter((w) => w.text.trim() && w.box.width > 0 && w.box.height > 0);
  if (!chosen.length) throw new PdfError('No text was found on these pages.');
  const doc = await open(bytes);
  check(doc, [pageIndex]);
  const page = doc.getPage(pageIndex);
  const chars = [...new Set(chosen.flatMap((w) => Array.from(w.text)))];
  const code = new Map(chars.map((ch, i) => [ch, i + 1]));
  isolate(doc, page);
  const font = page.node.newFontDictionary('OCR', glyphlessFont(doc, chars));
  const size = displayedSize(page);
  const angle = (quarterTurns(page) * Math.PI) / 2;
  const [cos, sin] = [Math.round(Math.cos(angle)), Math.round(Math.sin(angle))];
  const ops: PDFOperator[] = [pushGraphicsState(), beginText(), setTextRenderingMode(TextRenderingMode.Invisible)];
  for (const word of chosen) {
    // Right-to-left words are written in the order they are seen (as PDF writers lay out Arabic), which readers
    // turn back into reading order when text is searched or copied.
    const glyphs = RTL.test(word.text) ? Array.from(word.text).reverse() : Array.from(word.text);
    const height = word.box.height * size.height;
    const width = word.box.width * size.width;
    // The baseline along the foot of the box, the text as tall as the box and stretched to its width.
    const [x, y] = userPoint(page, { x: word.box.x, y: word.box.y + word.box.height });
    ops.push(
      setFontAndSize(font, height),
      setCharacterSqueeze((100 * width) / (glyphs.length * ADVANCE * height)),
      setTextMatrix(cos, sin, -sin, cos, x, y),
      showText(PDFHexString.of(glyphs.map((ch) => hex4(code.get(ch)!)).join(''))),
    );
  }
  ops.push(endText(), popGraphicsState());
  page.pushOperators(...ops);
  return save(doc);
}

// Compress (Tools › Protect & optimize): a smaller copy of a PDF, made by re-encoding its pictures only. Text,
// drawings, links, form fields and marks are left exactly as they are, so text stays selectable. The window does the
// pixel work (decode, scale down, JPEG) with its canvas through `reencode`; this walks the file's images and puts the
// results in. The source bytes are never changed: a new file comes back, or null when it would not be at least 5%
// smaller.
import { PDFArray, PDFDict, PDFName, PDFNumber, PDFRawStream, PDFRef, decodePDFRawStream } from '@cantoo/pdf-lib';
import type { PDFDocument } from '@cantoo/pdf-lib';
import { displayedSize } from './geometry.ts';
import { open, save } from './pdf.ts';

export type CompressLevel = 'smaller' | 'smallest';
/** Smaller: 150 dpi, JPEG quality 0.6. Smallest: 100 dpi, 0.4. */
export const LEVELS: Record<CompressLevel, { dpi: number; quality: number }> = {
  smaller: { dpi: 150, quality: 0.6 },
  smallest: { dpi: 100, quality: 0.4 },
};

/**
 * A picture for the window to re-encode: a JPEG as it is (`jpeg`), or its decoded pixels (`pixels`, 8 bits per
 * component, gray or RGB). `maxSide`: the most pixels its longer side may keep (the document's longest page side at
 * the level's dpi: a picture never needs more than its page can show). `quality`: the JPEG quality to use.
 */
export type CompressImage = {
  jpeg?: Uint8Array; pixels?: Uint8Array; components: 1 | 3; width: number; height: number; maxSide: number; quality: number;
};
/** The window's answer: the new JPEG (RGB) and its size, or null to leave the picture as it is. */
export type Reencoded = { jpeg: Uint8Array; width: number; height: number } | null;

const name = (n: string) => PDFName.of(n);

/** How many colour components a colour space has, when it is plain gray or RGB (directly or as an ICC profile). */
function components(doc: PDFDocument, space: unknown): 1 | 3 | null {
  const value = space instanceof PDFRef ? doc.context.lookup(space) : space;
  if (value === name('DeviceGray')) return 1;
  if (value === name('DeviceRGB')) return 3;
  if (value instanceof PDFArray && value.get(0) === name('ICCBased')) {
    const profile = doc.context.lookup(value.get(1));
    const n = profile instanceof PDFRawStream ? profile.dict.get(name('N')) : undefined;
    return n instanceof PDFNumber ? (n.asNumber() === 1 ? 1 : n.asNumber() === 3 ? 3 : null) : null;
  }
  return null;
}

/** Makes a smaller copy, or null when that would not save at least 5%. */
export async function compress(bytes: Uint8Array, level: CompressLevel, reencode: (image: CompressImage) => Promise<Reencoded>): Promise<Uint8Array | null> {
  const { dpi, quality } = LEVELS[level];
  const doc = await open(bytes);
  const longest = Math.max(...doc.getPages().map((p) => Math.max(displayedSize(p).width, displayedSize(p).height)));
  const maxSide = Math.round((longest / 72) * dpi);
  // Pictures used as another picture's mask stay as they are (a mask must keep its exact values).
  const masks = new Set<string>();
  for (const [, object] of doc.context.enumerateIndirectObjects()) {
    if (!(object instanceof PDFRawStream)) continue;
    for (const key of ['SMask', 'Mask']) {
      const ref = object.dict.get(name(key));
      if (ref instanceof PDFRef) masks.add(ref.toString());
    }
  }
  let changed = false;
  for (const [ref, object] of doc.context.enumerateIndirectObjects()) {
    if (!(object instanceof PDFRawStream) || object.dict.get(name('Subtype')) !== name('Image') || masks.has(ref.toString())) continue;
    const dict = object.dict;
    // Left alone: stencil masks, colour-key masks, decode arrays, anything but 8-bit gray or RGB.
    if (dict.get(name('ImageMask'))?.toString() === 'true' || dict.get(name('Mask')) instanceof PDFArray || dict.get(name('Decode'))) continue;
    const comps = components(doc, dict.get(name('ColorSpace')));
    const bpc = dict.get(name('BitsPerComponent'));
    const width = dict.get(name('Width'));
    const height = dict.get(name('Height'));
    if (!comps || !(bpc instanceof PDFNumber) || bpc.asNumber() !== 8 || !(width instanceof PDFNumber) || !(height instanceof PDFNumber)) continue;
    const filter = dict.get(name('Filter'));
    const filters = filter instanceof PDFArray ? filter.asArray() : filter ? [filter] : [];
    const image: CompressImage = { components: comps, width: width.asNumber(), height: height.asNumber(), maxSide, quality };
    if (filters.length === 1 && filters[0] === name('DCTDecode')) image.jpeg = object.contents;
    else {
      if (filters.some((f) => f === name('DCTDecode') || f === name('JPXDecode') || f === name('JBIG2Decode') || f === name('CCITTFaxDecode'))) continue;
      const parms = dict.get(name('DecodeParms'));
      if (parms instanceof PDFDict && (parms.get(name('Predictor')) as PDFNumber | undefined)?.asNumber() !== undefined
        && (parms.get(name('Predictor')) as PDFNumber).asNumber() > 1) continue;
      let pixels: Uint8Array;
      try {
        pixels = decodePDFRawStream(object).decode();
      } catch {
        continue;
      }
      if (pixels.length < image.width * image.height * comps) continue;
      image.pixels = pixels.subarray(0, image.width * image.height * comps);
    }
    const result = await reencode(image);
    if (!result || result.jpeg.length >= object.contents.length) continue;
    const next = dict.clone(doc.context);
    for (const key of ['Filter', 'DecodeParms', 'Length', 'ColorSpace', 'BitsPerComponent', 'Width', 'Height']) next.delete(name(key));
    next.set(name('Filter'), name('DCTDecode'));
    next.set(name('ColorSpace'), name('DeviceRGB'));
    next.set(name('BitsPerComponent'), PDFNumber.of(8));
    next.set(name('Width'), PDFNumber.of(result.width));
    next.set(name('Height'), PDFNumber.of(result.height));
    doc.context.assign(ref, PDFRawStream.of(next, result.jpeg));
    changed = true;
  }
  if (!changed) return null;
  const out = await save(doc);
  return out.length <= bytes.length * 0.95 ? out : null;
}

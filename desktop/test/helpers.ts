// Shared by the tests (not a test file itself): small PNGs and readers for what a saved PDF holds.
import { deflateSync, inflateSync } from 'node:zlib';
import { PDFArray, PDFDict, PDFDocument, PDFName, PDFNumber, PDFRawStream, PDFRef, PDFStream, decodePDFRawStream } from '@cantoo/pdf-lib';

/** A valid PNG, `w` x `h`, every pixel `rgba`. */
export function png(w = 1, h = 1, rgba: [number, number, number, number] = [255, 0, 0, 255]): Uint8Array {
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
  const rows = Buffer.concat(Array.from({ length: h }, () => Buffer.from([0, ...Array.from({ length: w }, () => rgba).flat()])));
  return new Uint8Array(Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]));
}

export type Box = { x: number; y: number; width: number; height: number };

/** A stream's bytes, decoded (Flate), as latin1 text. */
export function streamText(stream: PDFStream): string {
  const bytes = stream instanceof PDFRawStream ? decodePDFRawStream(stream).decode() : (stream as unknown as { getContents(): Uint8Array }).getContents();
  return Buffer.from(bytes).toString('latin1');
}

/** The page's drawing instructions as text. */
export function pageContent(doc: PDFDocument, index = 0): string {
  const contents = doc.getPage(index).node.Contents();
  if (!contents) return '';
  const streams = contents instanceof PDFArray ? contents.asArray().map((r) => doc.context.lookup(r)) : [contents];
  return streams.map((s) => streamText(s as PDFStream)).join('\n');
}

/** The annotations on a page, as dictionaries. */
export function annotations(doc: PDFDocument, index = 0): PDFDict[] {
  const annots = doc.getPage(index).node.Annots();
  return (annots?.asArray() ?? []).map((r) => doc.context.lookup(r)).filter((d): d is PDFDict => d instanceof PDFDict);
}

export function subtype(dict: PDFDict): string {
  return (dict.get(PDFName.of('Subtype')) as PDFName | undefined)?.decodeText() ?? '';
}

/** An annotation's normal appearance stream. */
export function appearance(doc: PDFDocument, dict: PDFDict): PDFStream | undefined {
  const ap = dict.lookupMaybe(PDFName.of('AP'), PDFDict);
  const n = ap?.get(PDFName.of('N'));
  const stream = n instanceof PDFRef ? doc.context.lookup(n) : n;
  return stream instanceof PDFStream ? stream : undefined;
}

export function numbers(dict: PDFDict, key: string): number[] {
  const value = dict.lookupMaybe(PDFName.of(key), PDFArray);
  return value ? value.asArray().map((n) => (n as PDFNumber).asNumber()) : [];
}

/** An annotation's /Rect as a box. */
export function rectOf(dict: PDFDict): Box {
  const [x1, y1, x2, y2] = numbers(dict, 'Rect');
  return { x: Math.min(x1, x2), y: Math.min(y1, y2), width: Math.abs(x2 - x1), height: Math.abs(y2 - y1) };
}

export function near(actual: Box, expected: Box, what: string, tolerance = 1e-3): void {
  for (const key of ['x', 'y', 'width', 'height'] as const) {
    if (!(Math.abs(actual[key] - expected[key]) < tolerance)) throw new Error(`${what}: ${key} is ${actual[key]}, expected ${expected[key]}`);
  }
}

/** Every stream in the file, decoded where it can be (Flate), as latin1 text. Includes objects nothing refers to. */
export function everyStream(bytes: Uint8Array): Promise<string[]> {
  return PDFDocument.load(bytes, { updateMetadata: false }).then((doc) => {
    const out: string[] = [];
    for (const [, object] of doc.context.enumerateIndirectObjects()) {
      if (!(object instanceof PDFStream)) continue;
      try {
        out.push(streamText(object));
      } catch {
        try {
          out.push(Buffer.from(inflateSync((object as PDFRawStream).contents)).toString('latin1'));
        } catch {
          out.push(Buffer.from((object as PDFRawStream).contents ?? []).toString('latin1'));
        }
      }
    }
    return out;
  });
}

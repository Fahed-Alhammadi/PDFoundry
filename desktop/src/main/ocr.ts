// Make Searchable (OCR), the recognising half: tesseract.js in the main process, with the English and Arabic data
// that ship with the app (resources/ocr). It is only ever given that folder and never caches anything, so it never
// goes online. The window draws each page (300 dpi) and sends the PNGs; the words come back with their boxes as
// fractions of the page as displayed, and the window writes them into the document (core/ocrtext.ts).
import path from 'node:path';
import type { Worker } from 'tesseract.js';
import type { PageRect } from '../core/geometry.ts';

/** A recognised word: its text (in reading order) and where it is on the page. `rtl`: Arabic, or another right-to-left script. */
export type OcrWord = { text: string; box: PageRect; rtl: boolean };
export type OcrPage = { index: number; png: Uint8Array };
export type OcrResult = { index: number; words: OcrWord[] };

const LANGUAGES = ['eng', 'ara'];
const RTL = /[֐-ࣿﭐ-﷿ﹰ-﻾]/;
/** Words recognised with less confidence than this are left out: noise in pictures, not text. */
const MIN_CONFIDENCE = 40;

/**
 * Where the language data and the worker script are. Packaged, the data is in resources/ocr and tesseract.js is
 * unpacked from the app archive (worker threads can't load scripts from inside it); in development the build copies
 * the data to out/ocr.
 */
export function ocrPaths(where: { appPath: string; packaged: boolean; resourcesPath?: string }): { langPath: string; workerPath?: string } {
  if (!where.packaged) return { langPath: path.join(where.appPath, 'out', 'ocr') };
  const unpacked = path.join(where.resourcesPath!, 'app.asar.unpacked', 'node_modules', 'tesseract.js', 'src', 'worker-script', 'node', 'index.js');
  return { langPath: path.join(where.resourcesPath!, 'ocr'), workerPath: unpacked };
}

/** A PNG's width and height, from its header. */
function pngSize(png: Uint8Array): { width: number; height: number } {
  const view = new DataView(png.buffer, png.byteOffset, png.byteLength);
  return { width: view.getUint32(16), height: view.getUint32(20) };
}

export class OcrEngine {
  private worker: Promise<Worker> | null = null;
  private readonly paths: { langPath: string; workerPath?: string };

  constructor(paths: { langPath: string; workerPath?: string }) {
    // tesseract.js downloads the data when it has no folder or a web address: never let it.
    if (!path.isAbsolute(paths.langPath) || /^[a-z][a-z0-9+.-]*:\/\//i.test(paths.langPath)) throw new Error('OCR data must be a folder on this computer.');
    this.paths = paths;
  }

  private start(): Promise<Worker> {
    // Loaded the first time OCR is used, not when the app starts.
    this.worker ??= import('tesseract.js').then(({ createWorker, OEM }) => createWorker(LANGUAGES, OEM.LSTM_ONLY, {
      langPath: this.paths.langPath, // a folder, never a web address
      cacheMethod: 'none',
      gzip: true,
      ...(this.paths.workerPath ? { workerPath: this.paths.workerPath } : {}),
    })).catch((e) => {
      this.worker = null;
      throw e;
    });
    return this.worker;
  }

  /**
   * Recognises the pages one by one. `progress(done, total)` after each; `cancelled()` is asked before each page and
   * stops everything (null comes back). The worker stays loaded for the next run until `terminate`.
   */
  async recognize(pages: OcrPage[], progress: (done: number, total: number) => void, cancelled: () => boolean): Promise<OcrResult[] | null> {
    const worker = await this.start();
    const out: OcrResult[] = [];
    for (const [n, page] of pages.entries()) {
      if (cancelled()) return null;
      const { width, height } = pngSize(page.png);
      const { data } = await worker.recognize(Buffer.from(page.png), {}, { blocks: true, text: false });
      const words: OcrWord[] = [];
      for (const block of data.blocks ?? []) {
        for (const paragraph of block.paragraphs) {
          for (const line of paragraph.lines) {
            for (const word of line.words) {
              const text = word.text.trim();
              if (!text || word.confidence < MIN_CONFIDENCE) continue;
              const { x0, y0, x1, y1 } = word.bbox;
              words.push({ text, rtl: RTL.test(text), box: { x: x0 / width, y: y0 / height, width: (x1 - x0) / width, height: (y1 - y0) / height } });
            }
          }
        }
      }
      out.push({ index: page.index, words });
      progress(n + 1, pages.length);
    }
    return cancelled() ? null : out;
  }

  async terminate(): Promise<void> {
    const worker = this.worker;
    this.worker = null;
    if (worker) await (await worker.catch(() => null))?.terminate();
  }
}

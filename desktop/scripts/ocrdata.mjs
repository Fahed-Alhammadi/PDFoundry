// The OCR language data bundled with the app: English and Arabic, the LSTM "best_int" models tesseract.js uses by
// default (from the @tesseract.js-data packages; Tesseract's tessdata, Apache 2.0). Copied into one folder, which is
// all tesseract.js is ever given, so it never looks for them online.
import { copyFileSync, mkdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

export const OCR_LANGUAGES = ['eng', 'ara'];

/** Copies `<lang>.traineddata.gz` for every bundled language into `folder`. */
export function copyOcrData(folder) {
  const require = createRequire(import.meta.url);
  mkdirSync(folder, { recursive: true });
  for (const lang of OCR_LANGUAGES) {
    const root = path.dirname(require.resolve(`@tesseract.js-data/${lang}/package.json`));
    copyFileSync(path.join(root, '4.0.0_best_int', `${lang}.traineddata.gz`), path.join(folder, `${lang}.traineddata.gz`));
  }
}

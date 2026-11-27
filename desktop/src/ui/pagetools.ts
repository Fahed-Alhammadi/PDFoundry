// The sheets of the page tools: Page Numbers and Watermark (they change the open document: one Undo step, saved on
// Save), Compress (a smaller copy; the source is never written) and the window's half of Compress, which decodes,
// scales and JPEG-encodes pictures with its canvas. Page Numbers and Watermark show a live preview on the first page.
import { api, title } from './api.ts';
import { button, dialog, formatSize, h, message } from './dom.ts';
import { toPng } from './images.ts';
import { unlockSource } from './protect.ts';
import { openPdf, renderPage } from './pdfview.ts';
import { compress } from '../core/compress.ts';
import type { CompressImage, CompressLevel, Reencoded } from '../core/compress.ts';
import { NUMBER_FORMATS, NUMBER_POSITIONS, numberText } from '../core/pagestamps.ts';
import type { NumberFormat, NumberPosition, PageNumberStyle, WatermarkColour, WatermarkStyle } from '../core/pagestamps.ts';
import * as pdf from '../core/pdf.ts';

const COLOURS: Record<WatermarkColour, string> = { graphite: '#12141c', ember: '#e2511b' };

/** A picture of a page for a preview, with the page's size in points; the overlay is laid over it. */
async function previewOf(bytes: Uint8Array, index: number): Promise<{ el: HTMLElement; overlay: HTMLElement; size: { width: number; height: number } }> {
  const doc = await openPdf(bytes);
  try {
    const page = await doc.getPage(index + 1);
    const size = page.getViewport({ scale: 1 });
    const canvas = h('canvas.preview-sheet');
    await (await renderPage(doc, index, canvas, 220)).promise;
    const overlay = h('div.preview-overlay');
    const el = h('div.preview-page', { 'aria-hidden': 'true' }, canvas, overlay);
    el.style.aspectRatio = `${size.width} / ${size.height}`;
    return { el, overlay, size: { width: size.width, height: size.height } };
  } finally {
    void doc.loadingTask.destroy();
  }
}

function select<T extends string>(label: string, options: Record<T, string>, value: T): HTMLSelectElement {
  return h('select', { 'aria-label': label }, ...(Object.keys(options) as T[]).map((key) => h('option', { value: key, selected: key === value }, options[key])));
}

const row = (label: string, control: HTMLElement) => h('label.sheet-row', h('span', label), control);

/** Page Numbers: position, format, start at, skip the first page. Resolves the style, or null when cancelled. */
export async function pageNumbersSheet(bytes: Uint8Array, pageCount: number): Promise<PageNumberStyle | null> {
  const preview = await previewOf(bytes, 0);
  return new Promise((resolve) => {
    let chosen: PageNumberStyle | null = null;
    const position = select<NumberPosition>('Position', NUMBER_POSITIONS, 'bottom-center');
    const format = select<NumberFormat>('Format', NUMBER_FORMATS, 'n');
    const start = h('input', { type: 'number', min: '0', step: '1', value: '1', 'aria-label': 'Start at', inputMode: 'numeric' });
    const skip = h('input', { type: 'checkbox', 'aria-label': 'Skip the first page' });
    const label = h('span.preview-number');
    const style = (): PageNumberStyle | null => {
      const startAt = Number(start.value);
      if (!/^\d+$/.test(start.value.trim())) return null;
      return { position: position.value as NumberPosition, format: format.value as NumberFormat, startAt, skipFirst: skip.checked };
    };
    const add = button('Add', () => {
      chosen = style();
      if (chosen) d.close();
    }, 'primary');
    const update = () => {
      const s = style();
      add.disabled = !s || (s.skipFirst && pageCount < 2);
      // The first page of the preview shows its number, or nothing when it is skipped.
      label.hidden = !s || s.skipFirst;
      if (!s) return;
      label.textContent = numberText(s.format, s.startAt, s.startAt + pageCount - (s.skipFirst ? 2 : 1));
      const [vertical, horizontal] = s.position.split('-');
      const { width, height } = preview.size;
      label.className = `preview-number ${vertical} ${horizontal}`;
      label.style.setProperty('--size', `${(10 / height) * 100}cqh`);
      label.style.setProperty('--edge-x', `${(36 / width) * 100}%`);
      label.style.setProperty('--edge-y', `${(36 / height) * 100}%`);
    };
    for (const c of [position, format, start, skip]) c.addEventListener('input', update);
    preview.overlay.append(label);
    const d = dialog('Page Numbers', [
      h('div.sheet-with-preview',
        h('div.sheet-fields',
          row('Position', position),
          row('Format', format),
          row('Start at', start),
          h('label.sheet-check', skip, h('span', 'Skip the first page'))),
        preview.el),
    ], [button('Cancel', () => d.close()), add], { onClose: () => resolve(chosen) });
    d.el.classList.add('page-numbers');
    update();
  });
}

/** Watermark: text (with quick choices), opacity, diagonal or across, colour. Resolves the style, or null. */
export async function watermarkSheet(bytes: Uint8Array): Promise<WatermarkStyle | null> {
  const preview = await previewOf(bytes, 0);
  return new Promise((resolve) => {
    let chosen: WatermarkStyle | null = null;
    const text = h('input', { type: 'text', value: 'CONFIDENTIAL', 'aria-label': 'Watermark text', dir: 'auto', autocomplete: 'off' });
    const quick = h('div.quick-choices', ...['CONFIDENTIAL', 'DRAFT', 'COPY'].map((word) => h('button.chip', {
      type: 'button', onclick: () => {
        text.value = word;
        update();
      },
    }, word)));
    const opacity = h('input', { type: 'range', min: '5', max: '100', step: '5', value: '25', 'aria-label': 'Opacity' });
    const opacityLabel = h('span.range-value');
    const layout = select<'diagonal' | 'horizontal'>('Direction', { diagonal: 'Diagonal', horizontal: 'Horizontal' }, 'diagonal');
    const colour = select<WatermarkColour>('Colour', { graphite: 'Graphite', ember: 'Ember' }, 'graphite');
    const mark = h('span.preview-watermark');
    const style = (): WatermarkStyle => ({ text: text.value.trim(), opacity: Number(opacity.value) / 100, diagonal: layout.value === 'diagonal', color: colour.value as WatermarkColour });
    const add = button('Add', () => {
      chosen = style();
      if (chosen.text) d.close();
    }, 'primary');
    const update = () => {
      const s = style();
      add.disabled = !s.text;
      opacityLabel.textContent = `${opacity.value}%`;
      const { width, height } = preview.size;
      mark.textContent = s.text;
      mark.style.color = COLOURS[s.color];
      mark.style.opacity = String(s.opacity);
      mark.style.setProperty('--angle', `${s.diagonal ? -(Math.atan2(height, width) * 180) / Math.PI : 0}deg`);
      // About 60% of the page's width: the font is shrunk to fit once the text is laid out.
      mark.style.fontSize = '40px';
      requestAnimationFrame(() => {
        const target = preview.overlay.clientWidth * 0.6;
        if (mark.offsetWidth) mark.style.fontSize = `${(40 * target) / mark.offsetWidth}px`;
      });
    };
    for (const c of [text, opacity, layout, colour]) c.addEventListener('input', update);
    preview.overlay.append(mark);
    const d = dialog('Watermark', [
      h('div.sheet-with-preview',
        h('div.sheet-fields',
          row('Text', text),
          quick,
          row('Opacity', h('span.range', opacity, opacityLabel)),
          row('Direction', layout),
          row('Colour', colour),
          h('p.hint', 'Added to every page.')),
        preview.el),
    ], [button('Cancel', () => d.close()), add], { onClose: () => resolve(chosen) });
    d.el.classList.add('watermark');
    update();
  });
}

/**
 * The watermark text drawn by the window, for text the standard PDF font cannot write (Arabic, say): bold, in its
 * colour, on a transparent PNG cropped to the text. Shaped by the browser, so every script joins correctly.
 */
export async function watermarkPng(style: WatermarkStyle): Promise<Uint8Array> {
  const points = 160;
  const family = '-apple-system, "Segoe UI", "Helvetica Neue", Arial, "Noto Sans", "Noto Sans Arabic", sans-serif';
  const measure = document.createElement('canvas').getContext('2d')!;
  measure.font = `bold ${points}px ${family}`;
  const metrics = measure.measureText(style.text);
  const canvas = document.createElement('canvas');
  canvas.width = Math.min(8000, Math.ceil(metrics.width) + 8);
  canvas.height = Math.ceil(points * 1.3);
  const ctx = canvas.getContext('2d')!;
  ctx.font = measure.font;
  ctx.fillStyle = COLOURS[style.color];
  ctx.textBaseline = 'middle';
  ctx.direction = /[֐-ࣿﭐ-﷿ﹰ-﻾]/.test(style.text) ? 'rtl' : 'ltr';
  ctx.textAlign = 'center';
  ctx.fillText(style.text, canvas.width / 2, canvas.height / 2);
  return toPng(canvas);
}

/** The window's half of Compress: decodes a picture, scales it down to `maxSide`, and encodes it as a JPEG. */
export async function reencode(image: CompressImage): Promise<Reencoded> {
  try {
    let source: ImageBitmap;
    if (image.jpeg) source = await createImageBitmap(new Blob([image.jpeg as BlobPart], { type: 'image/jpeg' }));
    else {
      const rgba = new Uint8ClampedArray(image.width * image.height * 4);
      const px = image.pixels!;
      for (let i = 0, j = 0; i < image.width * image.height; i++, j += image.components) {
        const [r, g, b] = image.components === 1 ? [px[j], px[j], px[j]] : [px[j], px[j + 1], px[j + 2]];
        rgba.set([r, g, b, 255], i * 4);
      }
      source = await createImageBitmap(new ImageData(rgba, image.width, image.height));
    }
    const scale = Math.min(1, image.maxSide / Math.max(image.width, image.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(image.width * scale));
    canvas.height = Math.max(1, Math.round(image.height * scale));
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
    source.close();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', image.quality));
    return blob ? { jpeg: new Uint8Array(await blob.arrayBuffer()), width: canvas.width, height: canvas.height } : null;
  } catch {
    return null; // a picture the window can't read stays as it is
  }
}

/**
 * Compress: Smaller or Smallest, then where to save "<name> (compressed).pdf" (never over the source). A protected
 * source asks for its password, and the new file is protected with it. Resolves the path saved, or null.
 */
export async function compressSheet(source: { name: string; path: string; bytes: Uint8Array; unsaved?: boolean; password?: string | null }, keep: string[] = []): Promise<string | null> {
  const unlocked = await unlockSource(source, 'compressed');
  if (!unlocked) return null;
  const name = title(source.name);
  return new Promise((resolve) => {
    let saved: string | null = null;
    let level: CompressLevel = 'smaller';
    const status = h('p.split-status', { role: 'status' });
    const option = (value: CompressLevel, label: string, detail: string) => {
      const radio = h('input', { type: 'radio', name: 'compress-level', value, checked: value === level, 'aria-label': label });
      radio.addEventListener('change', () => (level = value));
      return h('label.split-option', radio, h('span', label), h('span.hint', detail));
    };
    const run = button('Compress', async () => {
      run.disabled = true;
      status.classList.remove('error');
      status.textContent = 'Compressing…';
      try {
        let out = await compress(unlocked.bytes, level, reencode);
        if (!out) {
          status.textContent = 'This file is already as small as it can be.';
          return;
        }
        if (unlocked.password) out = await pdf.protect(out, unlocked.password);
        const path = await api.saveAs(`${name} (compressed)`, out, source.path, [source.path, ...keep]);
        if (!path) {
          status.textContent = '';
          return;
        }
        saved = path;
        status.textContent = `Saved “${title(path)}”: ${formatSize(out.length)}, was ${formatSize(source.bytes.length)}.`;
        run.replaceWith(button('Done', () => d.close(), 'primary'));
      } catch (e) {
        status.classList.add('error');
        status.textContent = message(e);
      } finally {
        run.disabled = false;
      }
    }, 'primary');
    const d = dialog('Compress', [
      h('p.hint', `“${name}” is ${formatSize(source.bytes.length)}${source.unsaved ? ', with your unsaved changes' : ''}. A smaller copy is made; that file is not changed. Only pictures are made smaller: text stays sharp and selectable.`),
      h('div.split-options', { role: 'radiogroup', 'aria-label': 'How small' },
        option('smaller', 'Smaller', 'Pictures at 150 dpi'),
        option('smallest', 'Smallest', 'Pictures at 100 dpi, lower quality')),
      status,
      unlocked.password ? h('p.hint', 'The copy is protected with the same password.') : null,
    ], [button('Cancel', () => d.close()), run], { onClose: () => resolve(saved) });
    d.el.classList.add('compress');
  });
}


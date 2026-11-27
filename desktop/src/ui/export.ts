// Export as Images (Tools › Create): pages as PNG or JPEG pictures, "<name> page 1.png" …, in a folder the user
// picks. Names that are taken ask once: Replace, Keep Both or Cancel (as Split does). The source is only read.
import { api, title } from './api.ts';
import { button, choose, dialog, h, message, toast } from './dom.ts';
import { folderName } from './extract.ts';
import { unlockSource } from './protect.ts';
import { openPdf, renderPage } from './pdfview.ts';
import * as pdf from '../core/pdf.ts';
import { exportNames, parseRanges, pixelSize } from '../core/ranges.ts';

export type ExportFormat = 'png' | 'jpg';
export type ExportChoice = { pages: number[]; format: ExportFormat; dpi: 150 | 300 };

/** The pages a choice means (0-based, in order, each once), or why it can't be used. */
export function exportPages(all: boolean, ranges: string, pageCount: number): { pages: number[] } | { error: string } {
  if (all) return { pages: Array.from({ length: pageCount }, (_, i) => i) };
  const parsed = parseRanges(ranges, pageCount);
  return 'error' in parsed ? parsed : { pages: [...new Set(parsed.parts.flat())] };
}

/** A page drawn at `dpi` (pdf.js, marks and form fields as saved) as a PNG or a JPEG (quality 0.9). */
async function picture(doc: Awaited<ReturnType<typeof openPdf>>, index: number, dpi: number, format: ExportFormat): Promise<Uint8Array> {
  const page = await doc.getPage(index + 1);
  const size = page.getViewport({ scale: 1 });
  const canvas = document.createElement('canvas');
  await (await renderPage(doc, index, canvas, pixelSize(size, dpi).width, 1)).promise;
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, format === 'png' ? 'image/png' : 'image/jpeg', 0.9));
  if (!blob) throw new Error('A page could not be drawn.');
  return new Uint8Array(await blob.arrayBuffer());
}

/** The sheet, the folder, then the pictures. Resolves the paths written, or null when cancelled. */
export async function exportImages(source: { name: string; path: string; bytes: Uint8Array; unsaved?: boolean; password?: string | null }): Promise<string[] | null> {
  const unlocked = await unlockSource(source, 'exported');
  if (!unlocked) return null;
  const count = await pdf.pageCount(unlocked.bytes);
  const name = title(source.name);
  return new Promise((resolve) => {
    let saved: string[] | null = null;
    let all = true;
    let format: ExportFormat = 'png';
    let dpi: 150 | 300 = 150;
    const ranges = h('input', { type: 'text', placeholder: '1-3, 5', 'aria-label': 'Page ranges', autocomplete: 'off', spellcheck: false });
    const status = h('p.split-status', { role: 'status' });
    const radio = <T>(group: string, value: T, label: string, current: () => T, set: (v: T) => void, extra?: HTMLElement) => {
      const input = h('input', { type: 'radio', name: group, value: String(value), checked: current() === value, 'aria-label': label });
      input.addEventListener('change', () => {
        set(value);
        update();
        extra?.focus();
      });
      return h('label.split-option', input, h('span', label), extra ?? null);
    };
    const go = button('Export', async () => {
      const chosen = exportPages(all, ranges.value, count);
      if ('error' in chosen) return;
      go.disabled = true;
      try {
        const folder = await api.pickFolder();
        if (!folder) return;
        const names = exportNames(name, chosen.pages, count, format).map((n) => n.replace(/\.(png|jpg)$/, ''));
        const taken = await api.partsExisting(folder, names, format);
        let keepBoth = false;
        if (taken.length) {
          const answer = await choose(
            taken.length === 1 ? `“${taken[0]}.${format}” already exists` : `${taken.length} of the pictures already exist`,
            `${taken.length === 1 ? 'A file with that name is' : 'Files with those names are'} already in “${folderName(folder)}”. Replace ${taken.length === 1 ? 'it' : 'them'}, or keep both?`,
            [{ label: 'Cancel', value: 'cancel' }, { label: 'Keep Both', value: 'keep' }, { label: 'Replace', value: 'replace', kind: 'danger' }]);
          if (answer === 'cancel') return;
          keepBoth = answer === 'keep';
        }
        const doc = await openPdf(unlocked.bytes);
        const written: string[] = [];
        try {
          // A few pages at a time, so a long document never holds every picture in memory at once.
          for (let start = 0; start < chosen.pages.length; start += 4) {
            const batch = chosen.pages.slice(start, start + 4);
            status.textContent = `Exporting page ${Math.min(start + batch.length, chosen.pages.length)} of ${chosen.pages.length}…`;
            const parts = [];
            for (const [i, index] of batch.entries()) parts.push({ name: names[start + i], bytes: await picture(doc, index, dpi, format) });
            written.push(...(await api.saveParts(folder, parts, keepBoth, [source.path], format)));
          }
        } finally {
          void doc.loadingTask.destroy();
        }
        saved = written;
        toast(`Saved ${written.length === 1 ? '1 picture' : `${written.length} pictures`} in “${folderName(folder)}”.`);
        d.close();
      } catch (e) {
        toast(message(e), true);
      } finally {
        update();
      }
    }, 'primary');
    const update = () => {
      const chosen = exportPages(all, ranges.value, count);
      ranges.disabled = all;
      go.disabled = 'error' in chosen;
      status.classList.toggle('error', 'error' in chosen);
      status.textContent = 'error' in chosen ? chosen.error : `Makes ${chosen.pages.length === 1 ? '1 picture' : `${chosen.pages.length} pictures`}.`;
    };
    ranges.addEventListener('input', update);
    const d = dialog('Export as Images', [
      h('p.hint', `“${name}”${source.unsaved ? ' as it is open now, with your unsaved changes' : ''}. That file is not changed.`),
      h('div.split-options', { role: 'radiogroup', 'aria-label': 'Pages' },
        radio('pages', true, 'All pages', () => all, (v) => (all = v)),
        radio('pages', false, 'Custom ranges', () => all, (v) => (all = v), ranges)),
      h('div.split-options.inline', { role: 'radiogroup', 'aria-label': 'Format' },
        radio('format', 'png' as ExportFormat, 'PNG', () => format, (v) => (format = v)),
        radio('format', 'jpg' as ExportFormat, 'JPEG', () => format, (v) => (format = v))),
      h('div.split-options.inline', { role: 'radiogroup', 'aria-label': 'Quality' },
        radio('dpi', 150 as const, 'Screen (150 dpi)', () => dpi, (v) => (dpi = v)),
        radio('dpi', 300 as const, 'Print (300 dpi)', () => dpi, (v) => (dpi = v))),
      status,
    ], [button('Cancel', () => d.close()), go], { onClose: () => resolve(saved) });
    d.el.classList.add('export');
    update();
  });
}

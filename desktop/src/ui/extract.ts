// The document picker of the Tools view, and Extract Pages: tick pages, save them as a new PDF. Extracting only
// reads the source; it is never changed and never opened in the reader.
import { api, title } from './api.ts';
import type { PickedPdf, RecentFile } from './api.ts';
import { button, dialog, h, icon, message, pages, toast } from './dom.ts';
import { openPdf, renderPage } from './pdfview.ts';
import * as pdf from '../core/pdf.ts';

/**
 * Lists the recent files to pick from, with "Browse…" at the top. Single: resolves the file clicked. Multiple: files
 * are ticked in the order wanted and confirmed with `action`. `browse` opens the system dialog; what it returns is
 * either picked at once (a PickedPdf, single only) or added to the list and ticked (paths). Null when cancelled.
 */
export function pickDocuments<T extends 'one' | 'several'>(options: {
  title: string;
  hint: string;
  mode: T;
  action?: string;
  browse: () => Promise<PickedPdf | string[] | null>;
}): Promise<(T extends 'one' ? string | PickedPdf : string[]) | null> {
  type Result = T extends 'one' ? string | PickedPdf : string[];
  return new Promise((resolve) => {
    let answer: Result | null = null;
    let files: RecentFile[] = [];
    const ticked: string[] = [];
    const list = h('div.picker-list', { role: 'list' });
    const confirm = options.mode === 'several' ? button(options.action ?? 'Next', () => {
      answer = [...ticked] as Result;
      d.close();
    }, 'primary') : null;
    const finish = (value: Result) => {
      answer = value;
      d.close();
    };
    const draw = () => {
      if (confirm) confirm.disabled = ticked.length < 2;
      list.replaceChildren(
        h('button.picker-row.browse', {
          type: 'button', 'aria-label': 'Browse…',
          onclick: async () => {
            try {
              const got = await options.browse();
              if (!got) return;
              if (!Array.isArray(got)) return finish(got as Result);
              for (const file of got) if (!ticked.includes(file)) ticked.push(file);
              files = await api.recent();
              draw();
            } catch (e) {
              toast(message(e), true);
            }
          },
        }, h('span.tile.tile-graphite', icon('folder', 18)), h('span.row-text', h('span.row-title', 'Browse…'), h('span.row-detail', 'Choose a PDF anywhere on this computer'))),
        ...files.map((file) => {
          const at = ticked.indexOf(file.path);
          return h('button.picker-row', {
            type: 'button', role: 'listitem', 'aria-label': title(file.name), 'aria-pressed': options.mode === 'several' ? String(at !== -1) : undefined, title: file.path,
            onclick: () => {
              if (options.mode === 'one') return finish(file.path as Result);
              if (at === -1) ticked.push(file.path);
              else ticked.splice(at, 1);
              draw();
            },
          },
          h('span.tile.tile-grey', icon('documents', 18)),
          h('span.row-text', h('span.row-title', title(file.name)), h('span.row-detail', folderName(file.folder))),
          options.mode === 'several' ? h('span.picker-tick', at === -1 ? '' : String(at + 1)) : null);
        }),
      );
      if (!files.length) list.append(h('p.hint.picker-empty', 'No recent files yet. Choose Browse… to pick one.'));
    };
    const d = dialog(options.title, [h('p.hint', options.hint), list], [button('Cancel', () => d.close()), confirm], { onClose: () => resolve(answer) });
    d.el.classList.add('picker');
    void api.recent().then((recent) => {
      files = recent;
      draw();
    });
    draw();
  });
}

/**
 * Extract Pages: page thumbnails to tick and one Extract button. Saves the chosen pages as a new file (asking where)
 * and resolves its path, or null when cancelled. The source is only read.
 */
export async function extractPages(source: { name: string; path: string; bytes: Uint8Array }): Promise<string | null> {
  if (!(await pdf.isEditable(source.bytes))) {
    toast('This PDF is protected, so its pages cannot be extracted.', true);
    return null;
  }
  const doc = await openPdf(source.bytes);
  return new Promise((resolve) => {
    let saved: string | null = null;
    const chosen = new Set<number>();
    const count = doc.numPages;
    const status = h('span.organizer-status', { role: 'status' });
    const all = button('Select all', () => {
      if (chosen.size === count) chosen.clear();
      else for (let i = 0; i < count; i++) chosen.add(i);
      draw();
    });
    const extract = button('Extract', async () => {
      extract.disabled = true;
      try {
        const picked = [...chosen].sort((a, b) => a - b);
        const bytes = await pdf.extract(source.bytes, picked);
        saved = await api.saveAs(`${title(source.name)} pages`, bytes, source.path, source.path);
        if (saved) {
          toast(`Saved ${pages(picked.length)} as “${title(saved)}”.`);
          d.close();
        }
      } catch (e) {
        toast(message(e), true);
      } finally {
        extract.disabled = !chosen.size;
      }
    }, 'primary');
    const grid = h('div.organizer-grid');
    const items = Array.from({ length: count }, (_, index) => {
      const canvas = h('canvas');
      void renderPage(doc, index, canvas, 170, 1.5).then((t) => t.promise).catch(() => {});
      const item = h('button.organizer-page', {
        type: 'button', 'aria-label': `Page ${index + 1}`,
        onclick: () => {
          if (chosen.has(index)) chosen.delete(index);
          else chosen.add(index);
          draw();
        },
      }, h('span.organizer-sheet', canvas, h('span.tick', icon('check', 14))), h('span.organizer-number', String(index + 1)));
      return item;
    });
    grid.append(...items);
    const draw = () => {
      items.forEach((item, i) => {
        item.classList.toggle('selected', chosen.has(i));
        item.setAttribute('aria-pressed', String(chosen.has(i)));
      });
      status.textContent = chosen.size ? `${chosen.size} of ${pages(count)} selected` : `${pages(count)}. Select the pages to save as a new PDF.`;
      all.textContent = chosen.size === count ? 'Select none' : 'Select all';
      extract.disabled = !chosen.size;
    };
    const d = dialog('Extract Pages', [
      h('p.hint', `From “${title(source.name)}”. That file is not changed.`),
      h('div.extract-tools', status, h('span.spacer'), all),
      grid,
    ], [button('Cancel', () => d.close()), extract], {
      wide: true,
      onClose: () => {
        void doc.loadingTask.destroy();
        resolve(saved);
      },
    });
    d.el.classList.add('organizer', 'extract');
    draw();
  });
}

/** The last two parts of a folder path. */
function folderName(folder: string): string {
  return folder.split(/[\\/]/).filter(Boolean).slice(-2).join(' › ');
}

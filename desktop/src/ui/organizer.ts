// Organize Pages: reorder (drag, or the arrows), rotate, duplicate, extract, add a blank page and delete.
// Like every other edit, these change the open document in memory; the file changes when the user saves.
import { api, title } from './api.ts';
import { button, confirm, dialog, h, icon, message, pages, toast } from './dom.ts';
import { renderPage } from './pdfview.ts';
import type { Reader } from './reader.ts';
import * as pdf from '../core/pdf.ts';

export function organizer(reader: Reader): void {
  let selected = new Set<number>();
  const grid = h('div.organizer-grid');
  const status = h('span.organizer-status', { role: 'status' });
  const tools = h('div.organizer-tools', { role: 'toolbar', 'aria-label': 'Page actions' });

  const chosen = () => [...selected].filter((i) => i < reader.pageSizes.length).sort((a, b) => a - b);

  /** Runs a page change, then redraws with `next` selected. */
  const run = async (action: (bytes: Uint8Array) => Promise<Uint8Array>, next: number[] = []) => {
    if (await reader.change(action)) selected = new Set(next);
    draw();
  };

  const tool = (name: string, label: string, onclick: () => void, disabled: boolean, danger = false) =>
    h('button.tool.compact', { type: 'button', disabled, onclick, class: danger ? 'danger' : '' }, icon(name, 20), h('span', label));

  const draw = () => {
    const count = reader.pageSizes.length;
    const picked = chosen();
    const single = picked.length === 1 ? picked[0] : null;
    status.textContent = picked.length ? `${picked.length} of ${pages(count)} selected` : `${pages(count)}. Select pages to change them, or drag a page to move it.`;
    tools.replaceChildren(
      tool('rotateLeft', 'Rotate left', () => void run((b) => pdf.rotatePages(b, picked, -90), picked), !picked.length),
      tool('rotateRight', 'Rotate right', () => void run((b) => pdf.rotatePages(b, picked, 90), picked), !picked.length),
      tool('back', 'Move earlier', () => void run((b) => pdf.movePage(b, single!, single! - 1), [single! - 1]), single === null || single === 0),
      tool('forward', 'Move later', () => void run((b) => pdf.movePage(b, single!, single! + 1), [single! + 1]), single === null || single === count - 1),
      tool('copy', 'Duplicate', () => void run((b) => pdf.duplicatePages(b, picked)), !picked.length),
      tool('extract', 'Extract', () => void extract(picked), !picked.length),
      tool('blank', 'Blank page', () => void run((b) => pdf.addBlankPage(b, picked.length ? picked[picked.length - 1] : count - 1)), false),
      tool('trash', 'Delete', () => void remove(picked), !picked.length || picked.length >= count, true),
    );
    grid.replaceChildren(
      ...reader.pageSizes.map((size, index) => {
        const canvas = h('canvas');
        void renderPage(reader.pdf, index, canvas, 170, 1.5).then((t) => t.promise).catch(() => {});
        const isSelected = selected.has(index);
        const item = h('button.organizer-page', {
          type: 'button', draggable: true, class: isSelected ? 'selected' : '', 'aria-pressed': String(isSelected), 'aria-label': `Page ${index + 1}`,
          onclick: (e: MouseEvent) => {
            if (e.shiftKey && selected.size) {
              const from = Math.max(...selected);
              for (let i = Math.min(from, index); i <= Math.max(from, index); i++) selected.add(i);
            } else if (selected.has(index)) selected.delete(index);
            else selected.add(index);
            draw();
          },
        }, h('span.organizer-sheet', { class: size.width > size.height ? 'wide' : '' }, canvas, h('span.tick', icon('check', 14))), h('span.organizer-number', String(index + 1)));
        item.addEventListener('dragstart', (e) => {
          e.dataTransfer!.setData('application/x-pdfoundry-page', String(index));
          e.dataTransfer!.effectAllowed = 'move';
        });
        item.addEventListener('dragover', (e) => {
          if (e.dataTransfer!.types.includes('application/x-pdfoundry-page')) {
            e.preventDefault();
            item.classList.add('drop-target');
          }
        });
        item.addEventListener('dragleave', () => item.classList.remove('drop-target'));
        item.addEventListener('drop', (e) => {
          e.preventDefault();
          item.classList.remove('drop-target');
          const from = Number(e.dataTransfer!.getData('application/x-pdfoundry-page'));
          if (Number.isInteger(from) && from !== index) void run((b) => pdf.movePage(b, from, index), [index]);
        });
        return item;
      }),
    );
  };

  const extract = async (picked: number[]) => {
    try {
      // A protected document's pages stay protected, with the same password.
      const password = reader.passwordFor(reader.file);
      const bytes = await pdf.extract(reader.data, picked);
      const saved = await api.saveAs(`${title(reader.file)} pages`, password ? await pdf.protect(bytes, password) : bytes, reader.file, [reader.file]);
      if (saved) toast(`Saved ${pages(picked.length)} as “${title(saved)}”.`);
    } catch (e) {
      toast(message(e), true);
    }
  };

  const remove = async (picked: number[]) => {
    const question = picked.length === 1 ? 'Delete this page?' : `Delete ${picked.length} pages?`;
    if (await confirm(question, 'You can bring it back with Undo. The file itself changes only when you save.', 'Delete')) await run((b) => pdf.deletePages(b, picked));
  };

  const d = dialog('Organize pages', [tools, status, grid], [
    button('Undo', async () => {
      await reader.undo();
      selected.clear();
      draw();
    }),
    button('Done', () => d.close(), 'primary'),
  ], { wide: true });
  d.el.classList.add('organizer');
  draw();
}

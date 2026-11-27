// Split PDF: every page, every N pages, or ranges the user types, each part saved as "<name> (part 1).pdf", … in a
// folder the user picks. The source is only read: never written, never opened, never listed.
import { api, title } from './api.ts';
import { button, choose, dialog, h, message, toast } from './dom.ts';
import { folderName } from './extract.ts';
import { unlockSource } from './protect.ts';
import * as pdf from '../core/pdf.ts';
import { everyN, parseRanges } from '../core/ranges.ts';

export type SplitMode = 'every' | 'everyN' | 'ranges';

/** The parts a choice makes, or why it can't be used. */
export function splitParts(mode: SplitMode, pageCount: number, n: string, ranges: string): { parts: number[][] } | { error: string } {
  if (mode === 'every') return { parts: everyN(pageCount, 1) };
  if (mode === 'ranges') return parseRanges(ranges, pageCount);
  const size = Number(n.trim());
  if (!/^\d+$/.test(n.trim()) || size < 1) return { error: 'Enter a number of pages of 1 or more.' };
  return { parts: everyN(pageCount, size) };
}

/** The sheet, then the folder, then the files. Resolves the paths written, or null when cancelled. */
export async function splitPdf(source: { name: string; path: string; bytes: Uint8Array; unsaved?: boolean; password?: string | null }, keep: string[] = []): Promise<string[] | null> {
  const unlocked = await unlockSource(source, 'split');
  if (!unlocked) return null;
  const { bytes, password } = unlocked;
  const count = await pdf.pageCount(bytes);
  const name = title(source.name);

  return new Promise((resolve) => {
    let saved: string[] | null = null;
    let mode: SplitMode = 'every';
    const n = h('input', { type: 'number', min: '1', step: '1', value: '2', 'aria-label': 'Pages in each file', inputMode: 'numeric' });
    const ranges = h('input', { type: 'text', placeholder: '1-3, 4-6, 9', 'aria-label': 'Page ranges', autocomplete: 'off', spellcheck: false });
    const status = h('p.split-status', { role: 'status' });
    const option = (value: SplitMode, label: string, extra?: HTMLElement) => {
      const radio = h('input', { type: 'radio', name: 'split-mode', value, checked: value === mode, 'aria-label': label });
      radio.addEventListener('change', () => {
        mode = value;
        update();
        extra?.focus();
      });
      return h('label.split-option', radio, h('span', label), extra ?? null);
    };
    const split = button('Split', async () => {
      const result = splitParts(mode, count, n.value, ranges.value);
      if ('error' in result) return;
      split.disabled = true;
      try {
        const folder = await api.pickFolder();
        if (!folder) return;
        let parts = await pdf.split(bytes, result.parts);
        if (password) parts = await Promise.all(parts.map((p) => pdf.protect(p, password)));
        const named = parts.map((p, i) => ({ name: `${name} (part ${i + 1})`, bytes: p }));
        const taken = await api.partsExisting(folder, named.map((p) => p.name));
        let keepBoth = false;
        if (taken.length) {
          const answer = await choose(
            taken.length === 1 ? `“${taken[0]}.pdf” already exists` : `${taken.length} of the parts already exist`,
            `${taken.length === 1 ? 'A file with that name is' : 'Files with those names are'} already in “${folderName(folder)}”. Replace ${taken.length === 1 ? 'it' : 'them'}, or keep both?`,
            [{ label: 'Cancel', value: 'cancel' }, { label: 'Keep Both', value: 'keep' }, { label: 'Replace', value: 'replace', kind: 'danger' }]);
          if (answer === 'cancel') return;
          keepBoth = answer === 'keep';
        }
        saved = await api.saveParts(folder, named, keepBoth, [source.path, ...keep]);
        toast(`Made ${saved.length === 1 ? '1 file' : `${saved.length} files`} in “${folderName(folder)}”.`);
        d.close();
      } catch (e) {
        toast(message(e), true);
      } finally {
        update();
      }
    }, 'primary');
    const update = () => {
      const result = splitParts(mode, count, n.value, ranges.value);
      n.disabled = mode !== 'everyN';
      ranges.disabled = mode !== 'ranges';
      split.disabled = 'error' in result;
      status.classList.toggle('error', 'error' in result);
      status.textContent = 'error' in result ? result.error : `Makes ${result.parts.length === 1 ? '1 file' : `${result.parts.length} files`}.`;
    };
    n.addEventListener('input', update);
    ranges.addEventListener('input', update);
    ranges.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !split.disabled) split.click();
    });
    const d = dialog('Split PDF', [
      h('p.hint', source.unsaved
        ? `“${name}” as it is open now (${count === 1 ? '1 page' : `${count} pages`}), with your unsaved changes. That file is not changed.`
        : `“${name}” has ${count === 1 ? '1 page' : `${count} pages`}. That file is not changed.`),
      h('div.split-options', { role: 'radiogroup', 'aria-label': 'How to split' },
        option('every', 'Every page'),
        option('everyN', 'Every N pages', n),
        option('ranges', 'Custom ranges', ranges)),
      status,
      password ? h('p.hint', 'Each part is protected with the same password.') : null,
    ], [button('Cancel', () => d.close()), split], { onClose: () => resolve(saved) });
    d.el.classList.add('split');
    update();
  });
}

// The start screen: files opened recently, each shown where it really lives. Nothing is copied into the app;
// a card is a shortcut to the file. From here the user opens a PDF, builds one from images, or merges several.
import { api, revealLabel, title } from './api.ts';
import type { Opened, RecentFile } from './api.ts';
import { button, dialog, formatSize, h, icon, iconButton, message, pages, showMenu, toast } from './dom.ts';
import { PasswordNeeded, thumbnail } from './pdfview.ts';
import * as pdf from '../core/pdf.ts';

type Sort = 'recent' | 'name' | 'size';
type Thumb = { url: string; pages: number } | 'locked' | 'broken';

/** First-page pictures, kept while the app is open. The key changes when the file does. */
const thumbs = new Map<string, Promise<Thumb>>();

function thumbFor(file: RecentFile): Promise<Thumb> {
  const key = `${file.path}|${file.modified}|${file.size}`;
  let made = thumbs.get(key);
  if (!made) {
    made = api.read(file.path).then((bytes) => thumbnail(bytes)).catch((e): Thumb => (e instanceof PasswordNeeded ? 'locked' : 'broken'));
    thumbs.set(key, made);
  }
  return made;
}

export type LibraryHost = { openDocument: (file: string) => void };

export class Library {
  readonly el = h('section.view.library', { 'aria-label': 'Recent files' });
  private files: RecentFile[] = [];
  private query = '';
  private sort: Sort = 'recent';
  private selected = new Set<string>();
  private selecting = false;
  private readonly grid = h('div.grid');
  private readonly bar = h('div.selection-bar');
  private readonly count = h('span.count');
  private readonly host: LibraryHost;

  constructor(host: LibraryHost) {
    this.host = host;
    const search = h('input.search', { type: 'search', placeholder: 'Search recent files', 'aria-label': 'Search recent files' });
    search.addEventListener('input', () => {
      this.query = search.value.trim().toLowerCase();
      this.render();
    });
    const sort = h('select', { 'aria-label': 'Sort files' },
      h('option', { value: 'recent' }, 'Last opened'), h('option', { value: 'name' }, 'Name'), h('option', { value: 'size' }, 'Largest first'));
    sort.addEventListener('change', () => {
      this.sort = sort.value as Sort;
      this.render();
    });
    const more = iconButton('more', 'More', () =>
      showMenu(more, [{ label: 'Create from images…', icon: 'image', action: () => void this.createFromImages() }]));
    const open = h('button.button.primary', { type: 'button', onclick: () => void this.openDialog() }, icon('folder', 18), 'Open');
    const select = button('Select', () => this.setSelecting(!this.selecting));
    select.classList.add('select-toggle');

    this.el.append(
      h('header.view-header', h('div', h('h1', 'Recent'), this.count), h('div.header-tools', search, sort, select, more, open)),
      this.bar,
      this.grid,
    );
    this.dropZone();
  }

  // Loading and drawing

  async refresh(): Promise<void> {
    this.files = await api.recent();
    for (const file of [...this.selected]) if (!this.files.some((f) => f.path === file)) this.selected.delete(file);
    this.render();
  }

  private visible(): RecentFile[] {
    const shown = this.files.filter((f) => title(f.name).toLowerCase().includes(this.query));
    if (this.sort === 'name') shown.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
    else if (this.sort === 'size') shown.sort((a, b) => b.size - a.size);
    else shown.sort((a, b) => b.opened - a.opened);
    return shown;
  }

  private render(): void {
    const shown = this.visible();
    this.count.textContent = this.files.length === 1 ? '1 file' : `${this.files.length} files`;
    this.el.classList.toggle('selecting', this.selecting);
    (this.el.querySelector('.select-toggle') as HTMLElement).textContent = this.selecting ? 'Cancel' : 'Select';
    this.renderBar();
    if (!shown.length) {
      this.grid.replaceChildren(
        h('div.empty',
          icon('documents', 40),
          h('h2', this.files.length ? 'No files match your search' : 'Open a PDF to get started'),
          h('p', this.files.length ? 'Try a different name.' : 'Choose Open, or drop a PDF anywhere on this window. Files stay where you keep them.'),
          this.files.length ? null : button('Open…', () => void this.openDialog(), 'primary')),
      );
      return;
    }
    this.grid.replaceChildren(...shown.map((file) => this.card(file)));
  }

  private card(file: RecentFile): HTMLElement {
    const picture = h('div.thumb');
    const detail = h('span.detail', formatSize(file.size));
    void thumbFor(file).then((thumb) => {
      if (thumb === 'locked') picture.append(h('div.thumb-note', icon('lock', 28), 'Protected'));
      else if (thumb === 'broken') picture.append(h('div.thumb-note', icon('info', 28), 'Cannot be shown'));
      else {
        picture.append(h('img', { src: thumb.url, alt: '', draggable: false }));
        detail.textContent = `${pages(thumb.pages)}, ${formatSize(file.size)}`;
      }
    });
    const chosen = this.selected.has(file.path);
    const more = iconButton('more', `More actions for ${title(file.name)}`, (e) => {
      e.stopPropagation();
      showMenu(more, this.menu(file));
    }, 'more');
    const card = h('article.card', { class: chosen ? 'selected' : '', tabIndex: 0, role: 'button', title: file.path, 'aria-label': `${title(file.name)}${chosen ? ', selected' : ''}` },
      picture,
      h('span.tick', icon('check', 16)),
      h('div.card-text', h('span.name', title(file.name)), detail, h('span.detail.folder', folderName(file.folder))),
      more,
    );
    const activate = (e: MouseEvent | KeyboardEvent) => {
      if (this.selecting || e.metaKey || e.ctrlKey) {
        if (!this.selecting) this.selecting = true;
        if (this.selected.has(file.path)) this.selected.delete(file.path);
        else this.selected.add(file.path);
        this.render();
      } else this.host.openDocument(file.path);
    };
    card.addEventListener('click', activate);
    card.addEventListener('keydown', (e) => {
      if (e.target === card && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        activate(e);
      }
    });
    card.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      showMenu(more, this.menu(file));
    });
    return card;
  }

  private menu(file: RecentFile) {
    return [
      { label: 'Open', icon: 'documents', action: () => this.host.openDocument(file.path) },
      { label: revealLabel(), icon: 'folder', action: () => void api.reveal(file.path) },
      'separator' as const,
      { label: 'Remove from this list', icon: 'close', action: () => void this.run(() => api.forget(file.path)) },
    ];
  }

  // Selection

  private setSelecting(on: boolean): void {
    this.selecting = on;
    if (!on) this.selected.clear();
    this.render();
  }

  private renderBar(): void {
    const n = this.selected.size;
    this.bar.hidden = !this.selecting;
    if (!this.selecting) return;
    const merge = h('button.button.primary', { type: 'button', disabled: n < 2, onclick: () => this.mergeDialog() }, icon('merge', 18), 'Merge');
    const remove = h('button.button', {
      type: 'button', disabled: n < 1,
      onclick: () => void this.run(async () => {
        for (const file of this.selected) await api.forget(file);
        this.selecting = false;
        this.selected.clear();
      }),
    }, icon('close', 18), 'Remove from list');
    this.bar.replaceChildren(
      h('span', { role: 'status' }, n === 0 ? 'Select two or more files to merge them' : n === 1 ? '1 selected. Select another to merge.' : `${n} selected`),
      h('span.spacer'), merge, remove,
    );
  }

  // Actions

  /** Runs something that changes the list, shows its error if it fails, and redraws. */
  private async run(action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (e) {
      toast(message(e), true);
    }
    await this.refresh();
  }

  async openDialog(): Promise<void> {
    await this.run(async () => this.opened(await api.openDialog()));
  }

  /** Shows what was opened: the document itself when it is a single one, otherwise the list with the new files on top. */
  opened(result: Opened): void {
    for (const error of result.errors) toast(error, true);
    if (result.opened.length === 1) this.host.openDocument(result.opened[0]);
    else if (result.opened.length) toast(`${result.opened.length} files are at the top of the list.`);
  }

  async createFromImages(): Promise<void> {
    await this.run(async () => {
      const images = await api.pickImages();
      if (!images.length) return;
      const saved = await api.saveAs(images.length === 1 ? images[0].name : 'Images', await pdf.imagesToPdf(images));
      if (saved) this.host.openDocument(saved);
    });
  }

  private mergeDialog(): void {
    let order = this.visible().filter((f) => this.selected.has(f.path)).map((f) => f.path);
    const list = h('ol.merge-list');
    const draw = () => {
      list.replaceChildren(
        ...order.map((file, i) =>
          h('li',
            h('span.merge-name', title(file)),
            iconButton('up', `Move ${title(file)} earlier`, () => move(i, -1)),
            iconButton('down', `Move ${title(file)} later`, () => move(i, 1)),
          ),
        ),
      );
      (list.querySelectorAll('li:first-child button')[0] as HTMLButtonElement).disabled = true;
      (list.querySelectorAll('li:last-child button')[1] as HTMLButtonElement).disabled = true;
    };
    const move = (i: number, by: number) => {
      const next = [...order];
      [next[i], next[i + by]] = [next[i + by], next[i]];
      order = next;
      draw();
    };
    draw();
    const merge = button('Merge and save…', async () => {
      merge.disabled = true;
      try {
        const sources = [];
        for (const file of order) sources.push(await api.read(file));
        const saved = await api.saveAs('Merged document', await pdf.merge(sources), order[0]);
        merge.disabled = false;
        if (!saved) return; // cancelled in the save dialog: stay here
        d.close();
        this.selecting = false;
        this.selected.clear();
        await this.refresh();
        this.host.openDocument(saved);
      } catch (e) {
        merge.disabled = false;
        toast(message(e), true);
      }
    }, 'primary');
    const d = dialog(`Merge ${order.length} files`, [
      h('p.hint', 'Pages are joined in this order. The files you merge are not changed.'),
      list,
    ], [button('Cancel', () => d.close()), merge]);
  }

  /** PDFs dropped anywhere on the window are opened. */
  private dropZone(): void {
    const veil = h('div.drop-veil', h('div', icon('import', 36), h('p', 'Drop PDFs to open them')));
    let depth = 0;
    const hasFiles = (e: DragEvent) => !!e.dataTransfer?.types.includes('Files');
    window.addEventListener('dragenter', (e) => {
      if (!hasFiles(e)) return;
      if (depth++ === 0) document.body.append(veil);
    });
    window.addEventListener('dragleave', (e) => {
      if (hasFiles(e) && --depth <= 0) {
        depth = 0;
        veil.remove();
      }
    });
    window.addEventListener('dragover', (e) => {
      if (hasFiles(e)) e.preventDefault();
    });
    window.addEventListener('drop', (e) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth = 0;
      veil.remove();
      const paths = [...(e.dataTransfer?.files ?? [])].map((f) => api.pathOf(f)).filter(Boolean);
      if (paths.length) void this.run(async () => this.opened(await api.openPaths(paths)));
    });
  }
}

/** The last two parts of a folder path: enough to tell where a file is without filling the card. */
function folderName(folder: string): string {
  const parts = folder.split(/[\\/]/).filter(Boolean);
  return parts.slice(-2).join(' › ');
}

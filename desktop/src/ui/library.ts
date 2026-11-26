// The library: every document as a card with its first page, plus adding, searching, sorting, merging and deleting.
import { api, title } from './api.ts';
import type { DocumentInfo, Imported } from './api.ts';
import { button, confirm, dialog, formatDate, formatSize, h, icon, iconButton, message, pages, prompt, showMenu, toast } from './dom.ts';
import { PasswordNeeded, thumbnail } from './pdfview.ts';
import * as pdf from '../core/pdf.ts';

type Sort = 'recent' | 'name' | 'size';
type Thumb = { url: string; pages: number } | 'locked' | 'broken';

/** First-page pictures, kept while the app is open. The key changes when the file does. */
const thumbs = new Map<string, Promise<Thumb>>();

function thumbFor(doc: DocumentInfo): Promise<Thumb> {
  const key = `${doc.name}|${doc.modified}|${doc.size}`;
  let made = thumbs.get(key);
  if (!made) {
    made = api.read(doc.name).then((bytes) => thumbnail(bytes)).catch((e): Thumb => (e instanceof PasswordNeeded ? 'locked' : 'broken'));
    thumbs.set(key, made);
  }
  return made;
}

export type LibraryHost = { openDocument: (name: string) => void };

export class Library {
  readonly el = h('section.view.library', { 'aria-label': 'Documents' });
  private docs: DocumentInfo[] = [];
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
    const search = h('input.search', { type: 'search', placeholder: 'Search documents', 'aria-label': 'Search documents' });
    search.addEventListener('input', () => {
      this.query = search.value.trim().toLowerCase();
      this.render();
    });
    const sort = h('select', { 'aria-label': 'Sort documents' },
      h('option', { value: 'recent' }, 'Recent first'), h('option', { value: 'name' }, 'Name'), h('option', { value: 'size' }, 'Largest first'));
    sort.addEventListener('change', () => {
      this.sort = sort.value as Sort;
      this.render();
    });
    const add = h('button.button.primary', { type: 'button' }, icon('plus', 18), 'Add');
    add.addEventListener('click', () =>
      showMenu(add, [
        { label: 'Import PDF…', icon: 'import', action: () => void this.importDialog() },
        { label: 'Create from images…', icon: 'image', action: () => void this.createFromImages() },
      ]),
    );
    const select = button('Select', () => this.setSelecting(!this.selecting));
    select.classList.add('select-toggle');

    this.el.append(
      h('header.view-header', h('div', h('h1', 'Documents'), this.count), h('div.header-tools', search, sort, select, add)),
      this.bar,
      this.grid,
    );
    this.dropZone();
  }

  // Loading and drawing

  async refresh(): Promise<void> {
    this.docs = await api.list();
    for (const name of [...this.selected]) if (!this.docs.some((d) => d.name === name)) this.selected.delete(name);
    this.render();
  }

  private visible(): DocumentInfo[] {
    const shown = this.docs.filter((d) => title(d.name).toLowerCase().includes(this.query));
    if (this.sort === 'name') shown.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
    else if (this.sort === 'size') shown.sort((a, b) => b.size - a.size);
    else shown.sort((a, b) => b.modified - a.modified);
    return shown;
  }

  private render(): void {
    const shown = this.visible();
    this.count.textContent = this.docs.length === 1 ? '1 document' : `${this.docs.length} documents`;
    this.el.classList.toggle('selecting', this.selecting);
    (this.el.querySelector('.select-toggle') as HTMLElement).textContent = this.selecting ? 'Cancel' : 'Select';
    this.renderBar();
    if (!shown.length) {
      this.grid.replaceChildren(
        h('div.empty',
          icon('documents', 40),
          h('h2', this.docs.length ? 'No documents match your search' : 'No documents yet'),
          h('p', this.docs.length ? 'Try a different name.' : 'Import a PDF, or drop one anywhere on this window.'),
          this.docs.length ? null : button('Import PDF…', () => void this.importDialog(), 'primary')),
      );
      return;
    }
    this.grid.replaceChildren(...shown.map((doc) => this.card(doc)));
  }

  private card(doc: DocumentInfo): HTMLElement {
    const picture = h('div.thumb');
    const detail = h('span.detail', `${formatDate(doc.modified)}, ${formatSize(doc.size)}`);
    void thumbFor(doc).then((thumb) => {
      if (thumb === 'locked') picture.append(h('div.thumb-note', icon('lock', 28), 'Protected'));
      else if (thumb === 'broken') picture.append(h('div.thumb-note', icon('info', 28), 'Cannot be shown'));
      else {
        picture.append(h('img', { src: thumb.url, alt: '', draggable: false }));
        detail.textContent = `${pages(thumb.pages)}, ${formatSize(doc.size)}`;
      }
    });
    const chosen = this.selected.has(doc.name);
    const more = iconButton('more', `More actions for ${title(doc.name)}`, (e) => {
      e.stopPropagation();
      showMenu(more, this.menu(doc));
    }, 'more');
    const card = h('article.card', { class: chosen ? 'selected' : '', tabIndex: 0, role: 'button', 'aria-label': `${title(doc.name)}${chosen ? ', selected' : ''}` },
      picture,
      h('span.tick', icon('check', 16)),
      h('div.card-text', h('span.name', { title: title(doc.name) }, title(doc.name)), detail),
      more,
    );
    const activate = (e: MouseEvent | KeyboardEvent) => {
      if (this.selecting || e.metaKey || e.ctrlKey) {
        if (!this.selecting) this.selecting = true;
        if (this.selected.has(doc.name)) this.selected.delete(doc.name);
        else this.selected.add(doc.name);
        this.render();
      } else this.host.openDocument(doc.name);
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
      showMenu(more, this.menu(doc));
    });
    return card;
  }

  private menu(doc: DocumentInfo) {
    return [
      { label: 'Open', icon: 'documents', action: () => this.host.openDocument(doc.name) },
      { label: 'Rename…', icon: 'rename', action: () => void this.rename(doc.name) },
      { label: 'Duplicate', icon: 'copy', action: () => void this.run(async () => void (await api.duplicate(doc.name))) },
      'separator' as const,
      { label: 'Save a copy…', icon: 'save', action: () => void saveCopy(doc.name) },
      { label: this.folderLabel(), icon: 'folder', action: () => void api.reveal(doc.name) },
      'separator' as const,
      { label: 'Delete', icon: 'trash', danger: true, action: () => void this.remove([doc.name]) },
    ];
  }

  private folderLabel(): string {
    const platform = document.documentElement.dataset.platform;
    return platform === 'darwin' ? 'Show in Finder' : platform === 'win32' ? 'Show in Explorer' : 'Show in folder';
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
    const remove = h('button.button.danger', { type: 'button', disabled: n < 1, onclick: () => void this.remove([...this.selected]) }, icon('trash', 18), 'Delete');
    this.bar.replaceChildren(
      h('span', { role: 'status' }, n === 0 ? 'Select documents to merge or delete' : n === 1 ? '1 selected. Select another to merge.' : `${n} selected`),
      h('span.spacer'), merge, remove,
    );
  }

  // Actions

  /** Runs something that changes the library, shows its error if it fails, and redraws. */
  private async run(action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (e) {
      toast(message(e), true);
    }
    await this.refresh();
  }

  async importDialog(): Promise<void> {
    await this.run(async () => this.imported(await api.importDialog()));
  }

  /** Reports what an import did, and opens the document when exactly one was added. */
  imported(result: Imported): void {
    for (const error of result.errors) toast(error, true);
    if (result.added.length === 1) this.host.openDocument(result.added[0]);
    else if (result.added.length) toast(`Added ${result.added.length} documents.`);
  }

  async createFromImages(): Promise<void> {
    await this.run(async () => {
      const images = await api.pickImages();
      if (!images.length) return;
      const name = await api.create(images.length === 1 ? images[0].name : 'Images', await pdf.imagesToPdf(images));
      this.host.openDocument(name);
    });
  }

  private async rename(name: string): Promise<void> {
    const wanted = await prompt('Rename document', 'Name', title(name), 'Rename');
    if (wanted !== null) await this.run(async () => void (await api.rename(name, wanted)));
  }

  private async remove(names: string[]): Promise<void> {
    const question = names.length === 1 ? `Delete “${title(names[0])}”?` : `Delete ${names.length} documents?`;
    if (!(await confirm(question, 'It moves to the trash of this computer.', 'Delete'))) return;
    await this.run(async () => {
      for (const name of names) await api.remove(name);
      this.selecting = false;
      this.selected.clear();
    });
  }

  private mergeDialog(): void {
    let order = this.visible().filter((d) => this.selected.has(d.name)).map((d) => d.name);
    const name = h('input', { type: 'text', value: 'Merged document', 'aria-label': 'Name of the merged document' });
    const removeOriginals = h('input', { type: 'checkbox', id: 'merge-delete' });
    const list = h('ol.merge-list');
    const draw = () => {
      list.replaceChildren(
        ...order.map((doc, i) =>
          h('li',
            h('span.merge-name', title(doc)),
            iconButton('up', `Move ${title(doc)} earlier`, () => move(i, -1)),
            iconButton('down', `Move ${title(doc)} later`, () => move(i, 1)),
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
    const merge = button('Merge', async () => {
      merge.disabled = true;
      try {
        const sources = [];
        for (const doc of order) sources.push(await api.read(doc));
        const created = await api.create(name.value || 'Merged document', await pdf.merge(sources));
        if (removeOriginals.checked) for (const doc of order) await api.remove(doc);
        d.close();
        this.selecting = false;
        this.selected.clear();
        await this.refresh();
        this.host.openDocument(created);
      } catch (e) {
        merge.disabled = false;
        toast(message(e), true);
      }
    }, 'primary');
    const d = dialog(`Merge ${order.length} documents`, [
      h('label.field', h('span', 'Name'), name),
      h('p.hint', 'Pages are joined in this order.'),
      list,
      h('label.check', { for: 'merge-delete' }, removeOriginals, 'Delete the originals after merging'),
    ], [button('Cancel', () => d.close()), merge]);
  }

  /** PDFs dropped anywhere on the window are added to the library. */
  private dropZone(): void {
    const veil = h('div.drop-veil', h('div', icon('import', 36), h('p', 'Drop PDFs to add them')));
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
      if (paths.length) void this.run(async () => this.imported(await api.importPaths(paths)));
    });
  }
}

export async function saveCopy(name: string): Promise<void> {
  try {
    const saved = await api.exportCopy(name);
    if (saved) toast('Copy saved.');
  } catch (e) {
    toast(message(e), true);
  }
}

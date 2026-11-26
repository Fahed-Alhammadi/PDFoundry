// The reader: one document, shown page by page, with the tools that change it (highlight, text, draw, sign),
// search, zoom, printing and undo. Every change is saved to the file straight away.
import { api, title } from './api.ts';
import { button, confirm, dialog, h, icon, iconButton, message, prompt, showMenu, toast } from './dom.ts';
import type { MenuItem } from './dom.ts';
import { paintStrokes, rasterize, textImage } from './images.ts';
import type { Placed, Stroke } from './images.ts';
import { saveCopy } from './library.ts';
import { organizer } from './organizer.ts';
import { PasswordNeeded, openPdf, pageSizes, renderPage, search } from './pdfview.ts';
import type { Match, PDFDocumentProxy, Size } from './pdfview.ts';
import { signatureDialog } from './sign.ts';
import * as pdf from '../core/pdf.ts';

const UNDO_STEPS = 10;
const HIGHLIGHTS = [
  { name: 'Yellow', color: '#ffe14d' },
  { name: 'Green', color: '#8be28b' },
  { name: 'Pink', color: '#ff9ec4' },
  { name: 'Blue', color: '#8fcbff' },
];
const PENS = [
  { name: 'Black', color: '#111318' },
  { name: 'Blue', color: '#1a3fb0' },
  { name: 'Red', color: '#c62828' },
];

type Ink = { highlighter: boolean; color: string; strokes: Map<number, Stroke[]> };
type Placement = { item: Placed; page: number; x: number; y: number; width: number; box: HTMLElement };

export type ReaderHost = { back: () => void; opened: (name: string) => void };

export class Reader {
  readonly el = h('section.view.reader', { 'aria-label': 'Document' });
  name = '';
  private bytes: Uint8Array = new Uint8Array();
  private doc: PDFDocumentProxy | null = null;
  private sizes: Size[] = [];
  private editable = true;
  private undoStack: Uint8Array[] = [];
  private zoom: number | 'fit' = 'fit';
  private scale = 1;
  private busy = false;
  private ink: Ink | null = null;
  private placement: Placement | null = null;
  private matches: Match[] = [];
  private matchAt = -1;
  private observer: IntersectionObserver | null = null;
  private readonly host: ReaderHost;

  private readonly heading = h('h1.doc-title');
  private readonly counter = h('span.page-counter', { role: 'status' });
  private readonly zoomLabel = h('span.zoom-label');
  private readonly notice = h('div.notice', { hidden: true });
  private readonly findBar = h('div.find-bar', { hidden: true });
  private readonly scroller = h('div.pages', { tabIndex: 0, 'aria-label': 'Pages' });
  private readonly dock = h('div.dock', { role: 'toolbar', 'aria-label': 'Editing tools' });

  constructor(host: ReaderHost) {
    this.host = host;
    const more = iconButton('more', 'More', () => showMenu(more, this.moreMenu()));
    this.el.append(
      h('header.reader-bar',
        iconButton('back', 'Back to documents', () => this.close()),
        this.heading,
        this.counter,
        h('span.spacer'),
        iconButton('search', 'Search in document', () => this.toggleFind()),
        iconButton('zoomOut', 'Zoom out', () => this.zoomBy(1 / 1.2)),
        this.zoomLabel,
        iconButton('zoomIn', 'Zoom in', () => this.zoomBy(1.2)),
        iconButton('pages', 'Organize pages', () => this.organize()),
        more,
      ),
      this.findBar,
      this.notice,
      this.scroller,
      this.dock,
    );
    this.scroller.addEventListener('scroll', () => this.updateCounter(), { passive: true });
    new ResizeObserver(() => {
      if (this.doc && this.zoom === 'fit') this.layout(true);
    }).observe(this.scroller);
    this.buildFindBar();
  }

  // Opening and closing

  async open(name: string): Promise<boolean> {
    try {
      const bytes = await api.read(name);
      const doc = await openPdf(bytes, (wrong) => prompt(wrong ? 'That password didn’t work' : 'This PDF is protected', 'Password', '', 'Unlock', 'password'));
      this.reset();
      this.name = name;
      this.bytes = bytes;
      this.doc = doc;
      this.sizes = await pageSizes(doc);
      this.editable = await pdf.isEditable(bytes);
      this.heading.textContent = title(name);
      this.notice.hidden = this.editable;
      this.notice.replaceChildren(icon('lock', 18), 'This PDF is protected, so it can be read but not changed.');
      this.zoom = 'fit';
      this.layout(false);
      this.drawDock();
      this.host.opened(name);
      return true;
    } catch (e) {
      if (!(e instanceof PasswordNeeded)) toast(message(e), true);
      return false;
    }
  }

  private reset(): void {
    this.observer?.disconnect();
    void this.doc?.loadingTask.destroy();
    this.doc = null;
    this.undoStack = [];
    this.ink = null;
    this.placement = null;
    this.matches = [];
    this.matchAt = -1;
    this.findBar.hidden = true;
    this.scroller.replaceChildren();
  }

  close(): void {
    this.reset();
    this.name = '';
    this.host.back();
  }

  // Layout and rendering

  private pageEls(): HTMLElement[] {
    return [...this.scroller.querySelectorAll<HTMLElement>('.page')];
  }

  /** Builds (or resizes) the page boxes. Pages are drawn only when they scroll into view. */
  private layout(keepPosition: boolean): void {
    if (!this.doc) return;
    const anchor = keepPosition ? this.position() : null;
    const available = Math.max(200, this.scroller.clientWidth - 64);
    const widest = Math.max(...this.sizes.map((s) => s.width));
    this.scale = this.zoom === 'fit' ? Math.min(available / widest, 1.6) : this.zoom;
    this.zoomLabel.textContent = `${Math.round(this.scale * 100)}%`;

    this.observer?.disconnect();
    this.observer = new IntersectionObserver((entries) => {
      for (const entry of entries) if (entry.isIntersecting) void this.paint(entry.target as HTMLElement);
    }, { root: this.scroller, rootMargin: '600px 0px' });

    const existing = this.pageEls();
    const reuse = existing.length === this.sizes.length;
    if (!reuse) this.scroller.replaceChildren();
    this.sizes.forEach((size, index) => {
      const page = reuse ? existing[index] : h('div.page', { dataset: { index: String(index) }, 'aria-label': `Page ${index + 1}` }, h('canvas.sheet'));
      page.style.width = `${Math.round(size.width * this.scale)}px`;
      page.style.height = `${Math.round(size.height * this.scale)}px`;
      delete page.dataset.painted;
      if (!reuse) {
        this.scroller.append(page);
        page.addEventListener('pointerdown', (e) => this.pagePointerDown(e, index));
      }
      this.observer!.observe(page);
    });
    if (anchor) this.restore(anchor);
    this.updateCounter();
    this.drawMatches();
    this.redrawInk();
  }

  private async paint(page: HTMLElement): Promise<void> {
    const key = `${this.scale}`;
    if (!this.doc || page.dataset.painted === key) return;
    page.dataset.painted = key;
    const doc = this.doc;
    try {
      const fresh = h('canvas.sheet');
      await (await renderPage(doc, Number(page.dataset.index), fresh, page.clientWidth)).promise;
      if (doc !== this.doc) return;
      page.querySelector('canvas.sheet')?.replaceWith(fresh); // swapped in when ready, so a redraw never flashes blank
    } catch {
      delete page.dataset.painted;
    }
  }

  /** The page nearest the top of the view and how far into it we are, to restore after a redraw. */
  private position(): { index: number; offset: number } {
    const top = this.scroller.scrollTop;
    const els = this.pageEls();
    const index = Math.max(0, els.findIndex((el) => el.offsetTop + el.offsetHeight > top));
    const el = els[index];
    return { index, offset: el ? (top - el.offsetTop) / el.offsetHeight : 0 };
  }

  private restore(at: { index: number; offset: number }): void {
    const el = this.pageEls()[Math.min(at.index, this.sizes.length - 1)];
    if (el) this.scroller.scrollTop = el.offsetTop + at.offset * el.offsetHeight;
  }

  /** The page that fills most of the view. */
  currentPage(): number {
    const middle = this.scroller.scrollTop + this.scroller.clientHeight / 2;
    const index = this.pageEls().findIndex((el) => el.offsetTop + el.offsetHeight >= middle);
    return index === -1 ? Math.max(0, this.sizes.length - 1) : index;
  }

  private updateCounter(): void {
    if (this.sizes.length) this.counter.textContent = `${this.currentPage() + 1} of ${this.sizes.length}`;
  }

  scrollToPage(index: number): void {
    const el = this.pageEls()[index];
    if (el) this.scroller.scrollTop = el.offsetTop - 16;
  }

  zoomBy(factor: number): void {
    if (!this.doc) return;
    this.zoom = Math.min(4, Math.max(0.25, this.scale * factor));
    this.layout(true);
  }

  zoomFit(): void {
    if (!this.doc) return;
    this.zoom = 'fit';
    this.layout(true);
  }

  // Changing the document

  /** Applies a change, saves it, and shows the result. The previous version is kept for Undo. */
  async change(action: (bytes: Uint8Array) => Promise<Uint8Array>, done?: string): Promise<boolean> {
    if (this.busy || !this.doc) return false;
    if (!this.editable) {
      toast('This PDF is protected, so it can only be read.', true);
      return false;
    }
    this.busy = true;
    this.el.classList.add('busy');
    try {
      const before = this.bytes;
      const after = await action(before);
      if (after !== before) {
        await api.write(this.name, after);
        this.undoStack.push(before);
        if (this.undoStack.length > UNDO_STEPS) this.undoStack.shift();
        await this.show(after);
      }
      if (done) toast(done);
      return true;
    } catch (e) {
      toast(message(e), true);
      return false;
    } finally {
      this.busy = false;
      this.el.classList.remove('busy');
      this.drawDock();
    }
  }

  /** Reloads the view from new bytes, staying at the same place. */
  private async show(bytes: Uint8Array): Promise<void> {
    const doc = await openPdf(bytes);
    const sizes = await pageSizes(doc);
    void this.doc?.loadingTask.destroy();
    this.bytes = bytes;
    this.doc = doc;
    this.sizes = sizes;
    this.matches = [];
    this.layout(true);
  }

  async undo(): Promise<void> {
    if (this.busy || !this.undoStack.length || this.ink || this.placement) return;
    this.busy = true;
    try {
      const previous = this.undoStack.pop()!;
      await api.write(this.name, previous);
      await this.show(previous);
    } catch (e) {
      toast(message(e), true);
    } finally {
      this.busy = false;
      this.drawDock();
    }
  }

  get data(): Uint8Array {
    return this.bytes;
  }

  // The dock: tools, or the controls of the tool in use

  private drawDock(): void {
    const tool = (name: string, label: string, onclick: () => void, disabled = false) =>
      h('button.tool', { type: 'button', disabled: disabled || !this.editable, onclick }, icon(name, 22), h('span', label));
    if (this.placement) {
      this.dock.replaceChildren(
        h('span.dock-hint', 'Drag it into place. Drag the corner to resize.'),
        button('Cancel', () => this.endPlacement()),
        button('Place', () => void this.place(), 'primary'),
      );
    } else if (this.ink) {
      const ink = this.ink;
      const swatches = (ink.highlighter ? HIGHLIGHTS : PENS).map((option) =>
        h('button.swatch', {
          type: 'button', title: option.name, 'aria-label': option.name, 'aria-pressed': String(option.color === ink.color),
          onclick: () => {
            ink.color = option.color;
            this.drawDock();
          },
        }, h('span', { class: `colour-${option.name.toLowerCase()}${ink.highlighter ? '-light' : ''}` })),
      );
      const count = [...ink.strokes.values()].reduce((n, s) => n + s.length, 0);
      this.dock.replaceChildren(
        h('span.dock-hint', ink.highlighter ? 'Drag over what you want to highlight.' : 'Draw on the page.'),
        ...swatches,
        h('button.tool.compact', { type: 'button', disabled: !count, onclick: () => this.removeLastStroke() }, icon('undo', 20), h('span', 'Remove last')),
        button('Cancel', () => this.endInk()),
        h('button.button.primary', { type: 'button', disabled: !count, onclick: () => void this.saveInk() }, 'Done'),
      );
    } else {
      this.dock.replaceChildren(
        tool('highlight', 'Highlight', () => this.beginInk(true)),
        tool('text', 'Text', () => this.textDialog()),
        tool('draw', 'Draw', () => this.beginInk(false)),
        tool('sign', 'Sign', () => void signatureDialog((signature) => this.beginPlacement(signature, 0.32))),
        tool('undo', 'Undo', () => void this.undo(), !this.undoStack.length),
      );
    }
    this.el.classList.toggle('inking', !!this.ink);
    this.el.classList.toggle('highlighting', !!this.ink?.highlighter);
  }

  // Drawing and highlighting

  private beginInk(highlighter: boolean): void {
    if (!this.editable) return;
    this.ink = { highlighter, color: (highlighter ? HIGHLIGHTS : PENS)[0].color, strokes: new Map() };
    this.drawDock();
  }

  private endInk(): void {
    this.ink = null;
    this.scroller.querySelectorAll('canvas.ink').forEach((c) => c.remove());
    this.drawDock();
  }

  private inkCanvas(page: HTMLElement): HTMLCanvasElement {
    let canvas = page.querySelector<HTMLCanvasElement>('canvas.ink');
    if (!canvas) {
      canvas = h('canvas.ink');
      page.append(canvas);
    }
    const ratio = window.devicePixelRatio || 1;
    const width = Math.round(page.clientWidth * ratio);
    const height = Math.round(page.clientHeight * ratio);
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    return canvas;
  }

  private redrawInk(): void {
    if (!this.ink) return;
    const els = this.pageEls();
    for (const [index, strokes] of this.ink.strokes) if (els[index]) paintStrokes(this.inkCanvas(els[index]), strokes);
  }

  private pagePointerDown(e: PointerEvent, index: number): void {
    const page = e.currentTarget as HTMLElement;
    if (this.placement) {
      // A click on another page moves what is being placed there.
      if (!(e.target as HTMLElement).closest('.placement') && this.placement.page !== index) {
        const box = page.getBoundingClientRect();
        this.movePlacement(index, (e.clientX - box.left) / box.width, (e.clientY - box.top) / box.height);
      }
      return;
    }
    if (!this.ink || e.button !== 0) return;
    e.preventDefault();
    const ink = this.ink;
    const box = page.getBoundingClientRect();
    const point = (ev: PointerEvent) => ({
      x: Math.min(1, Math.max(0, (ev.clientX - box.left) / box.width)),
      y: Math.min(1, Math.max(0, (ev.clientY - box.top) / box.height)),
    });
    // Widths are in points, stored as a fraction of the page width: a 14 pt marker, a 2 pt pen.
    const stroke: Stroke = { points: [point(e)], color: ink.color, width: (ink.highlighter ? 14 : 2) / this.sizes[index].width };
    const strokes = ink.strokes.get(index) ?? [];
    strokes.push(stroke);
    ink.strokes.set(index, strokes);
    const canvas = this.inkCanvas(page);
    try { page.setPointerCapture(e.pointerId); } catch { /* not a live pointer */ }
    const move = (ev: PointerEvent) => {
      stroke.points.push(point(ev));
      paintStrokes(canvas, strokes);
    };
    const up = () => {
      page.removeEventListener('pointermove', move);
      page.removeEventListener('pointerup', up);
      page.removeEventListener('pointercancel', up);
      this.drawDock();
    };
    page.addEventListener('pointermove', move);
    page.addEventListener('pointerup', up);
    page.addEventListener('pointercancel', up);
    paintStrokes(canvas, strokes);
  }

  private removeLastStroke(): void {
    if (!this.ink) return;
    // The last stroke drawn is on the last page that was touched.
    const index = [...this.ink.strokes.keys()].pop();
    if (index === undefined) return;
    const strokes = this.ink.strokes.get(index)!;
    strokes.pop();
    if (!strokes.length) {
      this.ink.strokes.delete(index);
      this.pageEls()[index]?.querySelector('canvas.ink')?.remove();
    } else paintStrokes(this.inkCanvas(this.pageEls()[index]), strokes);
    this.drawDock();
  }

  private async saveInk(): Promise<void> {
    const ink = this.ink;
    if (!ink) return;
    const ok = await this.change(async (bytes) => {
      let out = bytes;
      for (const [index, strokes] of ink.strokes) {
        const image = await rasterize(strokes, this.sizes[index]);
        if (image) out = await pdf.stamp(out, index, image.png, image.rect, ink.highlighter ? { multiply: true, opacity: 0.55 } : {});
      }
      return out;
    });
    if (ok) this.endInk();
  }

  // Placing a signature or typed text

  beginPlacement(item: Placed, widthFraction: number): void {
    if (!this.editable) return;
    this.endPlacement();
    const box = h('div.placement', { tabIndex: 0, 'aria-label': 'Item to place. Drag to move.' },
      h('img', { src: item.url, alt: '', draggable: false }), h('span.handle', { 'aria-label': 'Resize' }));
    this.placement = { item, page: this.currentPage(), x: 0, y: 0, width: Math.min(0.9, widthFraction), box };
    this.movePlacement(this.placement.page, 0.5, this.visibleCentre(this.placement.page));
    box.addEventListener('pointerdown', (e) => this.dragPlacement(e));
    box.addEventListener('keydown', (e) => {
      const step = e.shiftKey ? 0.02 : 0.005;
      const p = this.placement!;
      const by: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
      if (by[e.key]) {
        e.preventDefault();
        this.setPlacement(p.x + by[e.key][0], p.y + by[e.key][1], p.width);
      } else if (e.key === 'Enter') void this.place();
      else if (e.key === 'Escape') this.endPlacement();
    });
    this.drawDock();
    box.focus();
  }

  /** How far down the page (0…1) the middle of the visible part is, so new items appear where the user is looking. */
  private visibleCentre(index: number): number {
    const el = this.pageEls()[index];
    if (!el) return 0.5;
    const middle = this.scroller.scrollTop + this.scroller.clientHeight / 2;
    return Math.min(0.9, Math.max(0.1, (middle - el.offsetTop) / el.offsetHeight));
  }

  private placementHeight(p: Placement, width = p.width): number {
    const size = this.sizes[p.page];
    return (width * size.width) / p.item.aspect / size.height;
  }

  /** Puts the item on a page, centred on a point given in page fractions. */
  private movePlacement(index: number, cx: number, cy: number): void {
    const p = this.placement!;
    p.page = index;
    this.pageEls()[index].append(p.box);
    this.setPlacement(cx - p.width / 2, cy - this.placementHeight(p) / 2, p.width);
  }

  private setPlacement(x: number, y: number, width: number): void {
    const p = this.placement!;
    p.width = Math.min(1, Math.max(0.04, width));
    const height = this.placementHeight(p);
    p.x = Math.min(1 - p.width, Math.max(0, x));
    p.y = Math.min(Math.max(0, 1 - height), Math.max(0, y));
    p.box.style.left = `${p.x * 100}%`;
    p.box.style.top = `${p.y * 100}%`;
    p.box.style.width = `${p.width * 100}%`;
    p.box.style.height = `${height * 100}%`;
  }

  private dragPlacement(e: PointerEvent): void {
    const p = this.placement!;
    e.preventDefault();
    e.stopPropagation();
    const page = this.pageEls()[p.page].getBoundingClientRect();
    const resizing = (e.target as HTMLElement).classList.contains('handle');
    const start = { x: e.clientX, y: e.clientY, px: p.x, py: p.y, width: p.width };
    try { p.box.setPointerCapture(e.pointerId); } catch { /* not a live pointer */ }
    const move = (ev: PointerEvent) => {
      const dx = (ev.clientX - start.x) / page.width;
      const dy = (ev.clientY - start.y) / page.height;
      if (resizing) this.setPlacement(start.px, start.py, Math.min(start.width + dx, 1 - start.px));
      else this.setPlacement(start.px + dx, start.py + dy, start.width);
    };
    const up = () => {
      p.box.removeEventListener('pointermove', move);
      p.box.removeEventListener('pointerup', up);
      p.box.removeEventListener('pointercancel', up);
    };
    p.box.addEventListener('pointermove', move);
    p.box.addEventListener('pointerup', up);
    p.box.addEventListener('pointercancel', up);
  }

  private endPlacement(): void {
    if (!this.placement) return;
    this.placement.box.remove();
    URL.revokeObjectURL(this.placement.item.url);
    this.placement = null;
    this.drawDock();
  }

  private async place(): Promise<void> {
    const p = this.placement;
    if (!p) return;
    const rect = { x: p.x, y: p.y, width: p.width, height: this.placementHeight(p) };
    this.placement = null; // the page is rebuilt by the change; the box goes with it
    p.box.remove();
    const ok = await this.change((bytes) => pdf.stamp(bytes, p.page, p.item.png, rect));
    if (ok) URL.revokeObjectURL(p.item.url);
    else {
      this.placement = p;
      this.pageEls()[p.page]?.append(p.box);
      this.drawDock();
    }
  }

  private textDialog(): void {
    const input = h('textarea', { rows: 3, placeholder: 'Type your text', 'aria-label': 'Text to add' });
    const size = h('select', { 'aria-label': 'Text size' },
      h('option', { value: '10' }, 'Small'), h('option', { value: '14', selected: true }, 'Medium'), h('option', { value: '20' }, 'Large'), h('option', { value: '30' }, 'Extra large'));
    let color = PENS[0].color;
    const swatches = PENS.map((option, i) =>
      h('button.swatch', {
        type: 'button', title: option.name, 'aria-label': option.name, 'aria-pressed': String(i === 0),
        onclick: (e: MouseEvent) => {
          color = option.color;
          for (const s of swatches) s.setAttribute('aria-pressed', String(s === e.currentTarget));
        },
      }, h('span', { class: `colour-${option.name.toLowerCase()}` })),
    );
    const add = button('Add', async () => {
      const text = input.value.trim();
      if (!text) return;
      d.close();
      const image = await textImage(text, color, Number(size.value));
      const page = this.sizes[this.currentPage()];
      this.beginPlacement(image, image.widthPoints / page.width);
    }, 'primary');
    const d = dialog('Add text', [input, h('div.pad-tools', h('span.hint', 'Size'), size, h('span.spacer'), h('span.hint', 'Colour'), ...swatches)],
      [button('Cancel', () => d.close()), add]);
  }

  // Search

  private buildFindBar(): void {
    const input = h('input', { type: 'search', placeholder: 'Word or phrase', 'aria-label': 'Search in document' });
    const status = h('span.find-status', { role: 'status' });
    let timer: ReturnType<typeof setTimeout>;
    const run = async () => {
      if (!this.doc) return;
      const doc = this.doc;
      const found = await search(doc, input.value);
      if (doc !== this.doc) return;
      this.matches = found;
      this.matchAt = found.length ? 0 : -1;
      this.drawMatches();
      report();
      if (found.length) this.scrollToPage(found[0].page);
    };
    const report = () => {
      const total = this.matches.reduce((n, m) => n + m.rects.length, 0);
      status.textContent = !input.value.trim() ? '' : !total ? 'No matches'
        : `${total === 1 ? '1 match' : `${total} matches`}, page ${this.matches[this.matchAt].page + 1}`;
    };
    const step = (by: number) => {
      if (!this.matches.length) return;
      this.matchAt = (this.matchAt + by + this.matches.length) % this.matches.length;
      this.scrollToPage(this.matches[this.matchAt].page);
      report();
    };
    input.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(() => void run(), 250);
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') step(e.shiftKey ? -1 : 1);
      if (e.key === 'Escape') this.toggleFind(false);
    });
    this.findBar.append(input, status, h('span.spacer'),
      iconButton('up', 'Previous page with a match', () => step(-1)),
      iconButton('down', 'Next page with a match', () => step(1)),
      iconButton('close', 'Close search', () => this.toggleFind(false)));
  }

  toggleFind(show = this.findBar.hidden): void {
    if (!this.doc) return;
    this.findBar.hidden = !show;
    const input = this.findBar.querySelector('input')!;
    if (show) {
      input.focus();
      input.select();
    } else {
      input.value = '';
      this.findBar.querySelector('.find-status')!.textContent = '';
      this.matches = [];
      this.drawMatches();
    }
  }

  private drawMatches(): void {
    this.scroller.querySelectorAll('.match').forEach((m) => m.remove());
    const els = this.pageEls();
    for (const match of this.matches) {
      for (const rect of match.rects) {
        const mark = h('span.match');
        mark.style.left = `${rect.x * 100}%`;
        mark.style.top = `${rect.y * 100}%`;
        mark.style.width = `${rect.width * 100}%`;
        mark.style.height = `${rect.height * 100}%`;
        els[match.page]?.append(mark);
      }
    }
  }

  // Menu actions

  private moreMenu(): MenuItem[] {
    const platform = document.documentElement.dataset.platform;
    return [
      { label: 'Organize pages', icon: 'pages', action: () => this.organize() },
      'separator',
      { label: 'Rename…', icon: 'rename', action: () => void this.rename() },
      { label: 'Duplicate', icon: 'copy', action: () => void this.duplicate() },
      { label: 'Save a copy…', icon: 'save', action: () => void saveCopy(this.name) },
      { label: platform === 'darwin' ? 'Show in Finder' : platform === 'win32' ? 'Show in Explorer' : 'Show in folder', icon: 'folder', action: () => void api.reveal(this.name) },
      { label: 'Print…', icon: 'print', action: () => void this.print() },
      'separator',
      { label: 'Delete', icon: 'trash', danger: true, action: () => void this.remove() },
    ];
  }

  organize(): void {
    if (!this.doc) return;
    if (!this.editable) return void toast('This PDF is protected, so its pages cannot be changed.', true);
    if (this.ink) this.endInk();
    this.endPlacement();
    organizer(this);
  }

  /** The open document, for the page organizer's thumbnails. */
  get pdf(): PDFDocumentProxy {
    return this.doc!;
  }

  get pageSizes(): Size[] {
    return this.sizes;
  }

  private async rename(): Promise<void> {
    const wanted = await prompt('Rename document', 'Name', title(this.name), 'Rename');
    if (wanted === null) return;
    try {
      this.name = await api.rename(this.name, wanted);
      this.heading.textContent = title(this.name);
      this.host.opened(this.name);
    } catch (e) {
      toast(message(e), true);
    }
  }

  private async duplicate(): Promise<void> {
    try {
      toast(`Saved as “${title(await api.duplicate(this.name))}”.`);
    } catch (e) {
      toast(message(e), true);
    }
  }

  private async remove(): Promise<void> {
    if (!(await confirm(`Delete “${title(this.name)}”?`, 'It moves to the trash of this computer.', 'Delete'))) return;
    try {
      await api.remove(this.name);
      this.close();
    } catch (e) {
      toast(message(e), true);
    }
  }

  /** Prints through the system dialog: each page is drawn as an image on its own sheet. */
  async print(): Promise<void> {
    if (!this.doc || this.busy) return;
    this.busy = true;
    const holder = document.getElementById('print')!;
    const urls: string[] = [];
    try {
      toast('Preparing to print…');
      for (let index = 0; index < this.sizes.length; index++) {
        const canvas = document.createElement('canvas');
        await (await renderPage(this.doc, index, canvas, this.sizes[index].width, 2.5)).promise;
        const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92));
        if (!blob) continue;
        const url = URL.createObjectURL(blob);
        urls.push(url);
        const image = h('img', { src: url, alt: '' });
        await image.decode();
        holder.append(h('div.print-page', { class: this.sizes[index].width > this.sizes[index].height ? 'landscape' : '' }, image));
      }
      window.print();
    } catch (e) {
      toast(message(e), true);
    } finally {
      holder.replaceChildren();
      urls.forEach((u) => URL.revokeObjectURL(u));
      this.busy = false;
    }
  }
}

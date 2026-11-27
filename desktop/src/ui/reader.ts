// The reader: one document, shown page by page, with the tools that change it (Markup: highlight, underline and
// strikethrough on selected text, typed text, drawing; Fill & Sign: signatures, initials, text, the date, ✓ and ✗;
// Pages: the organizer), the document's own form fields (filled in place whenever it can be changed),
// search, zoom, printing and undo. The file is opened where it is. Changes are kept in memory until the user
// chooses Save (back to the same file) or Save As; leaving with unsaved changes asks first.
//
// A protected file opened with a password that gives full access is decrypted once: every change, and every Undo
// step, works on the decrypted bytes in memory, and Save and Save As encrypt them again with the same password. The
// password is kept here only while the document is open.
import { api, revealLabel, title } from './api.ts';
import { button, choose, confirm, dialog, h, icon, iconButton, message, prompt, showMenu, toast } from './dom.ts';
import type { MenuItem } from './dom.ts';
import { TEXT_INK, fieldTextPng, markPng, paintStrokes, placedFromPng, textImage } from './images.ts';
import type { Placed, Stroke } from './images.ts';
import { HIGHLIGHT_COLOURS, MARK_RED, SHAPE_COLOURS, SHAPE_NAMES, modeBar } from './modebar.ts';
import type { ShapeTool, TextTool, ToolMode } from './modebar.ts';
import { organizer } from './organizer.ts';
import { alreadyProtected, protectSheet } from './protect.ts';
import { PasswordNeeded, openPdf, pageSizes, renderFormLayer, renderPage, renderTextLayer, search } from './pdfview.ts';
import type { Match, PDFDocumentProxy, Size, Widget } from './pdfview.ts';
import { signatureDialog } from './sign.ts';
import { todayText } from '../core/dates.ts';
import * as pdf from '../core/pdf.ts';
import type { FieldInfo, FieldValue, MarkInfo, PagePoint, PageRect, Rgba, ShapeKind, TextMark } from '../core/pdf.ts';
import { joinBoxes } from '../core/rects.ts';
import type { TextDirection } from '../core/rects.ts';

const UNDO_STEPS = 10;
const PENS = [
  { name: 'Black', color: TEXT_INK },
  { name: 'Blue', color: '#1a3fb0' },
  { name: 'Red', color: '#c62828' },
];
/** Draw's highlighter pen: the freehand marker of earlier versions. */
const MARKER = { name: 'Highlighter', color: '#ffe14d' };
const NO_TEXT = 'This page has no text to mark up. Use Draw instead.';
const MARK_HINTS: Record<TextMark, string> = {
  highlight: 'Select text to highlight it.',
  underline: 'Select text to underline it.',
  strikethrough: 'Select text to strike it through.',
};

/** A drawing in progress. `marker`: the highlighter pen is the one in use. */
type Ink = { marker: boolean; color: string; strokes: Map<number, Stroke[]> };
type Placement = { item: Placed; page: number; x: number; y: number; width: number; box: HTMLElement };

export type ReaderHost = { back: () => void; opened: (file: string) => void };

/** `rect` moved by (dx, dy), kept on the page. */
function moved(rect: PageRect, dx: number, dy: number): PageRect {
  return { ...rect, x: Math.min(1 - rect.width, Math.max(0, rect.x + dx)), y: Math.min(1 - rect.height, Math.max(0, rect.y + dy)) };
}

/**
 * `rect` resized by dragging one corner (nw, ne, sw, se) by (dx, dy), kept on the page and at least a few points big.
 * `keep`: the proportions stay as they are (`aspect` is the page's width over its height, to compare like with like).
 */
function resized(rect: PageRect, corner: string, dx: number, dy: number, keep: boolean, aspect: number): PageRect {
  const min = 0.01;
  let left = rect.x, top = rect.y, right = rect.x + rect.width, bottom = rect.y + rect.height;
  if (corner.includes('w')) left = Math.min(right - min, Math.max(0, left + dx));
  else right = Math.max(left + min, Math.min(1, right + dx));
  if (corner.includes('n')) top = Math.min(bottom - min, Math.max(0, top + dy));
  else bottom = Math.max(top + min, Math.min(1, bottom + dy));
  if (keep) {
    // The width decides; the height follows it, from the corner opposite the one dragged.
    const ratio = (rect.height / rect.width) * aspect;
    let width = right - left;
    let height = (width * ratio) / aspect;
    const room = corner.includes('n') ? rect.y + rect.height : 1 - rect.y;
    if (height > room) {
      height = room;
      width = (height * aspect) / ratio;
      if (corner.includes('w')) left = right - width;
      else right = left + width;
    }
    if (corner.includes('n')) top = bottom - height;
    else bottom = top + height;
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}

export class Reader {
  readonly el = h('section.view.reader', { 'aria-label': 'Document' });
  /** The open file's full path; empty when nothing is open. */
  file = '';
  private bytes: Uint8Array = new Uint8Array();
  /** The bytes as they are on disk. The document has unsaved changes whenever `bytes` is something else. */
  private savedBytes: Uint8Array = this.bytes;
  /** The password of a protected document that was unlocked with full access. Every write is encrypted with it. */
  private password: string | null = null;
  private doc: PDFDocumentProxy | null = null;
  private sizes: Size[] = [];
  private editable = true;
  private undoStack: Uint8Array[] = [];
  /**
   * A field change is written into `bytes` without redrawing the pages (the inputs already show it), so the open
   * pdf.js document is older than `bytes` until the next redraw. Printing and the organizer redraw first.
   */
  private stale = false;
  /** Field changes, applied one after another in the order they were made. */
  private fieldWork: Promise<void> = Promise.resolve();
  /** A form field has been typed in and not yet left: its value is only in the input so far. */
  private typing = false;
  /** Field changes queued or being written. Until they are done the document counts as changed. */
  private pendingFields = 0;
  /** Opened with an owner password that is not the open password: the next save sets the open password to it. */
  private passwordNote = false;
  /** The form widgets on each painted page, by pdf.js id. */
  private widgets = new Map<string, Widget>();
  /** The document's signature fields (drawn over the page as "Sign here" boxes). */
  private signatureFields: Promise<FieldInfo[]> | null = null;
  private zoom: number | 'fit' = 'fit';
  private scale = 1;
  private busy = false;
  private ink: Ink | null = null;
  private placement: Placement | null = null;
  /** The bar's mode. The reader always opens in the reading bar, and stays in a mode until its close button. */
  toolMode: ToolMode = 'none';
  /** Highlight, Underline or Strikethrough waiting for the user to select text (Markup only). */
  private textTool: TextTool | null = null;
  /** Markup › Shapes: each drag on a page draws one. */
  private shapeTool: ShapeTool | null = null;
  /** The colour shapes are drawn in (kept when another shape is picked). */
  private shapeColour: Rgba = SHAPE_COLOURS[0].rgba;
  /** Markup › Comment: a click on a page places a comment there. */
  private commentTool = false;
  /** The mark selected on a page (to move, resize or delete it), and the box drawn around it. */
  private selected: { page: number; mark: MarkInfo; box: HTMLElement } | null = null;
  private matches: Match[] = [];
  private matchAt = -1;
  private observer: IntersectionObserver | null = null;
  private readonly host: ReaderHost;

  private readonly heading = h('h1.doc-title');
  private readonly edited = h('span.edited', { hidden: true }, 'Edited');
  private readonly locked = h('span.locked', { hidden: true, title: 'Protected with a password', 'aria-label': 'Protected with a password', role: 'img' }, icon('lock', 16));
  private readonly saveButton = h('button.button.save', { type: 'button', disabled: true, title: 'Save (Ctrl+S)', onclick: () => void this.save() }, 'Save');
  private readonly counter = h('span.page-counter', { role: 'status' });
  private readonly zoomLabel = h('span.zoom-label');
  private readonly notice = h('div.notice', { hidden: true });
  private readonly findBar = h('div.find-bar', { hidden: true });
  private readonly scroller = h('div.pages', { tabIndex: 0, 'aria-label': 'Pages' });
  private readonly dock = h('div.dock', { role: 'toolbar', 'aria-label': 'Editing tools' });
  private readonly dockNote = h('div.dock-note', { role: 'status', hidden: true });

  constructor(host: ReaderHost) {
    this.host = host;
    const more = iconButton('more', 'More', () => showMenu(more, this.moreMenu()));
    this.el.append(
      h('header.reader-bar',
        iconButton('back', 'Close document', () => void this.close()),
        this.heading,
        this.locked,
        this.edited,
        this.counter,
        h('span.spacer'),
        iconButton('search', 'Search in document', () => this.toggleFind()),
        iconButton('zoomOut', 'Zoom out', () => this.zoomBy(1 / 1.2)),
        this.zoomLabel,
        iconButton('zoomIn', 'Zoom in', () => this.zoomBy(1.2)),
        iconButton('pages', 'Organize pages', () => this.organize()),
        this.saveButton,
        more,
      ),
      this.findBar,
      this.notice,
      this.scroller,
      this.dockNote,
      this.dock,
    );
    this.scroller.addEventListener('scroll', () => this.updateCounter(), { passive: true });
    // With a text tool chosen, letting go of the mouse after selecting text marks it.
    this.scroller.addEventListener('pointerup', (e) => {
      if (this.textTool) setTimeout(() => void this.markSelection(e), 0);
    });
    // A form field changed (text when it loses focus, a box when it is ticked, a choice when picked): write it.
    this.scroller.addEventListener('change', (e) => this.fieldChanged(e));
    // Typing in a field already counts as a change (the Save button, the window's close question).
    this.scroller.addEventListener('input', (e) => {
      if (!(e.target as HTMLElement)?.closest?.('.annotationLayer') || this.typing) return;
      this.typing = true;
      this.report();
    });
    // Leaving a field that was typed in writes it, even when no change event came (a value set by a script, an
    // input method): a change event, when there is one, comes first and has already cleared `typing`.
    this.scroller.addEventListener('focusout', (e) => {
      if (!(e.target as HTMLElement)?.closest?.('.annotationLayer') || !this.typing) return;
      this.fieldChanged(e);
    });
    // Pressing a tool must not take away the text selection it is meant for.
    this.dock.addEventListener('mousedown', (e) => e.preventDefault());
    new ResizeObserver(() => {
      if (this.doc && this.zoom === 'fit') this.layout(true);
    }).observe(this.scroller);
    this.buildFindBar();
  }

  // Opening and closing

  async open(file: string): Promise<boolean> {
    if (file === this.file) return true;
    if (!(await this.leave())) return false;
    try {
      const bytes = await api.read(file);
      let tried: string | null = null; // the password pdf.js was last given: the one that worked, once it opens
      const doc = await openPdf(bytes, async (wrong) => (tried = await prompt(wrong ? 'That password didn’t work' : 'This PDF is protected', 'Password', '', 'Unlock', 'password')));
      // Protected: editable only when the password gives full access. Then the document is worked on decrypted.
      // Anything else (a password that only opens it, protection PDFoundry can't undo itself) is shown read-only.
      const access = await pdf.access(bytes, tried);
      this.reset();
      this.file = file;
      this.bytes = access.bytes;
      this.savedBytes = access.bytes;
      this.password = access.password;
      this.passwordNote = access.changesPassword;
      this.doc = doc;
      this.sizes = await pageSizes(doc);
      this.editable = access.editable;
      this.heading.textContent = title(file);
      this.heading.title = file;
      this.showProtection();
      this.zoom = 'fit';
      this.layout(false);
      this.drawDock();
      this.report();
      this.host.opened(file);
      return true;
    } catch (e) {
      if (!(e instanceof PasswordNeeded)) toast(message(e), true);
      return false;
    }
  }

  /** The lock beside the title, and the notice of a protected file that can only be read. */
  private showProtection(): void {
    this.locked.hidden = !this.password;
    this.notice.hidden = this.editable && !this.passwordNote;
    this.notice.replaceChildren(icon('lock', 18), this.editable
      ? 'Saving will set this document’s password to the one you entered.'
      : 'This PDF is protected, so it can be read but not changed.');
  }

  private reset(): void {
    this.observer?.disconnect();
    this.password = null;
    this.passwordNote = false;
    this.typing = false;
    this.stale = false;
    this.widgets.clear();
    this.signatureFields = null;
    void this.doc?.loadingTask.destroy();
    this.doc = null;
    this.undoStack = [];
    this.ink = null;
    this.placement = null;
    this.toolMode = 'none';
    this.disarm();
    this.selected = null;
    this.matches = [];
    this.matchAt = -1;
    this.findBar.hidden = true;
    this.scroller.replaceChildren();
  }

  /** Closes the document and goes back to the recent files. Asks first when there are unsaved changes. */
  async close(): Promise<boolean> {
    if (!(await this.leave())) return false;
    this.reset();
    this.file = '';
    this.bytes = this.savedBytes = new Uint8Array();
    this.showProtection();
    this.report();
    this.host.back();
    return true;
  }

  // Saving

  get dirty(): boolean {
    return !!this.file && (this.bytes !== this.savedBytes || this.typing || this.pendingFields > 0);
  }

  /** Writes the field being typed in (by leaving it) and waits for every field change to be in `bytes`. */
  private async commitFields(): Promise<void> {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active.closest('.annotationLayer')) active.blur(); // fires its change
    await this.fieldWork;
  }

  /** Shows whether there are unsaved changes, here and in the window's title bar. */
  private report(): void {
    this.edited.hidden = !this.dirty;
    this.saveButton.disabled = !this.dirty;
    this.saveButton.classList.toggle('primary', this.dirty);
    void api.setDocument(this.file || null, this.dirty);
  }

  /** Before closing or opening something else: with unsaved changes, asks whether to save them. False = stay. */
  private async leave(): Promise<boolean> {
    await this.commitFields();
    if (!this.dirty) return true;
    const answer = await choose(`Save the changes to “${title(this.file)}”?`, 'Your changes will be lost if you don’t save them.', [
      { label: 'Cancel', value: 'cancel' },
      { label: 'Don’t Save', value: 'discard' },
      { label: 'Save', value: 'save', kind: 'primary' },
    ]);
    if (answer === 'save') return this.save();
    return answer === 'discard';
  }

  /** Writes the changes back to the file that was opened. Returns false when it could not be saved. */
  async save(): Promise<boolean> {
    await this.commitFields(); // the field being typed in (Ctrl+S, the close question) is written first
    if (!this.file || this.busy) return false;
    if (!this.dirty) return true;
    if (this.ink || this.placement) {
      toast('Finish what you are placing or drawing first.', true);
      return false;
    }
    const bytes = this.bytes;
    try {
      await api.save(this.file, await this.encrypted(bytes));
      this.savedBytes = bytes;
      this.passwordNote = false; // done: the file now opens with the password that was entered
      this.showProtection();
      this.report();
      toast('Saved.');
      return true;
    } catch (e) {
      toast(message(e), true);
      return false;
    }
  }

  /** Saves the document, changes included, as a new file and continues in that file. The original is left as it was. */
  async saveAs(): Promise<boolean> {
    await this.commitFields();
    if (!this.file || this.busy) return false;
    const bytes = this.bytes;
    try {
      const saved = await api.saveAs(title(this.file), await this.encrypted(bytes), this.file);
      if (!saved) return false;
      this.file = saved;
      this.savedBytes = bytes;
      this.passwordNote = false;
      this.showProtection();
      this.heading.textContent = title(saved);
      this.heading.title = saved;
      this.report();
      this.host.opened(saved);
      toast('Saved.');
      return true;
    } catch (e) {
      toast(message(e), true);
      return false;
    }
  }

  /** What goes to disk: the bytes encrypted with the document's password, when it has one. */
  private encrypted(bytes: Uint8Array): Promise<Uint8Array> {
    return this.password ? pdf.protect(bytes, this.password) : Promise.resolve(bytes);
  }

  // Passwords (More › Protect with Password / Remove Password, and Tools › Password-protect on the open document)

  /** The open document's password, when `file` is that document and it is protected; otherwise null. */
  passwordFor(file: string): string | null {
    return file && file === this.file ? this.password : null;
  }

  /**
   * Protects the open file with a new password, in place. With unsaved changes it asks first, because protecting
   * writes the file (Save and Protect). From then on every save is encrypted with this password.
   */
  async protectWithPassword(): Promise<boolean> {
    await this.commitFields();
    if (!this.file || this.busy) return false;
    if (this.password || (await pdf.isProtected(this.bytes))) {
      alreadyProtected(this.file);
      return false;
    }
    if (!this.editable) {
      toast('This PDF can’t be changed by PDFoundry.', true);
      return false;
    }
    if (this.ink || this.placement) {
      toast('Finish what you are placing or drawing first.', true);
      return false;
    }
    if (this.dirty) {
      const answer = await choose(`Save the changes to “${title(this.file)}” first?`, 'Protecting saves the file, with your changes.', [
        { label: 'Cancel', value: 'cancel' },
        { label: 'Save and Protect', value: 'save', kind: 'primary' },
      ]);
      if (answer !== 'save') return false;
    }
    const password = await protectSheet(this.file);
    if (!password || !this.file) return false;
    const bytes = this.bytes;
    try {
      await api.save(this.file, await pdf.protect(bytes, password));
      this.password = password;
      this.savedBytes = bytes;
      this.passwordNote = false;
      this.showProtection();
      this.report();
      toast(`“${title(this.file)}” is now protected with a password.`);
      return true;
    } catch (e) {
      toast(message(e), true);
      return false;
    }
  }

  /** Takes the password off: the file is written without encryption (with any unsaved changes), after asking. */
  async removePassword(): Promise<boolean> {
    await this.commitFields();
    if (!this.file || this.busy || !this.password) return false;
    if (this.ink || this.placement) {
      toast('Finish what you are placing or drawing first.', true);
      return false;
    }
    const ok = await confirm(`Remove the password from “${title(this.file)}”?`,
      `Anyone who has the file will be able to open it.${this.dirty ? ' Your unsaved changes are saved too.' : ''}`, 'Remove Password');
    if (!ok || !this.file) return false;
    const bytes = this.bytes;
    try {
      await api.save(this.file, bytes);
      this.password = null;
      this.savedBytes = bytes;
      this.passwordNote = false;
      this.showProtection();
      this.report();
      toast('The password was removed.');
      return true;
    } catch (e) {
      toast(message(e), true);
      return false;
    }
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
        page.addEventListener('click', (e) => void this.pageClick(e, index));
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
      const index = Number(page.dataset.index);
      const fresh = h('canvas.sheet');
      // An editable document gets its form fields as inputs; otherwise they are drawn as saved.
      const forms = this.editable ? new Map<string, HTMLCanvasElement>() : undefined;
      await (await renderPage(doc, index, fresh, page.clientWidth, undefined, forms)).promise;
      if (doc !== this.doc || page.dataset.painted !== key) return; // outdated: a newer paint is under way
      page.querySelector('canvas.sheet')?.replaceWith(fresh); // swapped in when ready, so a redraw never flashes blank
      // The selectable text over the picture, sized by the page box's pixels per point.
      const layer = h('div');
      page.style.setProperty('--total-scale-factor', String(page.clientWidth / this.sizes[index].width));
      const pieces = await renderTextLayer(doc, index, layer);
      // A newer paint of this page (a zoom, a change) may have replaced the picture meanwhile; its own text layer wins.
      if (doc !== this.doc || !fresh.isConnected) return;
      page.querySelector('.textLayer')?.remove();
      fresh.after(layer);
      page.dataset.text = String(pieces);
      if (forms) await this.paintForms(doc, index, page, layer, forms);
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

  /** Applies a change and shows the result. Nothing is written to the file until Save. The previous version is kept for Undo. */
  async change(action: (bytes: Uint8Array) => Promise<Uint8Array>, done?: string, quiet = false): Promise<boolean> {
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
        this.undoStack.push(before);
        if (this.undoStack.length > UNDO_STEPS) this.undoStack.shift();
        if (quiet) {
          this.bytes = after;
          this.stale = true;
        } else await this.show(after);
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
      this.report();
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
    this.stale = false;
    this.typing = false; // the inputs are drawn again from the bytes
    this.widgets.clear();
    this.signatureFields = null;
    this.matches = [];
    this.layout(true);
  }

  /** Redraws from `bytes` when field changes were written without a redraw (before printing or organizing). */
  private async fresh(): Promise<void> {
    if (this.stale) await this.show(this.bytes);
  }

  async undo(): Promise<void> {
    await this.fieldWork; // a field change still being written comes first, so Undo takes back the latest step
    if (this.busy || !this.undoStack.length || this.ink || this.placement) return;
    this.deselect();
    this.busy = true;
    try {
      const previous = this.undoStack.pop()!;
      await this.show(previous);
    } catch (e) {
      toast(message(e), true);
    } finally {
      this.busy = false;
      this.drawDock();
      this.report();
    }
  }

  get data(): Uint8Array {
    return this.bytes;
  }

  /** The open document's bytes when `file` is it and it has unsaved changes; otherwise null. */
  unsaved(file: string): Uint8Array | null {
    return this.dirty && file === this.file ? this.bytes : null;
  }

  // The dock: the mode bar (modebar.ts), or the controls of the tool in use

  private drawDock(): void {
    this.dock.hidden = !this.doc || !this.editable; // a protected PDF is only read: no tools
    this.dockNote.hidden = true;
    if (this.placement) {
      this.dock.replaceChildren(
        h('span.dock-hint', 'Drag it into place. Drag the corner to resize.'),
        button('Cancel', () => this.endPlacement()),
        button('Place', () => void this.place(), 'primary'),
      );
    } else if (this.ink) {
      const ink = this.ink;
      const choice = (name: string, color: string, marker: boolean) =>
        h('button.swatch', {
          type: 'button', title: name, 'aria-label': name, 'aria-pressed': String(ink.marker === marker && ink.color === color),
          onclick: () => {
            ink.color = color;
            ink.marker = marker;
            this.drawDock();
          },
        }, h('span', { class: marker ? 'colour-marker' : `colour-${name.toLowerCase()}` }));
      const count = [...ink.strokes.values()].reduce((n, s) => n + s.length, 0);
      this.dock.replaceChildren(
        h('span.dock-hint', ink.marker ? 'Drag over what you want to mark.' : 'Draw on the page.'),
        ...PENS.map((pen) => choice(pen.name, pen.color, false)),
        choice(MARKER.name, MARKER.color, true),
        h('button.tool.compact', { type: 'button', disabled: !count, onclick: () => this.removeLastStroke() }, icon('undo', 20), h('span', 'Remove last')),
        button('Cancel', () => this.endInk()),
        h('button.button.primary', { type: 'button', disabled: !count, onclick: () => void this.saveInk() }, 'Done'),
      );
    } else {
      this.dock.replaceChildren(...modeBar(this.toolMode, {
        canUndo: this.undoStack.length > 0,
        armed: { text: this.textTool, shape: this.shapeTool, comment: this.commentTool },
      }, {
        shapes: (anchor) => this.shapesMenu(anchor),
        comment: () => this.armComment(),
        setMode: (mode) => this.setMode(mode),
        highlight: (anchor) => this.highlightMenu(anchor),
        textMark: (mark) => this.chooseTextTool({ mark, rgba: MARK_RED }, true),
        text: () => {
          this.disarm();
          this.textDialog();
        },
        draw: () => this.beginInk(),
        signature: () => void signatureDialog((signature) => this.beginPlacement(signature, 0.32)),
        initials: () => void signatureDialog((initials) => this.beginPlacement(initials, 0.12), 'initials'),
        date: () => {
          this.disarm();
          this.textDialog(todayText(), 'Add date');
        },
        mark: (mark) => void this.placeMark(mark),
        pages: () => this.organize(),
        undo: () => void this.undo(),
      }));
      const hint = this.textTool ? MARK_HINTS[this.textTool.mark]
        : this.shapeTool ? `Drag on the page to draw ${this.shapeTool.kind === 'ellipse' || this.shapeTool.kind === 'arrow' ? 'an' : 'a'} ${SHAPE_NAMES[this.shapeTool.kind].toLowerCase()}.`
          : this.commentTool ? 'Click the page where the comment goes.' : '';
      if (hint) {
        this.dockNote.textContent = hint;
        this.dockNote.hidden = false;
      }
    }
    const free = !this.ink && !this.placement;
    this.el.classList.toggle('inking', !!this.ink);
    this.el.classList.toggle('placing', !!this.placement);
    this.el.classList.toggle('marking', !!this.textTool && free);
    this.el.classList.toggle('picking', (!!this.shapeTool || this.commentTool) && free);
  }

  /** Switches the bar to a mode (or back to reading with 'none'). */
  setMode(mode: ToolMode): void {
    if (!this.editable && mode !== 'none') return;
    this.toolMode = mode;
    this.disarm();
    this.drawDock();
  }

  /** Puts away whichever Markup tool was waiting for the page (text, shape, comment). */
  private disarm(): void {
    this.deselect();
    this.textTool = null;
    this.shapeTool = null;
    this.commentTool = false;
  }

  // Marking selected text (Markup: Highlight, Underline, Strikethrough)

  private highlightMenu(anchor: HTMLElement): void {
    const waiting = this.selectedBoxes(); // the menu may take the selection away; remember it now
    showMenu(anchor, HIGHLIGHT_COLOURS.map((colour) => ({
      label: colour.name,
      swatch: colour.css,
      action: () => this.chooseTextTool({ mark: 'highlight', rgba: colour.rgba }, false, waiting),
    })));
  }

  /**
   * Picks a text tool. Text that is already selected is marked at once; otherwise the tool waits for a selection.
   * `toggle`: choosing the tool that is already waiting puts it away again.
   */
  private chooseTextTool(tool: TextTool, toggle: boolean, waiting = this.selectedBoxes()): void {
    const next = toggle && this.textTool?.mark === tool.mark ? null : tool;
    this.disarm();
    this.deselect();
    this.textTool = next;
    this.drawDock();
    if (this.textTool && waiting.size) void this.applyMark(waiting);
  }

  /** The selected text as boxes on each page (fractions of the page), one box per line. Empty when nothing is selected. */
  private selectedBoxes(): Map<number, PageRect[]> {
    const found = new Map<number, PageRect[]>();
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed) return found;
    // Boxes are joined per page and per direction of the text, which comes from the page's rotation plus the span's
    // own (pdf.js sets --rotate on rotated runs); a box's shape says nothing about it.
    const byLine = new Map<string, { index: number; direction: TextDirection; boxes: PageRect[] }>();
    const pages = this.pageEls();
    for (let r = 0; r < selection.rangeCount; r++) {
      const range = selection.getRangeAt(r);
      // Only the text itself: a range also reports whole elements it covers (a page, a canvas), which must not count.
      const walker = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_TEXT);
      const nodes: Node[] = range.commonAncestorContainer.nodeType === Node.TEXT_NODE ? [range.commonAncestorContainer] : [];
      for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node);
      for (const node of nodes) {
        const layer = node.parentElement?.closest<HTMLElement>('.textLayer');
        const page = layer?.closest<HTMLElement>('.page');
        if (!layer || !page || !range.intersectsNode(node)) continue;
        const index = pages.indexOf(page);
        const turn = Number(layer.getAttribute('data-main-rotation') || 0) + (parseFloat(node.parentElement!.style.getPropertyValue('--rotate')) || 0);
        const direction: TextDirection = Math.round(turn / 90) % 2 ? 'down' : 'across';
        const key = `${index}:${direction}`;
        const line = byLine.get(key) ?? { index, direction, boxes: [] };
        byLine.set(key, line);
        const part = document.createRange();
        part.selectNodeContents(node);
        if (node === range.startContainer) part.setStart(node, range.startOffset);
        if (node === range.endContainer) part.setEnd(node, range.endOffset);
        const box = page.getBoundingClientRect();
        for (const rect of part.getClientRects()) {
          const x = Math.max(0, (rect.left - box.left) / box.width);
          const y = Math.max(0, (rect.top - box.top) / box.height);
          const right = Math.min(1, (rect.right - box.left) / box.width);
          const bottom = Math.min(1, (rect.bottom - box.top) / box.height);
          if (right > x && bottom > y) line.boxes.push({ x, y, width: right - x, height: bottom - y });
        }
      }
    }
    for (const { index, direction, boxes } of byLine.values()) {
      const joined = joinBoxes(boxes, direction);
      if (joined.length) found.set(index, [...(found.get(index) ?? []), ...joined]);
    }
    return found;
  }

  /** After a selection with a text tool chosen: marks it, or explains why a page without text cannot be marked. */
  private async markSelection(e: PointerEvent): Promise<void> {
    if (!this.textTool || this.ink || this.placement || this.busy) return;
    const boxes = this.selectedBoxes();
    if (boxes.size) return void this.applyMark(boxes);
    const page = (e.target as HTMLElement | null)?.closest?.<HTMLElement>('.page');
    if (page?.dataset.text === '0') toast(NO_TEXT);
  }

  private async applyMark(boxes: Map<number, PageRect[]>): Promise<void> {
    const tool = this.textTool;
    if (!tool) return;
    window.getSelection()?.removeAllRanges();
    await this.change(async (bytes) => {
      let out = bytes;
      for (const [index, rects] of boxes) out = await pdf.markText(out, index, rects, tool.mark, tool.rgba);
      return out;
    });
  }

  // Drawing (Markup > Draw), with the highlighter pen

  private beginInk(): void {
    if (!this.editable) return;
    this.disarm();
    this.ink = { marker: false, color: PENS[0].color, strokes: new Map() };
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
    if (e.button !== 0) return;
    if (!this.ink && !this.busy && !(e.target as HTMLElement).closest('.mark-selection')) {
      if (this.shapeTool) return this.drawShape(e, index);
      if (this.commentTool) return this.newComment(e, index);
    }
    if (!this.ink) return;
    e.preventDefault();
    const ink = this.ink;
    const box = page.getBoundingClientRect();
    const point = (ev: PointerEvent) => ({
      x: Math.min(1, Math.max(0, (ev.clientX - box.left) / box.width)),
      y: Math.min(1, Math.max(0, (ev.clientY - box.top) / box.height)),
    });
    // Widths are in points, stored as a fraction of the page width: a 14 pt marker, a 2 pt pen.
    const stroke: Stroke = { points: [point(e)], color: ink.color, width: (ink.marker ? 14 : 2) / this.sizes[index].width, marker: ink.marker };
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
        // Pen strokes cover the page; marker strokes darken it, so they are a second, see-through Ink annotation.
        const pen = strokes.filter((s) => !s.marker);
        if (pen.length) out = await pdf.ink(out, index, pen);
        const marker = strokes.filter((s) => s.marker);
        if (marker.length) out = await pdf.ink(out, index, marker, { multiply: true, opacity: 0.55 });
      }
      return out;
    });
    if (ok) this.endInk();
  }

  // Shapes and comments (Markup › Shapes, Comment)

  private shapesMenu(anchor: HTMLElement): void {
    const pick = (kind: ShapeKind) => {
      this.disarm();
      this.shapeTool = { kind, rgba: this.shapeColour };
      this.drawDock();
    };
    const colour = (rgba: Rgba) => {
      this.shapeColour = rgba;
      const kind = this.shapeTool?.kind ?? 'rectangle';
      pick(kind);
    };
    const same = (a: Rgba, b: Rgba) => a.every((v, i) => Math.abs(v - b[i]) < 1e-6);
    showMenu(anchor, [
      ...(Object.keys(SHAPE_NAMES) as ShapeKind[]).map((kind) => ({ label: SHAPE_NAMES[kind], icon: kind, action: () => pick(kind) })),
      'separator',
      ...SHAPE_COLOURS.map((c) => ({ label: same(c.rgba, this.shapeColour) ? `${c.name} ✓` : c.name, swatch: c.css, action: () => colour(c.rgba) })),
      ...(this.shapeTool ? ['separator' as const, { label: 'Stop drawing shapes', icon: 'close', action: () => { this.disarm(); this.drawDock(); } }] : []),
    ]);
  }

  private armComment(): void {
    const on = !this.commentTool;
    this.disarm();
    this.commentTool = on;
    this.drawDock();
  }

  /** The point of a pointer event on a page, in page fractions, kept on the page. */
  private pointOn(page: HTMLElement, e: { clientX: number; clientY: number }): PagePoint {
    const box = page.getBoundingClientRect();
    return { x: Math.min(1, Math.max(0, (e.clientX - box.left) / box.width)), y: Math.min(1, Math.max(0, (e.clientY - box.top) / box.height)) };
  }

  /** A drag on the page with a shape tool: shows the shape as it is dragged, and adds it when the button goes up. */
  private drawShape(e: PointerEvent, index: number): void {
    const tool = this.shapeTool!;
    const page = e.currentTarget as HTMLElement;
    e.preventDefault();
    const start = this.pointOn(page, e);
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'shape-preview');
    svg.setAttribute('viewBox', '0 0 1 1');
    svg.setAttribute('preserveAspectRatio', 'none');
    const css = `rgb(${tool.rgba.slice(0, 3).map((v) => Math.round(v * 255)).join(',')})`;
    const el = document.createElementNS('http://www.w3.org/2000/svg', tool.kind === 'rectangle' ? 'rect' : tool.kind === 'ellipse' ? 'ellipse' : 'line');
    el.setAttribute('stroke', css);
    el.setAttribute('fill', 'none');
    el.setAttribute('stroke-width', '2');
    el.setAttribute('vector-effect', 'non-scaling-stroke');
    svg.append(el);
    page.append(svg);
    let end = start;
    const update = () => {
      const [x, y, w, hgt] = [Math.min(start.x, end.x), Math.min(start.y, end.y), Math.abs(end.x - start.x), Math.abs(end.y - start.y)];
      if (tool.kind === 'rectangle') Object.entries({ x, y, width: w, height: hgt }).forEach(([k, v]) => el.setAttribute(k, String(v)));
      else if (tool.kind === 'ellipse') Object.entries({ cx: x + w / 2, cy: y + hgt / 2, rx: w / 2, ry: hgt / 2 }).forEach(([k, v]) => el.setAttribute(k, String(v)));
      else Object.entries({ x1: start.x, y1: start.y, x2: end.x, y2: end.y }).forEach(([k, v]) => el.setAttribute(k, String(v)));
    };
    update();
    try { page.setPointerCapture(e.pointerId); } catch { /* not a live pointer */ }
    const move = (ev: PointerEvent) => {
      end = this.pointOn(page, ev);
      update();
    };
    const up = (ev: PointerEvent) => {
      page.removeEventListener('pointermove', move);
      page.removeEventListener('pointerup', up);
      page.removeEventListener('pointercancel', up);
      end = this.pointOn(page, ev);
      const size = this.sizes[index];
      const dx = (end.x - start.x) * size.width;
      const dy = (end.y - start.y) * size.height;
      const big = tool.kind === 'line' || tool.kind === 'arrow' ? Math.hypot(dx, dy) >= 4 : Math.abs(dx) >= 4 && Math.abs(dy) >= 4;
      if (ev.type === 'pointercancel' || !big) return svg.remove();
      void this.change((bytes) => pdf.shape(bytes, index, tool.kind, start, end, tool.rgba)).finally(() => svg.remove());
    };
    page.addEventListener('pointermove', move);
    page.addEventListener('pointerup', up);
    page.addEventListener('pointercancel', up);
  }

  /** A click on the page with Comment chosen: asks for the text and puts the note there. */
  private newComment(e: PointerEvent, index: number): void {
    e.preventDefault();
    const at = this.pointOn(e.currentTarget as HTMLElement, e);
    this.commentDialog('', async (text) => {
      await this.change((bytes) => pdf.comment(bytes, index, at, text));
    });
  }

  /** The comment sheet: type (or change) the text; Done saves it. An empty comment is not kept. */
  private commentDialog(value: string, done: (text: string) => Promise<void>, remove?: () => Promise<void>): void {
    const input = h('textarea.comment-text', { rows: 5, placeholder: 'Type your comment', 'aria-label': 'Comment', dir: 'auto' });
    input.value = value;
    const d = dialog(value ? 'Comment' : 'Add comment', [input], [
      ...(remove ? [h('span.spacer-left', button('Delete', () => {
        d.close();
        void remove();
      }, 'danger'))] : []),
      button('Cancel', () => d.close()),
      button('Done', () => {
        d.close();
        if (input.value.trim() || value) void done(input.value);
      }, 'primary'),
    ]);
    input.focus();
  }

  // Selecting marks: a click on a mark (with no tool waiting) selects it; drag to move, corners to resize, Delete.

  /** A click on a page with no tool waiting: selects the mark under the pointer, or clears the selection. */
  private async pageClick(e: MouseEvent, index: number): Promise<void> {
    if (!this.editable || this.busy || this.ink || this.placement || this.textTool || this.shapeTool || this.commentTool) return;
    const target = e.target as HTMLElement;
    if (target.closest('.mark-selection, .annotationLayer, .sign-field, .placement')) return;
    const selection = window.getSelection();
    if (selection && !selection.isCollapsed) return; // the click ended a text selection
    const page = e.currentTarget as HTMLElement;
    const point = this.pointOn(page, e);
    const bytes = this.bytes;
    let found: MarkInfo | undefined;
    try {
      // A few pixels of slack, so thin lines and underlines can be hit; the topmost mark wins.
      const box = page.getBoundingClientRect();
      const [sx, sy] = [5 / box.width, 5 / box.height];
      found = (await pdf.marks(bytes, index)).reverse()
        .find((m) => point.x >= m.rect.x - sx && point.x <= m.rect.x + m.rect.width + sx && point.y >= m.rect.y - sy && point.y <= m.rect.y + m.rect.height + sy);
    } catch {
      found = undefined;
    }
    if (bytes !== this.bytes) return; // changed meanwhile
    if (found) this.select(index, found);
    else this.deselect();
  }

  /** The selected mark's page and id, for the self-test. */
  get selectedMark(): { page: number; mark: MarkInfo } | null {
    return this.selected ? { page: this.selected.page, mark: this.selected.mark } : null;
  }

  deselect(): void {
    this.selected?.box.remove();
    this.selected = null;
  }

  /** Draws the selection box around a mark: handles to resize (marks that can be), and its menu. */
  select(index: number, mark: MarkInfo): void {
    this.deselect();
    const page = this.pageEls()[index];
    if (!page) return;
    const resizable = mark.movable && mark.kind !== 'comment';
    const box = h('div.mark-selection', {
      tabIndex: 0, role: 'group', class: mark.movable ? 'movable' : '',
      'aria-label': `Selected ${mark.kind === 'stamp' ? 'item' : mark.kind}. ${mark.movable ? 'Drag to move, arrow keys to nudge. ' : ''}Delete removes it.`,
    });
    if (resizable) for (const corner of ['nw', 'ne', 'sw', 'se']) box.append(h('span.corner', { class: corner, dataset: { corner }, 'aria-hidden': 'true' }));
    const menu = h('div.mark-menu', { role: 'toolbar', 'aria-label': 'Selected mark' },
      ...(mark.kind === 'comment' ? [h('button', { type: 'button', onclick: () => void this.openComment() }, icon('comment', 16), 'Open')] : []),
      h('button.danger', { type: 'button', onclick: () => void this.deleteSelected() }, icon('trash', 16), 'Delete'));
    box.append(menu);
    this.selected = { page: index, mark, box };
    this.placeSelection(mark.rect);
    page.append(box);
    box.addEventListener('pointerdown', (e) => this.dragSelection(e));
    box.addEventListener('keydown', (e) => this.selectionKey(e));
    box.addEventListener('dblclick', () => {
      if (mark.kind === 'comment') void this.openComment();
    });
    box.focus({ preventScroll: true });
  }

  private placeSelection(rect: PageRect): void {
    const s = this.selected!;
    Object.assign(s.box.style, { left: `${rect.x * 100}%`, top: `${rect.y * 100}%`, width: `${rect.width * 100}%`, height: `${rect.height * 100}%` });
    s.box.classList.toggle('menu-below', rect.y < 0.08);
  }

  /** Dragging the selection: inside moves it, a corner resizes it. Stamps and drawings keep their proportions. */
  private dragSelection(e: PointerEvent): void {
    const s = this.selected;
    if (!s || e.button !== 0 || (e.target as HTMLElement).closest('.mark-menu')) return;
    e.preventDefault();
    e.stopPropagation();
    if (!s.mark.movable) return;
    const page = this.pageEls()[s.page].getBoundingClientRect();
    const corner = (e.target as HTMLElement).dataset.corner;
    const keep = s.mark.kind === 'stamp' || s.mark.kind === 'ink';
    const from = s.mark.rect;
    const start = { x: e.clientX, y: e.clientY };
    let rect = from;
    try { s.box.setPointerCapture(e.pointerId); } catch { /* not a live pointer */ }
    const move = (ev: PointerEvent) => {
      const dx = (ev.clientX - start.x) / page.width;
      const dy = (ev.clientY - start.y) / page.height;
      rect = corner ? resized(from, corner, dx, dy, keep, page.width / page.height) : moved(from, dx, dy);
      this.placeSelection(rect);
    };
    const up = () => {
      s.box.removeEventListener('pointermove', move);
      s.box.removeEventListener('pointerup', up);
      s.box.removeEventListener('pointercancel', up);
      if (rect !== from && (Math.abs(rect.x - from.x) + Math.abs(rect.y - from.y) + Math.abs(rect.width - from.width) + Math.abs(rect.height - from.height)) > 1e-4) void this.moveSelected(rect);
    };
    s.box.addEventListener('pointermove', move);
    s.box.addEventListener('pointerup', up);
    s.box.addEventListener('pointercancel', up);
  }

  private selectionKey(e: KeyboardEvent): void {
    const s = this.selected;
    if (!s) return;
    if (e.key === 'Delete' || e.key === 'Backspace') {
      e.preventDefault();
      void this.deleteSelected();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      this.deselect();
    } else if (e.key === 'Enter' && s.mark.kind === 'comment') {
      e.preventDefault();
      void this.openComment();
    } else if (s.mark.movable && e.key.startsWith('Arrow')) {
      e.preventDefault();
      // One point at a time, ten with Shift.
      const size = this.sizes[s.page];
      const step = e.shiftKey ? 10 : 1;
      const [dx, dy] = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key] ?? [0, 0];
      void this.moveSelected(moved(s.mark.rect, dx / size.width, dy / size.height));
    }
  }

  /** Moves or resizes the selected mark (one Undo step) and keeps it selected. */
  private async moveSelected(rect: PageRect): Promise<void> {
    const s = this.selected;
    if (!s) return;
    const { page, mark } = s;
    const ok = await this.change((bytes) => pdf.moveMark(bytes, page, mark.id, rect));
    await this.reselect(page, mark.id, ok ? undefined : mark);
  }

  /** Selects the mark with this id again after a change (or `fallback` as it was, when the change failed). */
  private async reselect(page: number, id: string, fallback?: MarkInfo): Promise<void> {
    const mark = fallback ?? (await pdf.marks(this.bytes, page).catch(() => [])).find((m) => m.id === id);
    if (mark) this.select(page, mark);
    else this.deselect();
  }

  async deleteSelected(): Promise<void> {
    const s = this.selected;
    if (!s) return;
    this.deselect();
    await this.change((bytes) => pdf.deleteMark(bytes, s.page, s.mark.id));
  }

  /** Opens the selected comment to read, change or delete it. */
  private async openComment(): Promise<void> {
    const s = this.selected;
    if (!s || s.mark.kind !== 'comment') return;
    const { page, mark } = s;
    const text = await pdf.commentText(this.bytes, page, mark.id).catch(() => '');
    this.commentDialog(text, async (changed) => {
      if (changed.trim() === text.trim()) return;
      await this.change((bytes) => pdf.updateComment(bytes, page, mark.id, changed));
      await this.reselect(page, mark.id);
    }, async () => {
      this.deselect();
      await this.change((bytes) => pdf.deleteMark(bytes, page, mark.id));
    });
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

  /** Text (or Date, with today's date filled in and editable) to place on the page. */
  private textDialog(value = '', heading = 'Add text'): void {
    const input = h('textarea', { rows: 3, placeholder: 'Type your text', 'aria-label': 'Text to add' });
    input.value = value;
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
    const d = dialog(heading, [input, h('div.pad-tools', h('span.hint', 'Size'), size, h('span.spacer'), h('span.hint', 'Colour'), ...swatches)],
      [button('Cancel', () => d.close()), add]);
  }

  /** ✓ or ✗, placed and resized like a signature. About 18 points wide to start with. */
  private async placeMark(mark: 'check' | 'cross'): Promise<void> {
    this.disarm();
    const item = await placedFromPng(await markPng(mark));
    this.beginPlacement(item, 18 / this.sizes[this.currentPage()].width);
  }

  // Form fields: pdf.js's inputs over each page; every committed change is written into the document (one Undo step)

  private async paintForms(doc: PDFDocumentProxy, index: number, page: HTMLElement, after: HTMLElement, forms: Map<string, HTMLCanvasElement>): Promise<void> {
    const layer = h('div');
    const widgets = await renderFormLayer(doc, index, layer, forms);
    const signing = (await (this.signatureFields ??= pdf.fields(this.bytes).then((all) => all.filter((f) => f.kind === 'signature' && !f.readOnly)).catch(() => [])))
      .filter((f) => f.page === index);
    if (doc !== this.doc || !after.isConnected) return;
    for (const widget of widgets) this.widgets.set(widget.id, widget);
    page.querySelectorAll('.annotationLayer, .sign-field').forEach((el) => el.remove());
    after.after(layer);
    for (const field of signing) {
      const box = h('button.sign-field', { type: 'button', title: `Sign ${field.name}`, 'aria-label': `Sign here (${field.name})`, onclick: () => this.signField(field) },
        icon('sign', 16), h('span', 'Sign here'));
      Object.assign(box.style, { left: `${field.rect.x * 100}%`, top: `${field.rect.y * 100}%`, width: `${field.rect.width * 100}%`, height: `${field.rect.height * 100}%` });
      layer.after(box);
    }
  }

  /** A signature field: the chosen signature is stamped to fit its box (the field itself stays an unsigned field). */
  private signField(field: FieldInfo): void {
    if (this.busy || this.ink || this.placement || this.textTool) return;
    void signatureDialog(async (signature) => {
      const size = this.sizes[field.page];
      const box = { width: field.rect.width * size.width, height: field.rect.height * size.height };
      const tall = Math.min(box.width / signature.aspect, box.height); // the signature's height that fits, in points
      const width = (tall * signature.aspect) / size.width;
      const height = tall / size.height;
      const rect = { x: field.rect.x + (field.rect.width - width) / 2, y: field.rect.y + (field.rect.height - height) / 2, width, height };
      await this.change((bytes) => pdf.stamp(bytes, field.page, signature.png, rect));
      URL.revokeObjectURL(signature.url);
    });
  }

  /** A pdf.js form input changed: its value is written into the document's field (an Undo step; saved on Save). */
  private fieldChanged(e: Event): void {
    const input = e.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
    const id = input?.closest?.('.annotationLayer') ? input.getAttribute('data-element-id') : null;
    const widget = id ? this.widgets.get(id) : undefined;
    if (!widget || !this.editable || widget.readOnly) return;
    let value: FieldValue;
    if (input instanceof HTMLSelectElement) {
      const chosen = [...input.selectedOptions].map((o) => o.value); // export values: what the form stores
      value = input.multiple ? chosen : chosen[0] ?? '';
    } else if (input instanceof HTMLInputElement && input.type === 'checkbox') value = input.checked;
    else if (input instanceof HTMLInputElement && input.type === 'radio') {
      if (!input.checked) return; // the button that was turned off: the one turned on reports the choice
      value = widget.buttonValue ?? '';
    } else value = input.value;
    const name = widget.fieldName;
    const size = { width: Math.abs(widget.rect[2] - widget.rect[0]), height: Math.abs(widget.rect[3] - widget.rect[1]) };
    this.typing = false;
    this.pendingFields++;
    this.report();
    this.fieldWork = this.fieldWork.then(() => this.writeField(name, value, size, !!widget.multiLine)).finally(() => {
      this.pendingFields--;
      this.report();
    });
  }

  private async writeField(name: string, value: FieldValue, size: { width: number; height: number }, multiline: boolean): Promise<void> {
    const [file, doc] = [this.file, this.doc];
    await this.idle(); // after any change under way
    if (!this.doc || file !== this.file) return; // closed meanwhile
    // Redrawn meanwhile (another change came first): its inputs don't show this value, so redraw once it is written.
    const quiet = doc === this.doc;
    let written = false;
    if (this.busy) toast('That field couldn’t be changed because PDFoundry was busy. Please try again.', true);
    else {
      try {
        const images: Record<string, Uint8Array> = {};
        // Text the standard font can't show (Arabic, say) is drawn here and becomes the field's appearance; the
        // field's value is still the text itself.
        if (typeof value === 'string' && value && !pdf.canEncode(value)) images[name] = await fieldTextPng(value, size, multiline);
        written = await this.change((bytes) => pdf.fillFields(bytes, { [name]: value }, images), undefined, quiet); // errors are shown
      } catch (e) {
        toast(message(e), true);
      }
    }
    // Not written: the inputs go back to what the document holds, so nothing shows that isn't in it.
    if (!written) {
      await this.idle();
      if (!this.busy && this.doc) await this.show(this.bytes).catch(() => {});
    }
  }

  /** Waits (up to about 10 seconds) until no other change is being applied. */
  private async idle(): Promise<void> {
    for (let i = 0; this.busy && i < 400; i++) await new Promise((r) => setTimeout(r, 25));
  }

  /** Waits for field changes still being written (the self-test, and before saving). */
  settle(): Promise<void> {
    return this.fieldWork;
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
    return [
      { label: 'Organize pages', icon: 'pages', action: () => this.organize() },
      'separator',
      { label: 'Save', icon: 'save', disabled: !this.dirty, action: () => void this.save() },
      { label: 'Save As…', icon: 'copy', action: () => void this.saveAs() },
      'separator',
      this.password
        ? { label: 'Remove Password', icon: 'unlock', action: () => void this.removePassword() }
        : { label: 'Protect with Password', icon: 'lock', disabled: !this.editable, action: () => void this.protectWithPassword() },
      'separator',
      { label: revealLabel(), icon: 'folder', action: () => void api.reveal(this.file) },
      { label: 'Print…', icon: 'print', action: () => void this.print() },
      'separator',
      { label: 'Close document', icon: 'close', action: () => void this.close() },
    ];
  }

  async organize(): Promise<void> {
    if (!this.doc) return;
    if (!this.editable) return void toast('This PDF is protected, so its pages cannot be changed.', true);
    if (this.ink) this.endInk();
    this.endPlacement();
    await this.fieldWork;
    await this.fresh();
    organizer(this);
  }

  /** The open document, for the page organizer's thumbnails. */
  get pdf(): PDFDocumentProxy {
    return this.doc!;
  }

  get pageSizes(): Size[] {
    return this.sizes;
  }

  /** Prints through the system dialog: each page is drawn as an image on its own sheet. */
  async print(): Promise<void> {
    if (!this.doc || this.busy) return;
    await this.fieldWork;
    await this.fresh();
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

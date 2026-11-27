// The Tools view: whole-file tools as tiles in sections, the same grouping as the phone apps' Tools tab. A section
// without tools is not shown.
import { api, title } from './api.ts';
import type { PickedPdf } from './api.ts';
import { h, icon, message, toast } from './dom.ts';
import { extractPages, pickDocuments } from './extract.ts';
import type { Library } from './library.ts';
import { alreadyProtected, protectSheet } from './protect.ts';
import { splitPdf } from './split.ts';
import { exportImages } from './export.ts';
import { compressSheet } from './pagetools.ts';
import * as pdf from '../core/pdf.ts';

/** The tools that change a document: they open it in the reader (in place) with their sheet, or Redact, on top. */
export type ReaderTool = 'numbers' | 'watermark' | 'ocr' | 'redact';

export type ToolsHost = {
  library: Library;
  /** Opens `file` in the reader (asking about unsaved changes to the open one) and starts `tool` there. */
  openWith: (file: string, tool: ReaderTool) => Promise<void>;
  openFile: () => string;
  unsaved: (file: string) => Uint8Array | null;
  /** The open document's password, when `file` is that document and it is protected. */
  passwordFor: (file: string) => string | null;
  /** Password-protect on the document open in the reader: the reader does it, so its next save is encrypted too. */
  protectOpen: () => Promise<boolean>;
};

export type ToolSection = 'create' | 'combine' | 'protect' | 'enhance';
export type PdfTool = 'images' | 'export' | 'merge' | 'extract' | 'split' | 'protect' | 'compress' | ReaderTool;

/** Each section's tile colour comes from the brand palette, as on the iPhone: ember, steel, then graphite. */
const SECTIONS: { id: ToolSection; title: string; tile: string }[] = [
  { id: 'create', title: 'Create', tile: 'tile-ember' },
  { id: 'combine', title: 'Combine & split', tile: 'tile-steel' },
  { id: 'protect', title: 'Protect & optimize', tile: 'tile-coal' },
  { id: 'enhance', title: 'Enhance', tile: 'tile-coal' },
];

const TOOLS: { id: PdfTool; section: ToolSection; title: string; detail: string; symbol: string }[] = [
  { id: 'images', section: 'create', title: 'Create from Images', detail: 'Turn pictures into a PDF', symbol: 'image' },
  { id: 'export', section: 'create', title: 'Export as Images', detail: 'Save pages as pictures', symbol: 'exportImages' },
  { id: 'merge', section: 'combine', title: 'Merge PDFs', detail: 'Join several files into one', symbol: 'merge' },
  { id: 'extract', section: 'combine', title: 'Extract Pages', detail: 'Save chosen pages as a new PDF', symbol: 'extract' },
  { id: 'split', section: 'combine', title: 'Split PDF', detail: 'Make several PDFs from one', symbol: 'split' },
  { id: 'protect', section: 'protect', title: 'Password-protect', detail: 'Lock a PDF with a password', symbol: 'lock' },
  { id: 'compress', section: 'protect', title: 'Compress', detail: 'Make a PDF smaller', symbol: 'compress' },
  { id: 'redact', section: 'protect', title: 'Redact', detail: 'Black out text for good', symbol: 'redact' },
  { id: 'ocr', section: 'enhance', title: 'Make Searchable', detail: 'Find text in scanned pages', symbol: 'searchable' },
  { id: 'numbers', section: 'enhance', title: 'Page Numbers', detail: 'Number every page', symbol: 'numbers' },
  { id: 'watermark', section: 'enhance', title: 'Watermark', detail: 'Stamp text across pages', symbol: 'watermark' },
];

/** What the picker says for the tools that open the document in the reader. */
const READER_HINTS: Record<ReaderTool, string> = {
  numbers: 'Choose the PDF to number. It opens here, and the numbers are added to it; save it when you are happy.',
  watermark: 'Choose the PDF to watermark. It opens here, and the watermark is added to it; save it when you are happy.',
  ocr: 'Choose the PDF to make searchable. It opens here, and text is added to its scanned pages; save it when you are happy.',
  redact: 'Choose the PDF to redact. It opens here in Redact: mark what to remove, then Apply, then save.',
};

export class ToolsView {
  readonly el = h('section.view.tools', { 'aria-label': 'Tools' });
  private readonly library: Library;
  private readonly host: ToolsHost;

  constructor(host: ToolsHost) {
    this.host = host;
    this.library = host.library;
    this.el.append(
      h('header.view-header', h('div', h('h1', 'Tools'))),
      h('div.tools-body',
        ...SECTIONS.filter((section) => TOOLS.some((t) => t.section === section.id)).map((section) =>
          h('section.tool-section', { 'aria-label': section.title },
            h('h2', section.title),
            h('div.tool-grid', ...TOOLS.filter((t) => t.section === section.id).map((tool) =>
              h('button.tool-tile', { type: 'button', 'aria-label': tool.title, onclick: () => void this.run(tool.id) },
                h(`span.tile.big.${section.tile}` as 'span', icon(tool.symbol, 24)),
                h('span.row-text', h('span.row-title', tool.title), h('span.row-detail', tool.detail)),
              ),
            )),
          ),
        ),
      ),
    );
  }

  async run(tool: PdfTool): Promise<void> {
    try {
      if (tool === 'images') return void (await this.library.createFromImages());
      if (tool === 'merge') {
        const files = await pickDocuments({
          title: 'Merge PDFs', mode: 'several', action: 'Next',
          hint: 'Choose two or more files, in the order to join them. The files themselves are not changed.',
          browse: async () => {
            const result = await api.openDialog(); // opened files join the recent list, as with Open
            for (const error of result.errors) toast(error, true);
            return result.opened;
          },
        });
        if (files) this.library.mergeFiles(files);
        return;
      }
      if (tool === 'protect') return void (await this.protect());
      if (tool === 'numbers' || tool === 'watermark' || tool === 'ocr' || tool === 'redact') return void (await this.inReader(tool));
      const name = TOOLS.find((t) => t.id === tool)!.title;
      const hints: Partial<Record<PdfTool, string>> = {
        extract: 'Choose the PDF to take pages from. It is not changed, and it is not opened.',
        split: 'Choose the PDF to split. It is not changed, and it is not opened.',
        compress: 'Choose the PDF to compress. A smaller copy is made; the file itself is not changed, and it is not opened.',
        export: 'Choose the PDF whose pages to save as pictures. It is not changed, and it is not opened.',
      };
      const picked = await pickDocuments({
        title: name, mode: 'one', hint: hints[tool]!,
        browse: () => api.pickPdf(name), // read once; not added to the recent list
      });
      if (!picked) return;
      // The document open in the reader with unsaved changes: use what the user sees, not the older copy on disk.
      // When it is protected, its bytes in the reader are decrypted and its password comes with them.
      const unsaved = typeof picked === 'string' ? this.host.unsaved(picked) : null;
      const source: PickedPdf & { unsaved?: boolean; password?: string | null } = typeof picked !== 'string' ? picked
        : { path: picked, name: title(picked), bytes: unsaved ?? (await api.read(picked)), unsaved: !!unsaved, password: this.host.passwordFor(picked) };
      if (tool === 'extract') await extractPages(source, [this.host.openFile()]);
      else if (tool === 'split') await splitPdf(source, [this.host.openFile()]);
      else if (tool === 'compress') await compressSheet(source, [this.host.openFile()]);
      else await exportImages(source);
    } catch (e) {
      toast(message(e), true);
    }
  }

  /**
   * Page Numbers, Watermark, Make Searchable and Redact change the document itself: one file from the recent list, or
   * one picked with Browse… (opened in place, as Open does: never a copy), opened in the reader with the tool on top.
   */
  private async inReader(tool: ReaderTool): Promise<void> {
    const picked = await pickDocuments({
      title: TOOLS.find((t) => t.id === tool)!.title, mode: 'one', hint: READER_HINTS[tool],
      browse: async () => {
        const result = await api.openDialog(); // opened in place: it joins the recent list, as with Open
        for (const error of result.errors) toast(error, true);
        return result.opened.slice(0, 1);
      },
    });
    if (typeof picked === 'string') await this.host.openWith(picked, tool);
  }

  /**
   * Password-protect: one file from the recent list, or one picked with Browse… (opened in place, so the real file is
   * protected, never a copy). The file is replaced by its protected version once the password is typed twice.
   */
  private async protect(): Promise<void> {
    const picked = await pickDocuments({
      title: 'Password-protect', mode: 'one',
      hint: 'Choose the PDF to protect. The file itself is changed: from then on it needs the password to open.',
      browse: async () => {
        const result = await api.openDialog(); // opened in place: it joins the recent list, as with Open
        for (const error of result.errors) toast(error, true);
        return result.opened.slice(0, 1);
      },
    });
    if (typeof picked !== 'string') return;
    if (picked === this.host.openFile()) return void (await this.host.protectOpen());
    const bytes = await api.read(picked);
    if (await pdf.isProtected(bytes)) return alreadyProtected(picked);
    if (!(await pdf.isEditable(bytes))) return void toast('This PDF can’t be changed by PDFoundry.', true);
    const password = await protectSheet(picked);
    if (!password) return;
    await api.save(picked, await pdf.protect(bytes, password));
    toast(`“${title(picked)}” is now protected with a password.`);
    await this.library.refresh();
  }
}

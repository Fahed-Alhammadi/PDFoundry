// The Tools view: whole-file tools as tiles in sections, the same grouping as the phone apps' Tools tab. A section
// without tools is not shown (Protect & optimize and Enhance are waiting for their first tools).
import { api, title } from './api.ts';
import type { PickedPdf } from './api.ts';
import { h, icon, message, toast } from './dom.ts';
import { extractPages, pickDocuments } from './extract.ts';
import type { Library } from './library.ts';

export type ToolsHost = { library: Library; openFile: () => string; unsaved: (file: string) => Uint8Array | null };

export type ToolSection = 'create' | 'combine' | 'protect' | 'enhance';
export type PdfTool = 'images' | 'merge' | 'extract';

/** Each section's tile colour comes from the brand palette, as on the iPhone: ember, steel, then graphite. */
const SECTIONS: { id: ToolSection; title: string; tile: string }[] = [
  { id: 'create', title: 'Create', tile: 'tile-ember' },
  { id: 'combine', title: 'Combine & split', tile: 'tile-steel' },
  { id: 'protect', title: 'Protect & optimize', tile: 'tile-coal' },
  { id: 'enhance', title: 'Enhance', tile: 'tile-coal' },
];

const TOOLS: { id: PdfTool; section: ToolSection; title: string; detail: string; symbol: string }[] = [
  { id: 'images', section: 'create', title: 'Create from Images', detail: 'Turn pictures into a PDF', symbol: 'image' },
  { id: 'merge', section: 'combine', title: 'Merge PDFs', detail: 'Join several files into one', symbol: 'merge' },
  { id: 'extract', section: 'combine', title: 'Extract Pages', detail: 'Save chosen pages as a new PDF', symbol: 'extract' },
];

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
      const picked = await pickDocuments({
        title: 'Extract Pages', mode: 'one',
        hint: 'Choose the PDF to take pages from. It is not changed, and it is not opened.',
        browse: () => api.pickPdf(), // read once; not added to the recent list
      });
      if (!picked) return;
      // The document open in the reader with unsaved changes: extract what the user sees, not the older copy on disk.
      const unsaved = typeof picked === 'string' ? this.host.unsaved(picked) : null;
      const source: PickedPdf & { unsaved?: boolean } = typeof picked !== 'string' ? picked
        : { path: picked, name: title(picked), bytes: unsaved ?? (await api.read(picked)), unsaved: !!unsaved };
      await extractPages(source, [this.host.openFile()]);
    } catch (e) {
      toast(message(e), true);
    }
  }
}

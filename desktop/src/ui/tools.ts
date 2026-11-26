// The Tools view: whole-file tools as tiles in sections, the same grouping as the phone apps' Tools tab. A section
// without tools is not shown (Protect & optimize and Enhance are waiting for their first tools).
import { api, title } from './api.ts';
import type { PickedPdf } from './api.ts';
import { h, icon, message, toast } from './dom.ts';
import { extractPages, pickDocuments } from './extract.ts';
import type { Library } from './library.ts';

export type ToolSection = 'create' | 'combine' | 'protect' | 'enhance';
export type PdfTool = 'images' | 'merge' | 'extract';

const SECTIONS: { id: ToolSection; title: string }[] = [
  { id: 'create', title: 'Create' },
  { id: 'combine', title: 'Combine & split' },
  { id: 'protect', title: 'Protect & optimize' },
  { id: 'enhance', title: 'Enhance' },
];

const TOOLS: { id: PdfTool; section: ToolSection; title: string; detail: string; symbol: string; tile: string }[] = [
  { id: 'images', section: 'create', title: 'Create from Images', detail: 'Turn pictures into a PDF', symbol: 'image', tile: 'tile-green' },
  { id: 'merge', section: 'combine', title: 'Merge PDFs', detail: 'Join several files into one', symbol: 'merge', tile: 'tile-indigo' },
  { id: 'extract', section: 'combine', title: 'Extract Pages', detail: 'Save chosen pages as a new PDF', symbol: 'extract', tile: 'tile-ember' },
];

export class ToolsView {
  readonly el = h('section.view.tools', { 'aria-label': 'Tools' });
  private readonly library: Library;

  constructor(library: Library) {
    this.library = library;
    this.el.append(
      h('header.view-header', h('div', h('h1', 'Tools'))),
      h('div.tools-body',
        ...SECTIONS.filter((section) => TOOLS.some((t) => t.section === section.id)).map((section) =>
          h('section.tool-section', { 'aria-label': section.title },
            h('h2', section.title),
            h('div.tool-grid', ...TOOLS.filter((t) => t.section === section.id).map((tool) =>
              h('button.tool-tile', { type: 'button', 'aria-label': tool.title, onclick: () => void this.run(tool.id) },
                h(`span.tile.big.${tool.tile}` as 'span', icon(tool.symbol, 24)),
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
      const source: PickedPdf = typeof picked === 'string' ? { path: picked, name: title(picked), bytes: await api.read(picked) } : picked;
      await extractPages(source);
    } catch (e) {
      toast(message(e), true);
    }
  }
}

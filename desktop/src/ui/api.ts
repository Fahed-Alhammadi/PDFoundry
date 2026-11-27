// The window's typed view of the main process (handlers in src/main/main.ts).
import type { InkKind, PartExtension, RecentFile, Settings } from '../main/store.ts';
import type { UpdateState } from '../main/updater.ts';
import type { OcrWord } from '../main/ocr.ts';

export type { InkKind, RecentFile, Settings, UpdateState };
export type { OcrWord };

export type AppInfo = {
  version: string;
  platform: 'darwin' | 'win32' | 'linux';
  packaged: boolean;
  settings: Settings;
  update: UpdateState;
  canUpdate: boolean;
  dark: boolean;
  selfTest: string | null;
  shots: string | null;
};
export type Opened = { opened: string[]; errors: string[] };
export type PickedPdf = { path: string; name: string; bytes: Uint8Array };
export type PickedImage = { name: string; type: 'png' | 'jpeg'; bytes: Uint8Array };
export type LinkName = 'website' | 'privacy' | 'help' | 'feedback' | 'releases' | 'license';

type Bridge = {
  call(name: string, ...args: unknown[]): Promise<any>;
  on(fn: (event: string, data: unknown) => void): void;
  ready(): void;
  pathOf(file: File): string;
  platform: string;
};
const bridge = (window as unknown as { pdfoundry: Bridge }).pdfoundry;

export const api = {
  info: (): Promise<AppInfo> => bridge.call('info'),
  open: (link: LinkName): Promise<void> => bridge.call('open', link),
  setAppearance: (appearance: Settings['appearance']): Promise<Settings> => bridge.call('setAppearance', appearance),

  recent: (): Promise<RecentFile[]> => bridge.call('recent'),
  openDialog: (): Promise<Opened> => bridge.call('openDialog'),
  openPaths: (paths: string[]): Promise<Opened> => bridge.call('openPaths', paths),
  read: async (file: string): Promise<Uint8Array> => new Uint8Array(await bridge.call('read', file)),
  save: (file: string, bytes: Uint8Array): Promise<void> => bridge.call('save', file, bytes),
  /** Asks where to save, writes the file and returns its path; null when the user cancels. */
  /** `keep`: files the new one must not replace (its sources, the open document). */
  saveAs: (suggested: string, bytes: Uint8Array, beside?: string, keep: string[] = []): Promise<string | null> => bridge.call('saveAs', suggested, bytes, beside, keep),
  /** Extract Pages: asks for one PDF and reads it once, without listing it as recent. Null when cancelled. */
  pickPdf: async (title?: string): Promise<PickedPdf | null> => {
    const picked = await bridge.call('pickPdf', title);
    return picked ? { ...picked, bytes: new Uint8Array(picked.bytes) } : null;
  },
  /** Split PDF: asks for a folder for the parts. Only a folder picked here can be written to. Null when cancelled. */
  pickFolder: (): Promise<string | null> => bridge.call('pickFolder'),
  partsExisting: (folder: string, names: string[], extension: PartExtension = 'pdf'): Promise<string[]> => bridge.call('partsExisting', folder, names, extension),
  saveParts: (folder: string, parts: { name: string; bytes: Uint8Array }[], keepBoth: boolean, keep: string[] = [], extension: PartExtension = 'pdf'): Promise<string[]> =>
    bridge.call('saveParts', folder, parts, keepBoth, keep, extension),
  forget: (file: string): Promise<void> => bridge.call('forget', file),
  clearRecent: (): Promise<void> => bridge.call('clearRecent'),
  reveal: (file: string): Promise<void> => bridge.call('reveal', file),
  pickImages: (): Promise<PickedImage[]> => bridge.call('pickImages'),
  setDocument: (file: string | null, edited: boolean, redactions = 0): Promise<void> => bridge.call('setDocument', file, edited, redactions),
  /** Closes the window (asking again about unsaved changes, as closing it does). */
  closeWindow: (): Promise<void> => bridge.call('closeWindow'),
  /** Make Searchable: the words on one page drawn as a PNG (recognised in the main process, offline). */
  ocrPage: (png: Uint8Array): Promise<OcrWord[]> => bridge.call('ocrPage', png),
  /** Stops a page being read (it then fails) and frees the recogniser's memory. */
  ocrStop: (): Promise<void> => bridge.call('ocrStop'),
  closeNow: (): Promise<void> => bridge.call('closeNow'),

  /** Saved signatures, or saved initials (kept apart). */
  signatures: async (kind: InkKind = 'signature'): Promise<{ id: string; png: Uint8Array }[]> =>
    ((await bridge.call('signatures', kind)) as { id: string; png: Uint8Array }[]).map((s) => ({ id: s.id, png: new Uint8Array(s.png) })),
  addSignature: (png: Uint8Array, kind: InkKind = 'signature'): Promise<string> => bridge.call('addSignature', png, kind),
  removeSignature: (id: string, kind: InkKind = 'signature'): Promise<void> => bridge.call('removeSignature', id, kind),

  checkForUpdates: (): Promise<void> => bridge.call('checkForUpdates'),
  installUpdate: (): Promise<void> => bridge.call('installUpdate'),

  devCreate: (name: string, bytes: Uint8Array): Promise<string> => bridge.call('devCreate', name, bytes),
  devWrite: (name: string, bytes: Uint8Array): Promise<string> => bridge.call('devWrite', name, bytes),
  devAnswer: (answer: { save?: string; pick?: string; folder?: string }): Promise<void> => bridge.call('devAnswer', answer),
  devRead: async (file: string): Promise<Uint8Array> => new Uint8Array(await bridge.call('devRead', file)),
  devCapture: (name: string): Promise<void> => bridge.call('devCapture', name),
  devDone: (result: unknown): Promise<void> => bridge.call('devDone', result),

  on: bridge.on,
  ready: bridge.ready,
  pathOf: bridge.pathOf,
};

/** A file's name without its folder and without ".pdf". */
export function title(file: string): string {
  return file.replace(/^.*[\\/]/, '').replace(/\.pdf$/i, '');
}

/** "Show in Finder" on macOS, "Show in Explorer" on Windows, "Show in folder" elsewhere. */
export function revealLabel(): string {
  const platform = document.documentElement.dataset.platform;
  return platform === 'darwin' ? 'Show in Finder' : platform === 'win32' ? 'Show in Explorer' : 'Show in folder';
}

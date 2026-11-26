// The window's typed view of the main process (handlers in src/main/main.ts).
import type { DocumentInfo, Settings } from '../main/store.ts';
import type { UpdateState } from '../main/updater.ts';

export type { DocumentInfo, Settings, UpdateState };

export type AppInfo = {
  version: string;
  platform: 'darwin' | 'win32' | 'linux';
  packaged: boolean;
  settings: Settings;
  documentsFolder: string;
  update: UpdateState;
  canUpdate: boolean;
  dark: boolean;
  selfTest: string | null;
  shots: string | null;
};
export type Imported = { added: string[]; errors: string[] };
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

  list: (): Promise<DocumentInfo[]> => bridge.call('list'),
  read: async (name: string): Promise<Uint8Array> => new Uint8Array(await bridge.call('read', name)),
  write: (name: string, bytes: Uint8Array): Promise<void> => bridge.call('write', name, bytes),
  create: (name: string, bytes: Uint8Array): Promise<string> => bridge.call('create', name, bytes),
  rename: (name: string, wanted: string): Promise<string> => bridge.call('rename', name, wanted),
  duplicate: (name: string): Promise<string> => bridge.call('duplicate', name),
  remove: (name: string): Promise<void> => bridge.call('remove', name),
  reveal: (name: string): Promise<void> => bridge.call('reveal', name),
  openFolder: (): Promise<void> => bridge.call('openFolder'),
  importDialog: (): Promise<Imported> => bridge.call('importDialog'),
  importPaths: (paths: string[]): Promise<Imported> => bridge.call('importPaths', paths),
  exportCopy: (name: string): Promise<string | null> => bridge.call('exportCopy', name),
  pickImages: (): Promise<PickedImage[]> => bridge.call('pickImages'),

  signatures: async (): Promise<{ id: string; png: Uint8Array }[]> =>
    ((await bridge.call('signatures')) as { id: string; png: Uint8Array }[]).map((s) => ({ id: s.id, png: new Uint8Array(s.png) })),
  addSignature: (png: Uint8Array): Promise<string> => bridge.call('addSignature', png),
  removeSignature: (id: string): Promise<void> => bridge.call('removeSignature', id),

  checkForUpdates: (): Promise<void> => bridge.call('checkForUpdates'),
  installUpdate: (): Promise<void> => bridge.call('installUpdate'),

  devCapture: (name: string): Promise<void> => bridge.call('devCapture', name),
  devDone: (result: unknown): Promise<void> => bridge.call('devDone', result),

  on: bridge.on,
  ready: bridge.ready,
  pathOf: bridge.pathOf,
};

/** A document's name without ".pdf". */
export function title(name: string): string {
  return name.replace(/\.pdf$/i, '');
}

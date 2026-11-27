// PDFoundry for macOS, Windows and Linux: the main process.
// It owns the window, file access (src/main/store.ts) and updates (src/main/updater.ts). The window (src/ui) shows
// and edits PDFs and reaches this process only through the calls listed in `handlers` below.
//
// A PDF is opened where it is and saved back to the same file when the user chooses Save; nothing is copied into
// the app. The window may read and save only files the user picked (a system dialog, a drop, or "Open with").
// The one exception only reads: Extract Pages and Split PDF get the bytes of the file picked in their own dialog, once,
// and that file is not listed (so the window cannot read or save it afterwards). Split PDF writes its parts only into
// a folder the user has just picked.
//
// The window itself can never use the network: it is served from the app:// scheme and every other request is
// refused. The only thing that goes online is the update check in this process (GitHub releases).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { BrowserWindow, Menu, app, dialog, ipcMain, nativeTheme, net, protocol, session, shell } from 'electron';
import type { MenuItemConstructorOptions } from 'electron';
import { Store, cleanName } from './store.ts';
import type { InkKind, PartExtension, Settings } from './store.ts';
import { REPO, Updater } from './updater.ts';
import { OcrEngine, ocrPaths } from './ocr.ts';
import { welcomeGuide } from '../core/pdf.ts';

const OUT = __dirname;
const UI = path.join(OUT, 'ui');
const WEBSITE = 'https://fahed-alhammadi.github.io/PDFoundry/';
const LINKS: Record<string, string> = {
  website: WEBSITE,
  privacy: `${WEBSITE}privacy.html`,
  help: `https://github.com/${REPO}#readme`,
  feedback: `https://github.com/${REPO}/issues`,
  releases: `https://github.com/${REPO}/releases/latest`,
  license: `https://github.com/${REPO}/blob/main/LICENSE`,
};

// Development runs can keep their data apart from the installed app's, and drive the window for the self-test and
// the screenshots (desktop/README.md). None of this exists in a packaged app.
const dev = !app.isPackaged;
if (dev && process.env.PDFOUNDRY_DATA) app.setPath('userData', path.resolve(process.env.PDFOUNDRY_DATA));

protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }]);

let win: BrowserWindow | null = null;
let store: Store;
/** PDFs the system asked us to open before the window was ready. */
const pending: string[] = [];
/** Does the open document have changes that are not saved? Reported by the window. */
let unsaved = false;
/** How many areas the open document has marked for redaction and not applied. Closing the window asks about them first. */
let redactions = 0;
/** Make Searchable's engine, started the first time it is used. */
let ocr: OcrEngine | null = null;
/** Set once the user has answered the "save your changes?" question, so the window may really close. */
let closing = false;
/** Development only (the self-test): the answer to the next save or open dialog, so no system dialog is shown. */
let devAnswer: { save?: string; pick?: string; folder?: string } = {};
/** Folders the user picked for Split PDF's parts: the only folders the window may look into or write new files in. */
const pickedFolders = new Set<string>();

function pickedFolder(folder: unknown): string {
  if (typeof folder !== 'string' || !pickedFolders.has(path.resolve(folder))) throw new Error('Choose a folder first.');
  return path.resolve(folder);
}

const updater = new Updater((state) => send('update', state));

function send(event: string, data?: unknown): void {
  if (win && !win.isDestroyed()) win.webContents.send('pdfoundry:event', event, data);
}

function applyAppearance(settings: Settings): void {
  nativeTheme.themeSource = settings.appearance;
}

/** Lists PDFs as recent files (which lets the window read them) and returns the ones that were accepted. */
function openPaths(paths: string[]): { opened: string[]; errors: string[] } {
  const opened: string[] = [];
  const errors: string[] = [];
  for (const source of paths) {
    try {
      if (typeof source !== 'string' || !path.isAbsolute(source)) throw new Error('That file could not be found.');
      const file = store.remember(source);
      if (!opened.includes(file)) opened.push(file);
      app.addRecentDocument(file);
    } catch (e) {
      const code = (e as NodeJS.ErrnoException).code;
      errors.push(code === 'ENOENT' ? `${path.basename(source)} could not be found.` : e instanceof Error ? e.message : String(e));
    }
  }
  return { opened, errors };
}

function openFromSystem(paths: string[]): void {
  const pdfs = paths.filter((p) => /\.pdf$/i.test(p) && existsSync(p));
  if (!pdfs.length) return;
  if (!win) {
    // No window yet (still starting), or it was closed (macOS keeps the app running): the new window asks for
    // these as soon as it is ready.
    pending.push(...pdfs);
    if (app.isReady() && store) createWindow();
    return;
  }
  send('opened', openPaths(pdfs));
  if (win.isMinimized()) win.restore();
  win.focus();
}

/** Asks where to save a new file. Returns null when cancelled. */
async function chooseSavePath(title: string, suggested: string, folder?: string): Promise<string | null> {
  if (dev && devAnswer.save) {
    const answer = devAnswer.save;
    delete devAnswer.save;
    return answer;
  }
  const picked = await dialog.showSaveDialog(win!, {
    title,
    defaultPath: path.join(folder && existsSync(folder) ? folder : app.getPath('documents'), `${cleanName(suggested)}.pdf`),
    filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
  });
  return picked.canceled || !picked.filePath ? null : picked.filePath;
}

type Handler = (...args: any[]) => unknown;

const inkKind = (kind: unknown): InkKind => (kind === 'initials' ? 'initials' : 'signature');

const handlers: Record<string, Handler> = {
  // The app
  info: () => ({
    version: app.getVersion(),
    platform: process.platform,
    packaged: app.isPackaged,
    settings: store.settings,
    update: updater.state,
    canUpdate: updater.canInstall,
    dark: nativeTheme.shouldUseDarkColors,
    selfTest: dev ? (process.env.PDFOUNDRY_SELFTEST ?? null) : null,
    shots: dev ? (process.env.PDFOUNDRY_SHOTS ?? null) : null,
  }),
  open: (link: string) => {
    if (LINKS[link]) void shell.openExternal(LINKS[link]);
  },
  setAppearance: (appearance: Settings['appearance']) => {
    if (!['system', 'light', 'dark'].includes(appearance)) throw new Error('Unknown appearance.');
    const settings = store.update({ appearance });
    applyAppearance(settings);
    return settings;
  },

  // Files
  recent: () => store.recent(),
  openDialog: async () => {
    const picked = await dialog.showOpenDialog(win!, {
      title: 'Open PDF',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
    });
    return picked.canceled ? { opened: [], errors: [] } : openPaths(picked.filePaths);
  },
  openPaths: (paths: string[]) => openPaths(Array.isArray(paths) ? paths : []),
  read: (file: string) => store.read(file),
  save: (file: string, bytes: Uint8Array) => store.save(file, bytes),
  /**
   * Save As: asks where, writes the file there and returns its path (null when cancelled). `keep` lists files the new
   * one must not replace (its sources, the document open in the reader).
   */
  saveAs: async (suggested: string, bytes: Uint8Array, beside?: string, keep?: string[]) => {
    const target = await chooseSavePath('Save As', suggested, typeof beside === 'string' ? path.dirname(beside) : undefined);
    if (!target) return null;
    const saved = store.saveNew(target, bytes, Array.isArray(keep) ? keep.filter((k) => typeof k === 'string') : []);
    app.addRecentDocument(saved);
    return saved;
  },
  /**
   * Extract Pages: asks for one PDF and hands its bytes to the window, once. The file is not added to the recent
   * list, so this grants no access beyond this read. Null when cancelled.
   */
  pickPdf: async (title?: string) => {
    let file: string | undefined;
    if (dev && devAnswer.pick) {
      file = devAnswer.pick;
      delete devAnswer.pick;
    } else {
      const picked = await dialog.showOpenDialog(win!, {
        title: typeof title === 'string' ? title : 'Extract Pages',
        properties: ['openFile'],
        filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
      });
      file = picked.canceled ? undefined : picked.filePaths[0];
    }
    if (!file) return null;
    return { path: file, name: path.basename(file), bytes: store.readOnce(file) };
  },
  /** Split PDF: asks for the folder to put the parts in. Null when cancelled. */
  pickFolder: async () => {
    let folder: string | undefined;
    if (dev && devAnswer.folder) {
      folder = devAnswer.folder;
      delete devAnswer.folder;
    } else {
      const picked = await dialog.showOpenDialog(win!, { title: 'Choose a Folder for the Parts', buttonLabel: 'Choose', properties: ['openDirectory', 'createDirectory'] });
      folder = picked.canceled ? undefined : picked.filePaths[0];
    }
    if (!folder) return null;
    pickedFolders.add(path.resolve(folder));
    return path.resolve(folder);
  },
  /** Which of Split's part names are taken in the picked folder. */
  partsExisting: (folder: string, names: string[], extension?: unknown) =>
    store.existing(pickedFolder(folder), Array.isArray(names) ? names.filter((n) => typeof n === 'string') : [], (extension ?? 'pdf') as PartExtension),
  /**
   * Writes Split's parts into the picked folder; `keepBoth` numbers names that are taken. The store itself refuses to
   * write over `keep` (the source), the open document and every recent file. The parts are not listed.
   */
  saveParts: (folder: string, parts: { name: string; bytes: Uint8Array }[], keepBoth: boolean, keep?: string[], extension?: unknown) => {
    if (!Array.isArray(parts) || !parts.every((p) => p && typeof p.name === 'string' && p.bytes instanceof Uint8Array)) throw new Error('There is nothing to save.');
    return store.saveParts(pickedFolder(folder), parts, keepBoth === true, Array.isArray(keep) ? keep.filter((k) => typeof k === 'string') : [], (extension ?? 'pdf') as PartExtension);
  },
  forget: (file: string) => store.forget(file),
  clearRecent: () => {
    store.clearRecent();
    app.clearRecentDocuments();
  },
  reveal: (file: string) => {
    if (store.knows(file)) shell.showItemInFolder(path.resolve(file));
  },
  pickImages: async () => {
    const picked = await dialog.showOpenDialog(win!, {
      title: 'Create from images',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg'] }],
    });
    if (picked.canceled) return [];
    return picked.filePaths.map((file) => ({
      name: path.basename(file).replace(/\.[^.]+$/, ''),
      type: /\.png$/i.test(file) ? 'png' : 'jpeg',
      bytes: readFileSync(file) as Uint8Array,
    }));
  },

  // The open document, as the window reports it: shown in the title bar, and asked about before closing.
  setDocument: (file: string | null, edited: boolean, marked?: unknown) => {
    unsaved = !!file && edited === true;
    redactions = file && typeof marked === 'number' && Number.isInteger(marked) && marked > 0 ? marked : 0;
    store.setCurrent(file); // kept on the recent list, so it can always be saved
    if (!win) return;
    const name = typeof file === 'string' ? path.basename(file) : '';
    win.setTitle(name ? `${name}${unsaved && process.platform !== 'darwin' ? ' (edited)' : ''} - PDFoundry` : 'PDFoundry');
    if (process.platform === 'darwin') {
      win.setRepresentedFilename(typeof file === 'string' ? file : '');
      win.setDocumentEdited(unsaved);
    }
  },
  /** The window finished saving (or discarding) after the close question, and may go now. */
  closeNow: () => {
    closing = true;
    unsaved = false;
    redactions = 0;
    win?.close();
  },
  /** The window dealt with its redactions after the close question: closing goes on (and asks about unsaved changes). */
  // Make Searchable: the words on one page (a PNG the window drew at 300 dpi), recognised here with the bundled
  // English and Arabic data. Nothing goes online. ocrStop cancels a page being read (it then fails) and frees memory.
  ocrPage: async (png: unknown) => {
    if (!(png instanceof Uint8Array) || png.length < 24) throw new Error('There is no page to read.');
    ocr ??= new OcrEngine(ocrPaths({ appPath: app.getAppPath(), packaged: app.isPackaged, resourcesPath: process.resourcesPath }));
    const found = await ocr.recognize([{ index: 0, png }], () => {}, () => false);
    return found?.[0].words ?? [];
  },
  ocrStop: async () => {
    const engine = ocr;
    ocr = null;
    await engine?.terminate();
  },
  closeWindow: () => {
    win?.close();
  },

  // Signatures
  signatures: (kind?: string) => store.listSignatures(inkKind(kind)),
  addSignature: (png: Uint8Array, kind?: string) => store.addSignature(png, inkKind(kind)),
  removeSignature: (id: string, kind?: string) => store.removeSignature(id, inkKind(kind)),

  // Updates
  checkForUpdates: () => void updater.check(),
  installUpdate: () => updater.install(),

  // Development only: the self-test and the screenshots.
  /** Writes a file into the throwaway data folder and opens it, as if the user had picked it. */
  devCreate: (name: string, bytes: Uint8Array) => {
    if (!dev) return null;
    const folder = path.join(app.getPath('userData'), 'Test files');
    mkdirSync(folder, { recursive: true });
    const file = path.join(folder, `${cleanName(name)}.pdf`);
    writeFileSync(file, bytes);
    return store.remember(file);
  },
  /** Writes a file into the throwaway data folder without opening it (it is not listed). */
  devWrite: (name: string, bytes: Uint8Array) => {
    if (!dev) return null;
    const folder = path.join(app.getPath('userData'), 'Test files');
    mkdirSync(folder, { recursive: true });
    const file = path.join(folder, `${cleanName(name)}.pdf`);
    writeFileSync(file, bytes);
    return file;
  },
  /** The answer the next save dialog (`save`), Extract/Split open dialog (`pick`) or folder dialog gives, without showing it. */
  devAnswer: (answer: { save?: string; pick?: string; folder?: string }) => {
    if (dev) devAnswer = { ...devAnswer, ...answer };
  },
  devRead: (file: string) => (dev ? readFileSync(file) : null),
  devCapture: async (name: string) => {
    if (!dev || !process.env.PDFOUNDRY_SHOTS || !/^[a-z0-9-]+$/.test(name)) return;
    mkdirSync(process.env.PDFOUNDRY_SHOTS, { recursive: true });
    writeFileSync(path.join(process.env.PDFOUNDRY_SHOTS, `${name}.png`), (await win!.webContents.capturePage()).toPNG());
  },
  devDone: (result: unknown) => {
    if (!dev) return;
    if (process.env.PDFOUNDRY_SELFTEST) writeFileSync(process.env.PDFOUNDRY_SELFTEST, JSON.stringify(result, null, 2));
    closing = true;
    setTimeout(() => app.quit(), 100);
  },
};

function menu(): Menu {
  const mac = process.platform === 'darwin';
  const template: MenuItemConstructorOptions[] = [
    ...(mac
      ? [{
          label: app.name,
          submenu: [
            { label: 'About PDFoundry', click: () => send('menu', 'about') },
            { label: 'Check for Updates…', click: () => send('menu', 'updates') },
            { type: 'separator' },
            { label: 'Settings…', accelerator: 'Cmd+,', click: () => send('menu', 'settings') },
            { type: 'separator' },
            { role: 'services' }, { type: 'separator' }, { role: 'hide' }, { role: 'hideOthers' }, { role: 'unhide' },
            { type: 'separator' }, { role: 'quit' },
          ] as MenuItemConstructorOptions[],
        }]
      : []),
    {
      label: 'File',
      submenu: [
        { label: 'Open…', accelerator: 'CmdOrCtrl+O', click: () => send('menu', 'open') },
        ...(mac ? [{ role: 'recentDocuments', submenu: [{ role: 'clearRecentDocuments' }] } as MenuItemConstructorOptions] : []),
        { label: 'Create from Images…', accelerator: 'CmdOrCtrl+Shift+I', click: () => send('menu', 'images') },
        { type: 'separator' },
        { label: 'Save', accelerator: 'CmdOrCtrl+S', click: () => send('menu', 'save') },
        { label: 'Save As…', accelerator: 'CmdOrCtrl+Shift+S', click: () => send('menu', 'saveAs') },
        { type: 'separator' },
        { label: 'Close Document', accelerator: 'CmdOrCtrl+W', click: () => send('menu', 'closeDocument') },
        { label: 'Print…', accelerator: 'CmdOrCtrl+P', click: () => send('menu', 'print') },
        ...(mac ? [] : [
          { type: 'separator' } as MenuItemConstructorOptions,
          { label: 'Settings', accelerator: 'Ctrl+,', click: () => send('menu', 'settings') } as MenuItemConstructorOptions,
          { type: 'separator' } as MenuItemConstructorOptions,
          { role: 'quit' } as MenuItemConstructorOptions,
        ]),
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { label: 'Undo', accelerator: 'CmdOrCtrl+Z', click: () => send('menu', 'undo') },
        { type: 'separator' },
        { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' },
        { type: 'separator' },
        { label: 'Find in Document…', accelerator: 'CmdOrCtrl+F', click: () => send('menu', 'find') },
      ],
    },
    {
      label: 'View',
      submenu: [
        { label: 'Zoom In', accelerator: 'CmdOrCtrl+=', click: () => send('menu', 'zoomIn') },
        { label: 'Zoom Out', accelerator: 'CmdOrCtrl+-', click: () => send('menu', 'zoomOut') },
        { label: 'Fit Width', accelerator: 'CmdOrCtrl+0', click: () => send('menu', 'zoomFit') },
        { type: 'separator' },
        { label: 'Organize Pages', accelerator: 'CmdOrCtrl+Shift+P', click: () => send('menu', 'pages') },
        { type: 'separator' },
        { role: 'togglefullscreen' },
        ...(dev ? [{ role: 'toggleDevTools' } as MenuItemConstructorOptions] : []),
      ],
    },
    { role: 'windowMenu' },
    {
      role: 'help',
      submenu: [
        { label: 'PDFoundry Help', click: () => handlers.open('help') },
        { label: 'Send Feedback', click: () => handlers.open('feedback') },
        { type: 'separator' },
        { label: 'Website', click: () => handlers.open('website') },
        { label: 'Privacy Policy', click: () => handlers.open('privacy') },
        ...(mac ? [] : [
          { type: 'separator' } as MenuItemConstructorOptions,
          { label: 'Check for Updates…', click: () => send('menu', 'updates') } as MenuItemConstructorOptions,
          { label: 'About PDFoundry', click: () => send('menu', 'about') } as MenuItemConstructorOptions,
        ]),
      ],
    },
  ];
  return Menu.buildFromTemplate(template);
}

let uiSession: Electron.Session | null = null;

/**
 * The window's own session: it serves the interface from app://ui/ and refuses everything else.
 * Set up once. On macOS the window can be closed and opened again while the app keeps running, and registering
 * the app:// address a second time is an error.
 */
function windowSession(): Electron.Session {
  if (uiSession) return uiSession;
  const ui = session.fromPartition('pdfoundry-window');
  ui.protocol.handle('app', (request) => {
    const url = new URL(request.url);
    const file = path.normalize(path.join(UI, decodeURIComponent(url.pathname)));
    if (url.host !== 'ui' || !file.startsWith(UI + path.sep)) return new Response('Not found', { status: 404 });
    return net.fetch(pathToFileURL(file).toString());
  });
  ui.webRequest.onBeforeRequest((details, done) => {
    done({ cancel: !/^(app|blob|data|devtools):/.test(details.url) });
  });
  ui.setPermissionRequestHandler((_wc, _permission, done) => done(false));
  return (uiSession = ui);
}

function createWindow(): void {
  const ui = windowSession();
  closing = false;
  unsaved = false;
  win = new BrowserWindow({
    width: 1180,
    height: 800,
    minWidth: 860,
    minHeight: 600,
    show: false,
    title: 'PDFoundry',
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#12141c' : '#f6f4f1',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    icon: process.platform === 'linux' ? path.join(UI, 'logo.png') : undefined,
    webPreferences: {
      preload: path.join(OUT, 'preload.cjs'),
      session: ui,
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      spellcheck: false,
    },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.once('ready-to-show', () => win?.show());
  // Closing the window (or quitting) with unsaved changes asks first, like any document app.
  win.on('close', (e) => {
    if (closing || !win) return;
    if (redactions > 0) {
      // Areas marked for redaction are only in the window: apply them (then the save question) or leave them.
      e.preventDefault();
      const choice = dialog.showMessageBoxSync(win, {
        type: 'question',
        message: 'Apply the redactions?',
        buttons: ['Apply', 'Discard', 'Cancel'],
        defaultId: 0,
        cancelId: 2,
        noLink: true,
      });
      if (choice < 2) send('redactionsOnClose', choice === 0 ? 'apply' : 'discard'); // the window calls closeWindow
      return;
    }
    if (!unsaved) return;
    e.preventDefault();
    const answer = dialog.showMessageBoxSync(win, {
      type: 'question',
      message: 'Do you want to save the changes you made?',
      detail: 'Your changes will be lost if you don’t save them.',
      buttons: ['Save', 'Don’t Save', 'Cancel'],
      defaultId: 0,
      cancelId: 2,
      noLink: true,
    });
    if (answer === 0) send('saveAndClose'); // the window saves, then calls closeNow
    else if (answer === 1) handlers.closeNow();
  });
  win.on('closed', () => (win = null));
  void win.loadURL('app://ui/index.html');
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_e, argv) => {
    openFromSystem(argv.slice(1));
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.on('open-file', (e, file) => {
    e.preventDefault();
    openFromSystem([file]);
  });

  void app.whenReady().then(async () => {
    store = new Store(app.getPath('userData'));
    applyAppearance(store.settings);
    if (!store.settings.welcomed) {
      // A first document to try things on, kept in the app's own folder.
      if (!store.recent().length) {
        const guide = path.join(store.ownFolder, 'Welcome to PDFoundry.pdf');
        writeFileSync(guide, await welcomeGuide());
        store.remember(guide);
      }
      store.update({ welcomed: true });
    }

    ipcMain.handle('pdfoundry', async (event, name: string, ...args: unknown[]) => {
      if (event.sender !== win?.webContents || !Object.hasOwn(handlers, name)) throw new Error('Not allowed.');
      return handlers[name](...args);
    });
    // Sent by the window once it can take events: hand over anything the system asked us to open meanwhile.
    ipcMain.on('pdfoundry:ready', () => {
      const first = [...pending, ...process.argv.slice(1)];
      pending.length = 0;
      const pdfs = first.filter((p) => /\.pdf$/i.test(p) && existsSync(p));
      if (pdfs.length) send('opened', openPaths(pdfs));
    });
    nativeTheme.on('updated', () => send('theme', nativeTheme.shouldUseDarkColors));

    Menu.setApplicationMenu(menu());
    createWindow();
    if (app.isPackaged) updater.start();

    // Development check (npm run selftest): the window must survive being closed and opened again, twice.
    if (dev && process.env.PDFOUNDRY_REOPEN) {
      const result = process.env.PDFOUNDRY_REOPEN;
      let rounds = 0;
      const again = () => {
        win!.webContents.once('did-finish-load', () => {
          if (++rounds === 3) {
            writeFileSync(result, JSON.stringify({ checks: [{ name: 'the window can be closed and opened again', pass: true }] }));
            closing = true;
            return app.quit();
          }
          win!.once('closed', () => {
            try {
              createWindow();
              again();
            } catch (e) {
              writeFileSync(result, JSON.stringify({ checks: [{ name: 'the window can be closed and opened again', pass: false, detail: String(e) }] }));
              app.quit();
            }
          });
          closing = true;
          win!.close();
        });
      };
      again();
    }

    app.on('activate', () => {
      if (!win) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin' && !(dev && process.env.PDFOUNDRY_REOPEN)) app.quit();
  });
  // OCR's worker thread must not keep the app from quitting.
  app.on('will-quit', () => void handlers.ocrStop());
}

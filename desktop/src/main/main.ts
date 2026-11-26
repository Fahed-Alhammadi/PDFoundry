// PDFoundry for macOS, Windows and Linux: the main process.
// It owns the window, the files (src/main/store.ts) and updates (src/main/updater.ts). The window (src/ui) shows and
// edits PDFs and reaches this process only through the calls listed in `handlers` below.
//
// The window itself can never use the network: it is served from the app:// scheme and every other request is
// refused. The only thing that goes online is the update check in this process (GitHub releases).
import { existsSync, mkdirSync, readFileSync, writeFileSync, copyFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { BrowserWindow, Menu, app, dialog, ipcMain, nativeTheme, net, protocol, session, shell } from 'electron';
import type { MenuItemConstructorOptions } from 'electron';
import { Store } from './store.ts';
import type { Settings } from './store.ts';
import { REPO, Updater } from './updater.ts';
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
// the screenshots (scripts in desktop/README.md). None of this exists in a packaged app.
const dev = !app.isPackaged;
if (dev && process.env.PDFOUNDRY_DATA) app.setPath('userData', path.resolve(process.env.PDFOUNDRY_DATA));

protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }]);

let win: BrowserWindow | null = null;
let store: Store;
/** PDFs the system asked us to open before the window was ready. */
const pending: string[] = [];

const updater = new Updater((state) => send('update', state));

function send(event: string, data?: unknown): void {
  if (win && !win.isDestroyed()) win.webContents.send('pdfoundry:event', event, data);
}

function applyAppearance(settings: Settings): void {
  nativeTheme.themeSource = settings.appearance;
}

/** Brings PDFs into the library. `added` are new documents; `existing` were already there and are not copied again. */
function importPaths(paths: string[]): { added: string[]; existing: string[]; errors: string[] } {
  const added: string[] = [];
  const existing: string[] = [];
  const errors: string[] = [];
  for (const source of paths) {
    try {
      if (typeof source !== 'string' || !path.isAbsolute(source)) throw new Error('That file could not be found.');
      const result = store.importFile(source);
      const list = result.existing ? existing : added;
      if (!added.includes(result.name) && !existing.includes(result.name)) list.push(result.name);
    } catch (e) {
      errors.push(e instanceof Error ? e.message : String(e));
    }
  }
  return { added, existing, errors };
}

function openFromSystem(paths: string[]): void {
  const pdfs = paths.filter((p) => /\.pdf$/i.test(p) && existsSync(p));
  if (!pdfs.length) return;
  if (!win) return void pending.push(...pdfs);
  const result = importPaths(pdfs);
  send('imported', result);
  if (win.isMinimized()) win.restore();
  win.focus();
}

type Handler = (...args: any[]) => unknown;

const handlers: Record<string, Handler> = {
  // The app
  info: () => ({
    version: app.getVersion(),
    platform: process.platform,
    packaged: app.isPackaged,
    settings: store.settings,
    documentsFolder: store.documents,
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

  // Documents
  list: () => store.list(),
  read: (name: string) => store.read(name),
  write: (name: string, bytes: Uint8Array) => store.write(name, bytes),
  create: (name: string, bytes: Uint8Array) => store.create(name, bytes),
  rename: (name: string, wanted: string) => store.rename(name, wanted),
  duplicate: (name: string) => store.duplicate(name),
  remove: async (name: string) => {
    const file = store.path(name);
    // To the system trash, so a delete can be taken back there.
    await shell.trashItem(file).catch(() => rmSync(file, { force: true }));
  },
  reveal: (name: string) => shell.showItemInFolder(store.path(name)),
  openFolder: () => void shell.openPath(store.documents),
  importDialog: async () => {
    const picked = await dialog.showOpenDialog(win!, {
      title: 'Import PDF',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
    });
    return picked.canceled ? { added: [], existing: [], errors: [] } : importPaths(picked.filePaths);
  },
  importPaths: (paths: string[]) => importPaths(Array.isArray(paths) ? paths : []),
  exportCopy: async (name: string) => {
    const source = store.path(name);
    const picked = await dialog.showSaveDialog(win!, {
      title: 'Save a copy',
      defaultPath: path.join(app.getPath('downloads'), name),
      filters: [{ name: 'PDF documents', extensions: ['pdf'] }],
    });
    if (picked.canceled || !picked.filePath) return null;
    copyFileSync(source, picked.filePath);
    return picked.filePath;
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

  // Signatures
  signatures: () => store.listSignatures(),
  addSignature: (png: Uint8Array) => store.addSignature(png),
  removeSignature: (id: string) => store.removeSignature(id),

  // Updates
  checkForUpdates: () => void updater.check(),
  installUpdate: () => updater.install(),

  // Development only: the self-test and the screenshots.
  devCapture: async (name: string) => {
    if (!dev || !process.env.PDFOUNDRY_SHOTS || !/^[a-z0-9-]+$/.test(name)) return;
    mkdirSync(process.env.PDFOUNDRY_SHOTS, { recursive: true });
    writeFileSync(path.join(process.env.PDFOUNDRY_SHOTS, `${name}.png`), (await win!.webContents.capturePage()).toPNG());
  },
  devDone: (result: unknown) => {
    if (!dev) return;
    if (process.env.PDFOUNDRY_SELFTEST) writeFileSync(process.env.PDFOUNDRY_SELFTEST, JSON.stringify(result, null, 2));
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
        { label: 'Import PDF…', accelerator: 'CmdOrCtrl+O', click: () => send('menu', 'import') },
        { label: 'Create from Images…', accelerator: 'CmdOrCtrl+Shift+I', click: () => send('menu', 'images') },
        { type: 'separator' },
        { label: 'Save a Copy…', accelerator: 'CmdOrCtrl+Shift+S', click: () => send('menu', 'export') },
        { label: 'Print…', accelerator: 'CmdOrCtrl+P', click: () => send('menu', 'print') },
        { type: 'separator' },
        ...(mac ? [{ role: 'close' } as MenuItemConstructorOptions] : [
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

function createWindow(): void {
  // The window's own session: it serves the interface from app://ui/ and refuses everything else.
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
      if (!store.list().length) store.create('Welcome to PDFoundry', await welcomeGuide());
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
      if (pdfs.length) send('imported', importPaths(pdfs));
    });
    nativeTheme.on('updated', () => send('theme', nativeTheme.shouldUseDarkColors));

    Menu.setApplicationMenu(menu());
    createWindow();
    if (app.isPackaged) updater.start();

    app.on('activate', () => {
      if (!win) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}

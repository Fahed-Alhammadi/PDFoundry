// The window: a side rail (Documents, Settings) and the view beside it. Opening a document replaces the library
// with the reader until the user goes back.
import { api } from './api.ts';
import type { Imported, UpdateState } from './api.ts';
import { h, icon, message, toast } from './dom.ts';
import { Library } from './library.ts';
import { Reader } from './reader.ts';
import { SettingsView } from './settings.ts';
import { runDemo } from './demo.ts';

type Route = 'documents' | 'settings';

async function start(): Promise<void> {
  const info = await api.info();
  const root = document.documentElement;
  root.dataset.platform = info.platform;
  root.dataset.theme = info.dark ? 'dark' : 'light';

  let route: Route = 'documents';
  const reader = new Reader({
    back: () => {
      show('documents');
      void library.refresh();
    },
    opened: () => show('documents'),
  });
  const library = new Library({ openDocument: (name) => void reader.open(name) });
  const settings = new SettingsView(info);

  const nav = (target: Route, symbol: string, label: string) =>
    h('button.nav', { type: 'button', dataset: { route: target }, onclick: () => (target === 'documents' && route === 'documents' && reader.name ? reader.close() : show(target)) }, icon(symbol, 20), label);
  const updatePill = h('button.update-pill', { type: 'button', hidden: true, onclick: () => void api.installUpdate() });
  const rail = h('nav.rail', { 'aria-label': 'PDFoundry' },
    h('div.rail-brand', h('img', { src: 'logo.png', alt: '', width: 30, height: 30 }), h('span', 'PDFoundry')),
    nav('documents', 'documents', 'Documents'),
    nav('settings', 'settings', 'Settings'),
    h('span.spacer'),
    updatePill,
  );
  const main = h('main', library.el, reader.el, settings.el);
  document.getElementById('app')!.append(rail, main);

  function show(target: Route): void {
    route = target;
    const reading = target === 'documents' && !!reader.name;
    library.el.hidden = target !== 'documents' || reading;
    reader.el.hidden = !reading;
    settings.el.hidden = target !== 'settings';
    for (const b of rail.querySelectorAll<HTMLElement>('.nav')) b.setAttribute('aria-current', String(b.dataset.route === target));
    if (target === 'settings') settings.showSettings();
  }

  function setUpdate(state: UpdateState): void {
    settings.setUpdate(state);
    updatePill.hidden = state.state !== 'ready';
    if (state.state === 'ready') updatePill.replaceChildren(icon('update', 18), h('span', h('strong', `Version ${state.version} is ready`), h('small', 'Restart to update')));
  }

  const menuActions: Record<string, () => void> = {
    about: () => {
      show('settings');
      settings.showAbout();
    },
    settings: () => show('settings'),
    updates: () => {
      show('settings');
      void api.checkForUpdates();
    },
    import: () => {
      show('documents');
      void library.importDialog();
    },
    images: () => {
      show('documents');
      void library.createFromImages();
    },
    export: () => {
      if (reader.name) void import('./library.ts').then((m) => m.saveCopy(reader.name));
    },
    print: () => void reader.print(),
    undo: () => void reader.undo(),
    find: () => reader.toggleFind(true),
    zoomIn: () => reader.zoomBy(1.2),
    zoomOut: () => reader.zoomBy(1 / 1.2),
    zoomFit: () => reader.zoomFit(),
    pages: () => reader.organize(),
  };

  api.on((event, data) => {
    if (event === 'update') setUpdate(data as UpdateState);
    else if (event === 'theme') root.dataset.theme = data ? 'dark' : 'light';
    else if (event === 'menu') menuActions[data as string]?.();
    else if (event === 'imported') {
      show('documents');
      void library.refresh().then(() => library.imported(data as Imported));
    }
  });

  setUpdate(info.update);
  show('documents');
  await library.refresh();
  api.ready();

  if (info.selfTest || info.shots) void runDemo(info, { library, reader, settings, show });
}

window.addEventListener('unhandledrejection', (e) => toast(message(e.reason), true));
void start().catch((e) => {
  document.getElementById('app')!.textContent = `PDFoundry could not start: ${message(e)}`;
});

// Small helpers for building the window without a framework.

type Child = Node | string | null | undefined | false;
type Props = Record<string, unknown>;

/** Creates an element: h('button.primary', { onclick, title }, 'Save'). Props starting with "on" are listeners. */
export function h<K extends keyof HTMLElementTagNameMap>(spec: K | `${K}.${string}`, props?: Props | Child, ...children: Child[]): HTMLElementTagNameMap[K] {
  const [tag, ...classes] = spec.split('.');
  const el = document.createElement(tag) as HTMLElementTagNameMap[K];
  if (classes.length) el.className = classes.join(' ');
  let rest = children;
  if (props && typeof props === 'object' && !(props instanceof Node)) {
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2), value as EventListener);
      else if (key === 'class') el.className += ` ${value}`;
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (key in el && key !== 'list') (el as any)[key] = value;
      else el.setAttribute(key, String(value));
    }
  } else {
    rest = [props as Child, ...children];
  }
  for (const child of rest) if (child || child === '') el.append(child);
  return el;
}

/** Line icons, 24 x 24, drawn with the text colour. */
const ICONS: Record<string, string> = {
  documents: 'M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M14 3v5h5 M9 13h6 M9 17h6',
  settings: 'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z M19.4 13a7.6 7.6 0 0 0 0-2l2-1.5-2-3.4-2.3 1a7.6 7.6 0 0 0-1.7-1L15 3.5h-4l-.4 2.6a7.6 7.6 0 0 0-1.7 1l-2.3-1-2 3.4L6.6 11a7.6 7.6 0 0 0 0 2l-2 1.5 2 3.4 2.3-1a7.6 7.6 0 0 0 1.7 1l.4 2.6h4l.4-2.6a7.6 7.6 0 0 0 1.7-1l2.3 1 2-3.4z',
  plus: 'M12 5v14 M5 12h14',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-3.8-3.8',
  back: 'M15 5l-7 7 7 7',
  forward: 'M9 5l7 7-7 7',
  more: 'M5 12h.01 M12 12h.01 M19 12h.01',
  close: 'M6 6l12 12 M18 6L6 18',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  highlight: 'M4 20h7 M14.5 4.5l5 5L11 18l-5 1 1-5z',
  text: 'M5 6V4h14v2 M12 4v16 M9 20h6',
  draw: 'M3 17c3-6 5 4 8-2s4-8 6-3 2 5 4 3',
  sign: 'M3 17c2-7 4-9 5-6 1 2-3 7-1 7s3-5 5-5 0 4 2 4 3-3 7-3 M3 21h18',
  undo: 'M9 7L4 12l5 5 M4 12h10a6 6 0 0 1 0 12h-2',
  merge: 'M6 4v5a6 6 0 0 0 6 6v5 M18 4v5a6 6 0 0 1-6 6 M9 17l3 3 3-3',
  trash: 'M4 7h16 M9 7V4h6v3 M6.5 7l1 13h9l1-13 M10 11v6 M14 11v6',
  rotateLeft: 'M4 5v5h5 M4.6 10A8 8 0 1 1 6 17.5',
  rotateRight: 'M20 5v5h-5 M19.4 10A8 8 0 1 0 18 17.5',
  copy: 'M9 9h10v11H9z M5 15V4h10',
  extract: 'M12 15V4 M8 8l4-4 4 4 M5 14v5h14v-5',
  blank: 'M7 3h7l5 5v13H7z M12 12v6 M9 15h6',
  zoomIn: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-3.8-3.8 M11 8v6 M8 11h6',
  zoomOut: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-3.8-3.8 M8 11h6',
  lock: 'M7 11V8a5 5 0 0 1 10 0v3 M5 11h14v10H5z',
  up: 'M6 15l6-6 6 6',
  down: 'M6 9l6 6 6-6',
  external: 'M14 5h5v5 M19 5l-8 8 M18 14v5H5V6h5',
  help: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7 M12 17h.01',
  feedback: 'M4 5h16v11H9l-5 4z',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18',
  shield: 'M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6z M9 12l2 2 4-4',
  appearance: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 3v18 M12 7a5 5 0 0 1 0 10',
  folder: 'M3 6h6l2 2h10v11H3z',
  update: 'M4 12a8 8 0 0 1 14-5.3L20 9 M20 4v5h-5 M20 12a8 8 0 0 1-14 5.3L4 15 M4 20v-5h5',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v6 M12 7.5h.01',
  image: 'M4 5h16v14H4z M4 16l4.5-4.5 4 4 3-3L20 17 M15.5 9.5h.01',
  import: 'M12 4v11 M8 11l4 4 4-4 M5 15v4h14v-4',
  print: 'M7 9V4h10v5 M7 17H4v-7h16v7h-3 M7 14h10v6H7z',
  rename: 'M4 20h4L19 9l-4-4L4 16z M13 7l4 4',
  save: 'M12 4v11 M8 11l4 4 4-4 M5 20h14',
  pages: 'M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z',
};

export function icon(name: string, size = 20): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.7');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[name] ?? '');
  svg.append(path);
  return svg;
}

/** A button that shows only an icon; `label` is its name for screen readers and its tooltip. */
export function iconButton(name: string, label: string, onclick: (e: MouseEvent) => void, extra = ''): HTMLButtonElement {
  return h(`button.icon-button${extra ? `.${extra}` : ''}` as 'button', { type: 'button', title: label, 'aria-label': label, onclick }, icon(name));
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function formatDate(ms: number): string {
  return new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function pages(n: number): string {
  return n === 1 ? '1 page' : `${n} pages`;
}

// Dialogs, menus and messages

let toastTimer: ReturnType<typeof setTimeout> | undefined;

/** A short message at the bottom of the window. */
export function toast(message: string, error = false): void {
  document.querySelector('.toast')?.remove();
  const el = h('div.toast', { role: error ? 'alert' : 'status', class: error ? 'error' : '' }, message);
  document.body.append(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), error ? 6000 : 3200);
}

export type MenuItem = { label: string; icon?: string; danger?: boolean; disabled?: boolean; action: () => void } | 'separator';

/** A pop-up menu anchored under (or above) a button. */
export function showMenu(anchor: HTMLElement, items: MenuItem[]): void {
  closeMenu();
  const menu = h('div.menu', { role: 'menu' });
  for (const item of items) {
    if (item === 'separator') {
      menu.append(h('div.menu-separator'));
      continue;
    }
    menu.append(
      h('button', {
        type: 'button', role: 'menuitem', disabled: item.disabled, class: item.danger ? 'danger' : '',
        onclick: () => { closeMenu(); item.action(); },
      }, item.icon ? icon(item.icon, 18) : null, item.label),
    );
  }
  document.body.append(menu);
  const a = anchor.getBoundingClientRect();
  const m = menu.getBoundingClientRect();
  const top = a.bottom + m.height + 12 > window.innerHeight ? Math.max(8, a.top - m.height - 6) : a.bottom + 6;
  menu.style.top = `${top}px`;
  menu.style.left = `${Math.max(8, Math.min(a.right - m.width, window.innerWidth - m.width - 8))}px`;
  (menu.querySelector('button:not(:disabled)') as HTMLElement | null)?.focus();
  setTimeout(() => {
    document.addEventListener('pointerdown', outside, true);
    document.addEventListener('keydown', escape, true);
  });
}

function outside(e: Event): void {
  if (!(e.target as HTMLElement).closest('.menu')) closeMenu();
}
function escape(e: KeyboardEvent): void {
  if (e.key === 'Escape') closeMenu();
}
export function closeMenu(): void {
  document.querySelector('.menu')?.remove();
  document.removeEventListener('pointerdown', outside, true);
  document.removeEventListener('keydown', escape, true);
}

export type Dialog = { el: HTMLElement; body: HTMLElement; close: () => void };

/** A modal dialog. `actions` are its buttons, right-aligned; Escape and the backdrop close it. */
export function dialog(title: string, body: Child[], actions: Child[], options: { wide?: boolean; onClose?: () => void } = {}): Dialog {
  const bodyEl = h('div.dialog-body', ...body);
  const panel = h('div.dialog', { role: 'dialog', 'aria-modal': 'true', 'aria-label': title, class: options.wide ? 'wide' : '' },
    h('h2', title), bodyEl, actions.length ? h('div.dialog-actions', ...actions) : null);
  const backdrop = h('div.backdrop', panel);
  const close = () => {
    backdrop.remove();
    document.removeEventListener('keydown', onKey, true);
    options.onClose?.();
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && !document.querySelector('.menu')) {
      e.stopPropagation();
      close();
    }
  };
  backdrop.addEventListener('pointerdown', (e) => {
    if (e.target === backdrop) close();
  });
  document.addEventListener('keydown', onKey, true);
  document.body.append(backdrop);
  (panel.querySelector('input, textarea, button.primary') as HTMLElement | null)?.focus();
  return { el: panel, body: bodyEl, close };
}

export function button(label: string, onclick: () => void, kind: '' | 'primary' | 'danger' = ''): HTMLButtonElement {
  return h('button', { type: 'button', class: `button ${kind}`, onclick }, label);
}

/** Asks before something that cannot simply be taken back. Resolves true when confirmed. */
export function confirm(title: string, message: string, action: string, danger = true): Promise<boolean> {
  return new Promise((resolve) => {
    let answer = false;
    const d = dialog(title, [h('p', message)], [
      button('Cancel', () => d.close()),
      button(action, () => { answer = true; d.close(); }, danger ? 'danger' : 'primary'),
    ], { onClose: () => resolve(answer) });
  });
}

/** A question with several answers. Resolves the chosen value, or `fallback` when dismissed (Escape, backdrop). */
export function choose<T extends string>(title: string, message: string, answers: { label: string; value: T; kind?: '' | 'primary' | 'danger' }[], fallback: T = answers[0].value): Promise<T> {
  return new Promise((resolve) => {
    let answer = fallback;
    const d = dialog(title, [h('p', message)], answers.map((a) => button(a.label, () => { answer = a.value; d.close(); }, a.kind ?? '')), { onClose: () => resolve(answer) });
  });
}

/** Asks for one line of text. Resolves null when cancelled. */
export function prompt(title: string, label: string, value: string, action: string, type = 'text'): Promise<string | null> {
  return new Promise((resolve) => {
    let answer: string | null = null;
    const input = h('input', { type, value, 'aria-label': label, placeholder: label, autocomplete: 'off' });
    const submit = () => {
      if (!input.value.trim()) return;
      answer = input.value;
      d.close();
    };
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') submit();
    });
    const d = dialog(title, [input], [button('Cancel', () => d.close()), button(action, submit, 'primary')], { onClose: () => resolve(answer) });
    input.select();
  });
}

export function message(e: unknown): string {
  const text = e instanceof Error ? e.message : String(e);
  // Errors from the main process arrive wrapped: "Error invoking remote method 'pdfoundry': Error: <message>".
  return text.replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
}

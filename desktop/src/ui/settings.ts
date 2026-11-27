// Settings and About. Settings follows the system layout: plain rows in unlabelled groups, About in its own last one.
import { api } from './api.ts';
import type { AppInfo, LinkName, UpdateState } from './api.ts';
import { h, icon } from './dom.ts';
import { signatureDialog } from './sign.ts';

export class SettingsView {
  readonly el = h('section.view.settings', { 'aria-label': 'Settings' });
  private info: AppInfo;
  private showingAbout = false;

  constructor(info: AppInfo) {
    this.info = info;
  }

  setUpdate(state: UpdateState): void {
    this.info.update = state;
    if (!this.el.hidden) void this.render();
  }

  showAbout(): void {
    this.showingAbout = true;
    void this.render();
  }

  showSettings(): void {
    this.showingAbout = false;
    void this.render();
  }

  async render(): Promise<void> {
    if (this.showingAbout) return this.renderAbout();
    const [recent, signatures, initials] = await Promise.all([api.recent(), api.signatures(), api.signatures('initials')]);
    const appearance = h('div.segmented', { role: 'radiogroup', 'aria-label': 'Appearance' },
      ...(['system', 'light', 'dark'] as const).map((value) =>
        h('button', {
          type: 'button', role: 'radio', 'aria-checked': String(this.info.settings.appearance === value),
          onclick: async () => {
            this.info.settings = await api.setAppearance(value);
            void this.render();
          },
        }, value === 'system' ? 'System' : value === 'light' ? 'Light' : 'Dark'),
      ),
    );
    this.el.replaceChildren(
      h('header.view-header', h('div', h('h1', 'Settings'))),
      h('div.settings-body',
        h('div.group', row('appearance', 'tile-indigo', 'Appearance', null, appearance)),
        h('div.group',
          row('sign', 'tile-ember', 'Signatures', signatures.length === 1 ? '1 saved' : `${signatures.length} saved`, null,
            () => void signatureDialog().then(() => this.render())),
          row('initials', 'tile-ember', 'Initials', initials.length === 1 ? '1 saved' : `${initials.length} saved`, null,
            () => void signatureDialog(undefined, 'initials').then(() => this.render())),
          row('documents', 'tile-grey', 'Recent files', recent.length === 1 ? '1 file on the list' : `${recent.length} files on the list`,
            h('button.button', { type: 'button', disabled: !recent.length, onclick: () => void api.clearRecent().then(() => this.render()) }, 'Clear list')),
        ),
        h('p.group-note', 'PDFoundry opens your files where they are and saves changes back to the same file when you choose Save. Clearing the list does not delete any file.'),
        h('div.group', this.updateRow()),
        h('p.group-note', 'Checking for updates is the only time PDFoundry uses the internet. Your documents are never sent anywhere.'),
        h('div.group', row('info', 'tile-graphite', 'About PDFoundry', null, null, () => this.showAbout())),
      ),
    );
  }

  private updateRow(): HTMLElement {
    const state = this.info.update;
    let detail = `Version ${this.info.version}`;
    let control: HTMLElement | null = h('button.button', { type: 'button', onclick: () => void api.checkForUpdates() }, 'Check for updates');
    if (state.state === 'checking') {
      detail = 'Checking…';
      (control as HTMLButtonElement).disabled = true;
    } else if (state.state === 'none') detail = `Version ${this.info.version} is the latest.`;
    else if (state.state === 'downloading') {
      detail = `Downloading version ${state.version}${state.percent ? `, ${state.percent}%` : '…'}`;
      control = null;
    } else if (state.state === 'ready') {
      detail = `Version ${state.version} is ready to install.`;
      control = h('button.button.primary', { type: 'button', onclick: () => void api.installUpdate() }, 'Restart to update');
    } else if (state.state === 'available') {
      detail = `Version ${state.version} is available.`;
      control = h('button.button.primary', { type: 'button', onclick: () => void api.open('releases') }, 'Download');
    } else if (state.state === 'error') detail = `Could not check for updates: ${state.message}`;
    return row('update', 'tile-green', 'Updates', detail, control);
  }

  private renderAbout(): void {
    const link = (name: string, label: string, target: LinkName, detail?: string) =>
      h('button.row.link', { type: 'button', onclick: () => void api.open(target) },
        h('span.link-icon', icon(name, 20)), h('span.row-text', h('span.row-title', label), detail ? h('span.row-detail', detail) : null), icon('external', 16));
    this.el.replaceChildren(
      h('header.view-header', h('div.crumb',
        h('button.button.quiet', { type: 'button', onclick: () => this.showSettings() }, icon('back', 16), 'Settings'), h('h1', 'About PDFoundry'))),
      h('div.settings-body',
        h('div.brand-panel',
          h('img.brand-motif', { src: 'logo.png', alt: '', 'aria-hidden': 'true' }),
          h('img.brand-icon', { src: 'logo.png', alt: 'PDFoundry icon', width: 96, height: 96 }),
          h('h2', 'PDFoundry'),
          h('p', `© ${new Date().getFullYear()} Fahed Alhammadi. All rights reserved.`),
          h('span.version', `Version ${this.info.version}`),
        ),
        h('div.group', link('help', 'Help', 'help'), link('feedback', 'Send feedback', 'feedback')),
        h('div.group', link('globe', 'Website', 'website', 'fahed-alhammadi.github.io/PDFoundry'), link('shield', 'Privacy policy', 'privacy')),
        h('p.about-footer', 'Open source under the MIT License.'),
      ),
    );
  }
}

/** One settings row: a white symbol on a coloured tile, a title, an optional detail and an optional control. */
function row(symbol: string, tile: string, label: string, detail: string | null, control: HTMLElement | null, onclick?: () => void): HTMLElement {
  const content = [
    h('span.tile', { class: tile }, icon(symbol, 18)),
    h('span.row-text', h('span.row-title', label), detail ? h('span.row-detail', detail) : null),
    control,
    onclick ? icon('forward', 16) : null,
  ];
  return onclick ? h('button.row', { type: 'button', onclick }, ...content) : h('div.row', ...content);
}

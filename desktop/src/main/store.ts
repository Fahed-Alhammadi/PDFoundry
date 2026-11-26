// What PDFoundry keeps on this computer: the list of recently opened files, saved signatures and settings.
// Documents themselves stay wherever the user keeps them: a PDF is opened in place and saved back to the same file.
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, openSync, readFileSync, readSync, closeSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';

export type RecentFile = { path: string; name: string; folder: string; size: number; modified: number; opened: number };
export type Settings = { appearance: 'system' | 'light' | 'dark'; welcomed: boolean };

const DEFAULTS: Settings = { appearance: 'system', welcomed: false };
const MAX_RECENT = 60;

/** A file name without folders or reserved characters, at most 120 characters, without ".pdf". */
export function cleanName(name: string): string {
  const base = name
    .trim()
    .replace(/\.pdf$/i, '')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^[. ]+|[. ]+$/g, '')
    .slice(0, 120)
    .trim();
  return base || 'Document';
}

/** Refuses files that do not start like a PDF. */
function checkPdf(file: string): void {
  const head = Buffer.alloc(1024);
  const fd = openSync(file, 'r');
  try {
    const read = readSync(fd, head, 0, head.length, 0);
    if (!head.subarray(0, read).toString('latin1').includes('%PDF-')) throw new Error(`${path.basename(file)} is not a PDF.`);
  } finally {
    closeSync(fd);
  }
}

/** Are two paths the same file? Compared without case on macOS and Windows, whose disks usually ignore it. */
function sameFile(a: string, b: string): boolean {
  const [x, y] = [path.resolve(a), path.resolve(b)];
  if (x === y) return true;
  if (process.platform !== 'linux' && x.toLowerCase() === y.toLowerCase()) return true;
  try {
    const [sa, sb] = [statSync(x), statSync(y)];
    return sa.dev === sb.dev && sa.ino === sb.ino; // the same file under another name (a link)
  } catch {
    return false;
  }
}

export class Store {
  /** Where the welcome guide lives, and where documents of versions before 1.1 were kept. */
  readonly ownFolder: string;
  private readonly signatures: string;
  private readonly settingsFile: string;
  private readonly recentFile: string;

  constructor(root: string) {
    this.ownFolder = path.join(root, 'Documents');
    this.signatures = path.join(root, 'Signatures');
    this.settingsFile = path.join(root, 'settings.json');
    this.recentFile = path.join(root, 'recent.json');
    mkdirSync(this.ownFolder, { recursive: true });
    mkdirSync(this.signatures, { recursive: true });
    this.adoptOldLibrary();
  }

  // Recent files. This list is also what the window is allowed to touch: it can read and save only files the
  // user opened (or created) through a system dialog, a drop, or "Open with".

  private get saved(): { path: string; opened: number }[] {
    try {
      const list = JSON.parse(readFileSync(this.recentFile, 'utf8'));
      return Array.isArray(list) ? list.filter((e) => e && typeof e.path === 'string' && typeof e.opened === 'number') : [];
    } catch {
      return [];
    }
  }

  private write(list: { path: string; opened: number }[]): void {
    writeFileSync(this.recentFile, JSON.stringify(list.slice(0, MAX_RECENT), null, 2));
  }

  /** Versions before 1.1 copied every document into the app's folder. Those copies are listed as recent files. */
  private adoptOldLibrary(): void {
    if (existsSync(this.recentFile)) return;
    const old = readdirSync(this.ownFolder, { withFileTypes: true })
      .filter((entry) => entry.isFile() && /\.pdf$/i.test(entry.name) && !entry.name.startsWith('.'))
      .map((entry) => {
        const file = path.join(this.ownFolder, entry.name);
        return { path: file, opened: statSync(file).mtimeMs };
      })
      .sort((a, b) => b.opened - a.opened);
    this.write(old);
  }

  /** Recently opened files that still exist, newest first. */
  recent(): RecentFile[] {
    const files: RecentFile[] = [];
    for (const entry of this.saved) {
      try {
        const stat = statSync(entry.path);
        if (!stat.isFile()) continue;
        files.push({ path: entry.path, name: path.basename(entry.path), folder: path.dirname(entry.path), size: stat.size, modified: stat.mtimeMs, opened: entry.opened });
      } catch {
        // moved or deleted since: simply not shown
      }
    }
    return files;
  }

  /** May the window read and save this file? Only files on the recent list. */
  knows(file: string): boolean {
    const wanted = path.resolve(file);
    return this.saved.some((e) => e.path === wanted);
  }

  /** Puts a PDF at the top of the recent list. Refuses files that are not PDFs. */
  remember(file: string): string {
    const resolved = path.resolve(file);
    checkPdf(resolved);
    this.write([{ path: resolved, opened: Date.now() }, ...this.saved.filter((e) => e.path !== resolved)]);
    return resolved;
  }

  /** Takes a file off the list. The file itself is not touched. */
  forget(file: string): void {
    const resolved = path.resolve(file);
    this.write(this.saved.filter((e) => e.path !== resolved));
  }

  clearRecent(): void {
    this.write([]);
  }

  /**
   * Reads a PDF the user has just picked in an open dialog, for a tool that only reads it (Extract Pages). It is not
   * listed as recent, so this gives the window no lasting access to the file: it can never read or save it again.
   */
  readOnce(file: string): Uint8Array {
    if (typeof file !== 'string' || !path.isAbsolute(file)) throw new Error('That file could not be found.');
    checkPdf(file);
    return readFileSync(file);
  }

  read(file: string): Uint8Array {
    if (!this.knows(file)) throw new Error('That file is not open in PDFoundry.');
    return readFileSync(path.resolve(file));
  }

  /** Saves over a file the user opened. Written beside it first, so a crash never leaves half a PDF. */
  save(file: string, bytes: Uint8Array): void {
    if (!this.knows(file)) throw new Error('That file is not open in PDFoundry.');
    this.writeFile(path.resolve(file), bytes);
  }

  /**
   * Writes a new file at a place the user just chose in a save dialog, and lists it as recent.
   * `keep`: files that must not be replaced: the ones the new file is made from (extract, merge) and the document
   * open in the reader. When ".pdf" had to be added to the name, the system dialog never asked about replacing a
   * file of that name (the Linux dialog does not add it), so an existing file is refused rather than overwritten.
   */
  saveNew(file: string, bytes: Uint8Array, keep: string[] = []): string {
    const typed = /\.pdf$/i.test(file);
    const target = typed ? path.resolve(file) : `${path.resolve(file)}.pdf`;
    if (keep.some((k) => typeof k === 'string' && k !== '' && sameFile(target, k))) {
      throw new Error('Choose another name: that would replace a file it is made from, or the open document.');
    }
    if (!typed && existsSync(target)) throw new Error(`“${path.basename(target)}” already exists. Choose another name.`);
    this.writeFile(target, bytes);
    return this.remember(target);
  }

  private writeFile(target: string, bytes: Uint8Array): void {
    const partial = path.join(path.dirname(target), `.${path.basename(target)}.${randomUUID()}.part`);
    try {
      writeFileSync(partial, bytes);
      renameSync(partial, target);
    } catch (e) {
      rmSync(partial, { force: true });
      const code = (e as NodeJS.ErrnoException).code;
      if (code === 'EACCES' || code === 'EPERM' || code === 'EROFS') throw new Error('This file cannot be changed here. Use Save As to save it somewhere else.');
      if (code === 'EBUSY') throw new Error('This file is open in another app. Close it there, or use Save As.');
      throw e;
    }
  }

  // Signatures: PNG files named by a random id.

  listSignatures(): { id: string; png: Uint8Array }[] {
    return readdirSync(this.signatures)
      .filter((f) => /^[0-9a-f-]{36}\.png$/.test(f))
      .map((f) => ({ id: f.slice(0, -4), file: path.join(this.signatures, f) }))
      .sort((a, b) => statSync(b.file).mtimeMs - statSync(a.file).mtimeMs)
      .map(({ id, file }) => ({ id, png: readFileSync(file) }));
  }

  addSignature(png: Uint8Array): string {
    if (png.length > 4_000_000 || Buffer.from(png.subarray(0, 8)).toString('hex') !== '89504e470d0a1a0a') throw new Error('That signature could not be saved.');
    const id = randomUUID();
    writeFileSync(path.join(this.signatures, `${id}.png`), png, { mode: 0o600 });
    return id;
  }

  removeSignature(id: string): void {
    if (!/^[0-9a-f-]{36}$/.test(id)) throw new Error('That signature does not exist.');
    rmSync(path.join(this.signatures, `${id}.png`), { force: true });
  }

  // Settings

  get settings(): Settings {
    try {
      const saved = JSON.parse(readFileSync(this.settingsFile, 'utf8')) as Partial<Settings>;
      return {
        appearance: saved.appearance === 'light' || saved.appearance === 'dark' ? saved.appearance : 'system',
        welcomed: saved.welcomed === true,
      };
    } catch {
      return { ...DEFAULTS };
    }
  }

  update(change: Partial<Settings>): Settings {
    const next = { ...this.settings, ...change };
    writeFileSync(this.settingsFile, JSON.stringify(next, null, 2));
    return next;
  }
}

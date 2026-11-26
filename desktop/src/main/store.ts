// What PDFoundry keeps on this computer: the document library, saved signatures and settings.
// Everything lives in the app's own data folder, so no system permission is needed to read or write it.
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync, copyFileSync } from 'node:fs';
import path from 'node:path';

export type DocumentInfo = { name: string; size: number; modified: number };
export type Settings = { appearance: 'system' | 'light' | 'dark'; welcomed: boolean };

const DEFAULTS: Settings = { appearance: 'system', welcomed: false };

/** A file name the library can hold: no folders, no reserved characters, at most 120 characters, without ".pdf". */
export function cleanName(name: string): string {
  const base = name
    .replace(/\.pdf$/i, '')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^[. ]+|[. ]+$/g, '')
    .slice(0, 120)
    .trim();
  return base || 'Document';
}

export class Store {
  readonly documents: string;
  private readonly signatures: string;
  private readonly settingsFile: string;

  constructor(root: string) {
    this.documents = path.join(root, 'Documents');
    this.signatures = path.join(root, 'Signatures');
    this.settingsFile = path.join(root, 'settings.json');
    mkdirSync(this.documents, { recursive: true });
    mkdirSync(this.signatures, { recursive: true });
  }

  // Documents

  /** The file for a document name, refusing anything that is not a plain "<name>.pdf" inside the library. */
  private file(name: string): string {
    if (typeof name !== 'string' || !/\.pdf$/i.test(name) || name !== path.basename(name) || name.includes('\u0000')) {
      throw new Error('That is not a document in the library.');
    }
    return path.join(this.documents, name);
  }

  /** A name that is not taken yet: "Report.pdf", then "Report 2.pdf", "Report 3.pdf"… */
  uniqueName(wanted: string): string {
    const base = cleanName(wanted);
    for (let n = 1; ; n++) {
      const name = `${n === 1 ? base : `${base} ${n}`}.pdf`;
      if (!existsSync(path.join(this.documents, name))) return name;
    }
  }

  list(): DocumentInfo[] {
    return readdirSync(this.documents, { withFileTypes: true })
      .filter((entry) => entry.isFile() && /\.pdf$/i.test(entry.name) && !entry.name.startsWith('.'))
      .map((entry) => {
        const stat = statSync(path.join(this.documents, entry.name));
        return { name: entry.name, size: stat.size, modified: stat.mtimeMs };
      });
  }

  read(name: string): Uint8Array {
    return readFileSync(this.file(name));
  }

  /** Replaces a document's contents. Written beside the file first, so a crash never leaves half a PDF. */
  write(name: string, bytes: Uint8Array): void {
    const file = this.file(name);
    const partial = `${file}.${randomUUID()}.part`;
    writeFileSync(partial, bytes);
    renameSync(partial, file);
  }

  create(wanted: string, bytes: Uint8Array): string {
    const name = this.uniqueName(wanted);
    this.write(name, bytes);
    return name;
  }

  /** Copies a PDF from anywhere on the computer into the library. */
  importFile(source: string): string {
    const head = readFileSync(source).subarray(0, 1024).toString('latin1');
    if (!head.includes('%PDF-')) throw new Error(`${path.basename(source)} is not a PDF.`);
    const name = this.uniqueName(path.basename(source));
    copyFileSync(source, path.join(this.documents, name));
    return name;
  }

  rename(name: string, wanted: string): string {
    const from = this.file(name);
    if (`${cleanName(wanted)}.pdf` === name) return name;
    const to = this.uniqueName(wanted);
    renameSync(from, path.join(this.documents, to));
    return to;
  }

  duplicate(name: string): string {
    const copy = this.uniqueName(`${name.replace(/\.pdf$/i, '')} copy`);
    copyFileSync(this.file(name), path.join(this.documents, copy));
    return copy;
  }

  path(name: string): string {
    return this.file(name);
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

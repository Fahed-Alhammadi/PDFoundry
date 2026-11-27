// Is a password a PDF's owner password? pdf-lib opens a protected file with either password and does not say which
// one matched, but only the owner password gives full access (the right to change the file). This checks the
// password against the file's standard security handler itself (ISO 32000-2, 7.6.4: algorithms 7 and 6 for revisions
// 2 to 4, 2.A and 2.B for revisions 5 and 6). Pure TypeScript plus Web Crypto (SHA-2 and AES), so it runs in the
// window and in the tests. Nothing here keeps or writes the password.
import { PDFArray, PDFBool, PDFDict, PDFHexString, PDFName, PDFNumber, PDFString } from '@cantoo/pdf-lib';
import type { PDFDocument } from '@cantoo/pdf-lib';

/** The parts of a file's /Encrypt dictionary and /ID that the password checks need. */
export type Encryption = {
  v: number;
  r: number;
  /** Key length in bits. */
  length: number;
  o: Uint8Array;
  u: Uint8Array;
  p: number;
  encryptMetadata: boolean;
  id: Uint8Array;
};

const PADDING = new Uint8Array([
  0x28, 0xbf, 0x4e, 0x5e, 0x4e, 0x75, 0x8a, 0x41, 0x64, 0x00, 0x4e, 0x56, 0xff, 0xfa, 0x01, 0x08,
  0x2e, 0x2e, 0x00, 0xb6, 0xd0, 0x68, 0x3e, 0x80, 0x2f, 0x0c, 0xa9, 0xfe, 0x64, 0x53, 0x69, 0x7a,
]);

function bytesOf(value: unknown): Uint8Array | null {
  return value instanceof PDFString || value instanceof PDFHexString ? value.asBytes() : null;
}

/** Reads the encryption settings of a document loaded with `ignoreEncryption`. Null when it is not encrypted. */
export function encryptionOf(doc: PDFDocument): Encryption | null {
  const context = doc.context;
  const dict = context.lookup(context.trailerInfo.Encrypt);
  if (!(dict instanceof PDFDict)) return null;
  const number = (name: string) => {
    const n = dict.lookup(PDFName.of(name));
    return n instanceof PDFNumber ? n.asNumber() : undefined;
  };
  const v = number('V') ?? 0;
  let length = number('Length');
  if (!length) {
    length = v <= 3 ? 40 : 128;
    const filters = dict.lookup(PDFName.of('CF'));
    const stream = dict.lookup(PDFName.of('StmF'));
    if (filters instanceof PDFDict && stream instanceof PDFName) {
      const handler = filters.lookup(stream);
      const bits = handler instanceof PDFDict ? handler.lookup(PDFName.of('Length')) : undefined;
      if (bits instanceof PDFNumber) length = bits.asNumber() < 40 ? bits.asNumber() * 8 : bits.asNumber();
    }
  }
  const ids = context.lookup(context.trailerInfo.ID);
  const id = ids instanceof PDFArray ? bytesOf(ids.lookup(0)) : null;
  const meta = dict.lookup(PDFName.of('EncryptMetadata'));
  return {
    v,
    r: number('R') ?? 0,
    length,
    o: bytesOf(dict.lookup(PDFName.of('O'))) ?? new Uint8Array(),
    u: bytesOf(dict.lookup(PDFName.of('U'))) ?? new Uint8Array(),
    p: number('P') ?? 0,
    encryptMetadata: !(meta instanceof PDFBool) || meta.asBoolean(),
    id: id ?? new Uint8Array(),
  };
}

/** Does `password` match the owner password of a file encrypted with the standard security handler? */
export async function isOwnerPassword(e: Encryption, password: string): Promise<boolean> {
  if (e.r >= 5) {
    // UTF-8 for revision 6 (as pdf-lib encodes it); revision 5 (an Adobe extension) is read as pdf-lib reads it.
    const pw = (e.r === 6 ? new TextEncoder().encode(password) : latin1(password)).subarray(0, 127);
    const userBytes = e.u.subarray(0, 48);
    const input = join(pw, e.o.subarray(32, 40), userBytes);
    const hash = e.r === 6 ? await hash2B(pw, input, userBytes) : await sha('SHA-256', input);
    return equal(hash.subarray(0, 32), e.o.subarray(0, 32));
  }
  if (e.r < 2) return false;
  // Algorithm 7: the owner password decrypts /O into the padded user password, which must then pass algorithm 6.
  const n = e.r === 2 ? 5 : e.length >> 3;
  let hash = md5(pad(latin1(password)));
  if (e.r >= 3) for (let i = 0; i < 50; i++) hash = md5(hash);
  const key = hash.subarray(0, n);
  let user = e.o.subarray(0, 32);
  if (e.r === 2) user = rc4(key, user);
  else for (let i = 19; i >= 0; i--) user = rc4(key.map((b) => b ^ i), user);
  return isUserKeyValid(e, user);
}

/** Does `password` match the open (user) password? Used to tell whether saving with it changes the open password. */
export async function isUserPassword(e: Encryption, password: string): Promise<boolean> {
  if (e.r >= 5) {
    const pw = (e.r === 6 ? new TextEncoder().encode(password) : latin1(password)).subarray(0, 127);
    const input = join(pw, e.u.subarray(32, 40));
    const hash = e.r === 6 ? await hash2B(pw, input, new Uint8Array()) : await sha('SHA-256', input);
    return equal(hash.subarray(0, 32), e.u.subarray(0, 32));
  }
  return e.r >= 2 && isUserKeyValid(e, pad(latin1(password)));
}

/** Algorithm 6 for revisions 2 to 4, given the user password already padded to 32 bytes. */
function isUserKeyValid(e: Encryption, padded: Uint8Array): boolean {
  const n = e.r === 2 ? 5 : e.length >> 3;
  const p = e.p >>> 0;
  const extra = e.r >= 4 && !e.encryptMetadata ? new Uint8Array([0xff, 0xff, 0xff, 0xff]) : new Uint8Array();
  let hash = md5(join(padded, e.o.subarray(0, 32), new Uint8Array([p & 0xff, (p >>> 8) & 0xff, (p >>> 16) & 0xff, p >>> 24]), e.id, extra));
  if (e.r >= 3) for (let i = 0; i < 50; i++) hash = md5(hash.subarray(0, n));
  const key = hash.subarray(0, n);
  if (e.r === 2) return equal(rc4(key, PADDING), e.u.subarray(0, 32));
  let check = rc4(key, md5(join(PADDING, e.id)));
  for (let i = 1; i <= 19; i++) check = rc4(key.map((b) => b ^ i), check);
  return equal(check, e.u.subarray(0, 16));
}

// Revision 6: algorithm 2.B (SHA-256/384/512 and AES-128-CBC rounds).

async function hash2B(password: Uint8Array, input: Uint8Array, userBytes: Uint8Array): Promise<Uint8Array> {
  let k = await sha('SHA-256', input);
  let e: Uint8Array = new Uint8Array([0]);
  for (let round = 0; round < 64 || e[e.length - 1] > round - 32; round++) {
    const one = join(password, k, userBytes);
    const k1 = new Uint8Array(one.length * 64);
    for (let j = 0; j < 64; j++) k1.set(one, j * one.length);
    e = await aes128CbcNoPadding(k.subarray(0, 16), k.subarray(16, 32), k1);
    const choice = e.subarray(0, 16).reduce((a, b) => a + b, 0) % 3; // the first 16 bytes as a number, mod 3
    k = await sha(choice === 0 ? 'SHA-256' : choice === 1 ? 'SHA-384' : 'SHA-512', e);
  }
  return k.subarray(0, 32);
}

const subtle = () => globalThis.crypto.subtle;

async function sha(algorithm: 'SHA-256' | 'SHA-384' | 'SHA-512', data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().digest(algorithm, data as BufferSource));
}

/** AES-128-CBC of whole blocks without padding: Web Crypto always adds a padding block, which is cut off again. */
async function aes128CbcNoPadding(key: Uint8Array, iv: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
  const k = await subtle().importKey('raw', key as BufferSource, { name: 'AES-CBC' }, false, ['encrypt']);
  return new Uint8Array(await subtle().encrypt({ name: 'AES-CBC', iv: iv as BufferSource }, k, data as BufferSource)).subarray(0, data.length);
}

// Revisions 2 to 4: MD5 and RC4, which Web Crypto does not offer.

function rc4(key: Uint8Array, data: Uint8Array): Uint8Array {
  const s = new Uint8Array(256).map((_, i) => i);
  for (let i = 0, j = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) & 0xff;
    [s[i], s[j]] = [s[j], s[i]];
  }
  const out = new Uint8Array(data.length);
  for (let n = 0, i = 0, j = 0; n < data.length; n++) {
    i = (i + 1) & 0xff;
    j = (j + s[i]) & 0xff;
    [s[i], s[j]] = [s[j], s[i]];
    out[n] = data[n] ^ s[(s[i] + s[j]) & 0xff];
  }
  return out;
}

const SHIFTS = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const SINES = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);

export function md5(data: Uint8Array): Uint8Array {
  const total = ((data.length + 8) >>> 6 << 6) + 64;
  const buffer = new Uint8Array(total);
  buffer.set(data);
  buffer[data.length] = 0x80;
  const view = new DataView(buffer.buffer);
  view.setUint32(total - 8, (data.length * 8) >>> 0, true);
  view.setUint32(total - 4, Math.floor((data.length * 8) / 2 ** 32), true);
  let [a0, b0, c0, d0] = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476];
  for (let chunk = 0; chunk < total; chunk += 64) {
    let [a, b, c, d] = [a0, b0, c0, d0];
    for (let i = 0; i < 64; i++) {
      let f: number;
      let g: number;
      if (i < 16) [f, g] = [(b & c) | (~b & d), i];
      else if (i < 32) [f, g] = [(d & b) | (~d & c), (5 * i + 1) % 16];
      else if (i < 48) [f, g] = [b ^ c ^ d, (3 * i + 5) % 16];
      else [f, g] = [c ^ (b | ~d), (7 * i) % 16];
      f = (f + a + SINES[i] + view.getUint32(chunk + g * 4, true)) >>> 0;
      const shift = SHIFTS[(i >> 4) * 4 + (i % 4)];
      [a, d, c] = [d, c, b];
      b = (b + ((f << shift) | (f >>> (32 - shift)))) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }
  const out = new DataView(new ArrayBuffer(16));
  [a0, b0, c0, d0].forEach((word, i) => out.setUint32(i * 4, word, true));
  return new Uint8Array(out.buffer);
}

// Helpers

function latin1(text: string): Uint8Array {
  return Uint8Array.from({ length: text.length }, (_, i) => text.charCodeAt(i) & 0xff);
}

function pad(password: Uint8Array): Uint8Array {
  const out = new Uint8Array(32);
  const head = password.subarray(0, 32);
  out.set(head);
  out.set(PADDING.subarray(0, 32 - head.length), head.length);
  return out;
}

function join(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

function equal(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length || !a.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

// Passwords in the window: the Password-protect sheet, asking for a protected file's password, and the messages
// around them. A password lives only in memory (the open document, or the tool that is running); it is never sent to
// the main process, stored or logged.
import { title } from './api.ts';
import { button, dialog, h, message as errorText, prompt, toast } from './dom.ts';
import * as pdf from '../core/pdf.ts';

/** Shown under the password fields, word for word as on the phones. */
export const FORGET_WARNING = "If you forget the password, the file can't be opened, and it can't be recovered.";

/** Password and Confirm. Protect is enabled once both are filled in and the same. Resolves the password, or null. */
export function protectSheet(name: string): Promise<string | null> {
  return new Promise((resolve) => {
    let answer: string | null = null;
    const field = (label: string) => h('input', { type: 'password', placeholder: label, 'aria-label': label, autocomplete: 'new-password' });
    const password = field('Password');
    const again = field('Confirm');
    const mismatch = h('p.field-error', { role: 'status' });
    const protect = button('Protect', () => {
      if (!ready()) return;
      answer = password.value;
      d.close();
    }, 'primary');
    const ready = () => password.value !== '' && password.value === again.value;
    const update = () => {
      protect.disabled = !ready();
      mismatch.textContent = again.value && password.value !== again.value ? 'The passwords are not the same.' : '';
    };
    for (const input of [password, again]) {
      input.addEventListener('input', update);
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') protect.click();
      });
    }
    const d = dialog('Protect with Password', [
      h('p', `“${title(name)}” will need this password to open, in PDFoundry and in every other app.`),
      h('div.password-fields', password, again),
      mismatch,
      h('p.hint.warning', FORGET_WARNING),
    ], [button('Cancel', () => d.close()), protect], { onClose: () => resolve(answer) });
    d.el.classList.add('protect');
    update();
  });
}

/** Asks for a protected file's password until it works. Null when the user gives up. */
export async function askPassword(name: string, bytes: Uint8Array): Promise<{ bytes: Uint8Array; password: string; full: boolean } | null> {
  for (let wrong = false; ; wrong = true) {
    const password = await prompt(wrong ? 'That password didn’t work' : `“${title(name)}” is protected`, 'Password', '', 'Unlock', 'password');
    if (password === null) return null;
    const unlocked = await pdf.unlock(bytes, password);
    if (unlocked) return { ...unlocked, password };
  }
}

/**
 * For tools that make new files from a source (Extract Pages, Split PDF): the source as plain bytes, and the password
 * the new files must be protected with (null when the source is not protected). A protected source asks for its
 * password and needs full access. `source.password`: the open document's password, when the source is that document
 * (its bytes are then either decrypted unsaved changes, or the file on disk). Null when cancelled or not allowed.
 */
export async function unlockSource(source: { name: string; bytes: Uint8Array; password?: string | null }, verb: string): Promise<{ bytes: Uint8Array; password: string | null } | null> {
  try {
    if (!(await pdf.isProtected(source.bytes))) return { bytes: source.bytes, password: source.password ?? null };
    const known = source.password ? await pdf.unlock(source.bytes, source.password) : null; // the open document's own
    const unlocked = known ? { ...known, password: source.password! } : await askPassword(source.name, source.bytes);
    if (!unlocked) return null;
    if (!unlocked.full) {
      toast(`That password opens “${title(source.name)}” but doesn’t allow changes, so its pages can’t be ${verb}.`, true);
      return null;
    }
    return { bytes: unlocked.bytes, password: unlocked.password };
  } catch (e) {
    toast(errorText(e), true);
    return null;
  }
}

/** The Tools tile on a file that already has a password: nothing to do here. */
export function alreadyProtected(name: string): void {
  const d = dialog('Already protected', [
    h('p', `“${title(name)}” is already protected with a password. To take it off, open it and choose More › Remove Password.`),
  ], [button('OK', () => d.close(), 'primary')]);
}

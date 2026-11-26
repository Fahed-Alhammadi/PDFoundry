// Signatures: the saved ones, and the pad for drawing a new one.
import { api } from './api.ts';
import { button, confirm, dialog, h, iconButton, message, toast } from './dom.ts';
import { placedFromPng, pngUrl, toPng, trim } from './images.ts';
import type { Placed } from './images.ts';

const INKS = [
  { name: 'Black', color: '#111318' },
  { name: 'Blue', color: '#1a3fb0' },
];

/**
 * Shows the saved signatures and the pad. With `onUse`, picking or drawing one hands it over for placing;
 * without it (from Settings) the dialog only manages what is saved.
 */
export async function signatureDialog(onUse?: (signature: Placed) => void): Promise<void> {
  const list = h('div.signature-list');
  const pad = h('canvas.signature-pad', { width: 1200, height: 420, 'aria-label': 'Signature pad. Draw your signature here.', tabIndex: 0 });
  const ctx = pad.getContext('2d')!;
  let ink = INKS[0].color;
  let drawn = false;
  const save = h('input', { type: 'checkbox', checked: true, id: 'save-signature' });

  const refresh = async () => {
    const saved = await api.signatures();
    list.replaceChildren();
    if (!saved.length) return void list.append(h('p.hint', onUse ? 'No saved signatures yet. Draw one below.' : 'No saved signatures. Draw one below to save it.'));
    for (const signature of saved) {
      const use = async () => {
        if (!onUse) return;
        d.close();
        onUse(await placedFromPng(signature.png));
      };
      list.append(
        h('div.signature-item',
          h('button.signature-use', { type: 'button', disabled: !onUse, 'aria-label': 'Use this signature', onclick: use }, h('img', { src: pngUrl(signature.png), alt: 'Saved signature' })),
          iconButton('trash', 'Delete signature', async () => {
            if (!(await confirm('Delete this signature?', 'Documents you already signed keep it.', 'Delete'))) return;
            await api.removeSignature(signature.id);
            await refresh();
          }),
        ),
      );
    }
  };

  // Drawing
  let last: { x: number; y: number } | null = null;
  const at = (e: PointerEvent) => {
    const box = pad.getBoundingClientRect();
    return { x: ((e.clientX - box.left) / box.width) * pad.width, y: ((e.clientY - box.top) / box.height) * pad.height };
  };
  pad.addEventListener('pointerdown', (e) => {
    try { pad.setPointerCapture(e.pointerId); } catch { /* not a live pointer */ }
    last = at(e);
    ctx.fillStyle = ink;
    ctx.beginPath();
    ctx.arc(last.x, last.y, 3, 0, Math.PI * 2);
    ctx.fill();
    drawn = true;
    useButton.disabled = false;
  });
  pad.addEventListener('pointermove', (e) => {
    if (!last) return;
    const p = at(e);
    ctx.strokeStyle = ink;
    ctx.lineWidth = 6;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(last.x, last.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
    last = p;
  });
  const end = () => (last = null);
  pad.addEventListener('pointerup', end);
  pad.addEventListener('pointercancel', end);

  const clear = () => {
    ctx.clearRect(0, 0, pad.width, pad.height);
    drawn = false;
    useButton.disabled = true;
  };
  const inkButtons = INKS.map((option, i) =>
    h('button.swatch', {
      type: 'button', title: option.name, 'aria-label': `${option.name} ink`, 'aria-pressed': String(i === 0),
      onclick: (e: MouseEvent) => {
        // Ink already on the pad changes colour too, so the choice can be made after drawing.
        ink = option.color;
        ctx.globalCompositeOperation = 'source-in';
        ctx.fillStyle = ink;
        ctx.fillRect(0, 0, pad.width, pad.height);
        ctx.globalCompositeOperation = 'source-over';
        for (const b of inkButtons) b.setAttribute('aria-pressed', String(b === e.currentTarget));
      },
    }, h('span', { class: `ink-${option.name.toLowerCase()}` })),
  );

  const useButton = button(onUse ? 'Use signature' : 'Save signature', async () => {
    if (!drawn) return;
    try {
      const trimmed = trim(pad);
      if (!trimmed) return;
      const png = await toPng(trimmed);
      if (!onUse || save.checked) await api.addSignature(png);
      if (onUse) {
        d.close();
        onUse(await placedFromPng(png));
      } else {
        clear();
        await refresh();
        toast('Signature saved.');
      }
    } catch (e) {
      toast(message(e), true);
    }
  }, 'primary');
  useButton.disabled = true;

  const d = dialog(onUse ? 'Sign' : 'Signatures', [
    list,
    h('div.pad-wrap', pad, h('span.pad-line')),
    h('div.pad-tools', h('span.hint', 'Ink'), ...inkButtons, h('span.spacer'), button('Clear', clear),
      onUse ? h('label.check', { for: 'save-signature' }, save, 'Save for next time') : null),
  ], [button(onUse ? 'Cancel' : 'Done', () => d.close()), useButton], { wide: true });
  await refresh();
}

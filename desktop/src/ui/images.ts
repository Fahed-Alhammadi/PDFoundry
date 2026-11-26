// Turning what the user draws or types into PNG images that the PDF engine stamps onto a page.

export type Stroke = { points: { x: number; y: number }[]; color: string; width: number };
export type Placed = { png: Uint8Array; url: string; aspect: number };

export async function toPng(canvas: HTMLCanvasElement): Promise<Uint8Array> {
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
  if (!blob) throw new Error('The image could not be made.');
  return new Uint8Array(await blob.arrayBuffer());
}

export function pngUrl(png: Uint8Array): string {
  return URL.createObjectURL(new Blob([png as BlobPart], { type: 'image/png' }));
}

/** The canvas cropped to its ink (pixels that are not transparent), with a small margin. Null when it is empty. */
export function trim(canvas: HTMLCanvasElement, margin = 6): HTMLCanvasElement | null {
  const ctx = canvas.getContext('2d')!;
  const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height);
  let left = width, top = height, right = -1, bottom = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] > 8) {
        if (x < left) left = x;
        if (x > right) right = x;
        if (y < top) top = y;
        if (y > bottom) bottom = y;
      }
    }
  }
  if (right < 0) return null;
  left = Math.max(0, left - margin);
  top = Math.max(0, top - margin);
  right = Math.min(width - 1, right + margin);
  bottom = Math.min(height - 1, bottom + margin);
  const out = document.createElement('canvas');
  out.width = right - left + 1;
  out.height = bottom - top + 1;
  out.getContext('2d')!.drawImage(canvas, left, top, out.width, out.height, 0, 0, out.width, out.height);
  return out;
}

function drawStroke(ctx: CanvasRenderingContext2D, stroke: Stroke, toX: (x: number) => number, toY: (y: number) => number, width: number): void {
  ctx.strokeStyle = stroke.color;
  ctx.fillStyle = stroke.color;
  ctx.lineWidth = width;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  const [first, ...rest] = stroke.points;
  if (!rest.length) {
    ctx.beginPath();
    ctx.arc(toX(first.x), toY(first.y), width / 2, 0, Math.PI * 2);
    ctx.fill();
    return;
  }
  ctx.beginPath();
  ctx.moveTo(toX(first.x), toY(first.y));
  for (const p of rest) ctx.lineTo(toX(p.x), toY(p.y));
  ctx.stroke();
}

/** Draws strokes (points and widths are fractions of the page's width and height) on a canvas covering the page. */
export function paintStrokes(canvas: HTMLCanvasElement, strokes: Stroke[]): void {
  const ctx = canvas.getContext('2d')!;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  for (const s of strokes) drawStroke(ctx, s, (x) => x * canvas.width, (y) => y * canvas.height, s.width * canvas.width);
}

/**
 * The strokes of one page as a PNG covering only their bounding box, plus that box as fractions of the page.
 * `page` is the page's size in points; the image has three pixels per point.
 */
export async function rasterize(strokes: Stroke[], page: { width: number; height: number }): Promise<{ png: Uint8Array; rect: { x: number; y: number; width: number; height: number } } | null> {
  if (!strokes.length) return null;
  let left = 1, top = 1, right = 0, bottom = 0;
  for (const s of strokes) {
    const padX = s.width / 2;
    const padY = (s.width * page.width) / page.height / 2;
    for (const p of s.points) {
      left = Math.min(left, p.x - padX);
      right = Math.max(right, p.x + padX);
      top = Math.min(top, p.y - padY);
      bottom = Math.max(bottom, p.y + padY);
    }
  }
  left = Math.max(0, left); top = Math.max(0, top); right = Math.min(1, right); bottom = Math.min(1, bottom);
  if (right <= left || bottom <= top) return null;
  const scale = Math.min(3, 4000 / Math.max((right - left) * page.width, (bottom - top) * page.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round((right - left) * page.width * scale));
  canvas.height = Math.max(1, Math.round((bottom - top) * page.height * scale));
  const ctx = canvas.getContext('2d')!;
  for (const s of strokes) {
    drawStroke(ctx, s, (x) => (x - left) * page.width * scale, (y) => (y - top) * page.height * scale, s.width * page.width * scale);
  }
  return { png: await toPng(canvas), rect: { x: left, y: top, width: right - left, height: bottom - top } };
}

/** Typed text as an image. Drawn by the browser, so every script (Arabic included) is shaped correctly. */
export async function textImage(text: string, color: string, points: number): Promise<Placed & { widthPoints: number }> {
  const scale = 3;
  const font = `${points * scale}px -apple-system, "Segoe UI", "Helvetica Neue", Arial, "Noto Sans", sans-serif`;
  const lines = text.replace(/\r/g, '').split('\n');
  const measure = document.createElement('canvas').getContext('2d')!;
  measure.font = font;
  const lineHeight = points * scale * 1.3;
  const width = Math.ceil(Math.max(...lines.map((l) => measure.measureText(l || ' ').width))) + 8 * scale;
  const canvas = document.createElement('canvas');
  canvas.width = Math.min(width, 6000);
  canvas.height = Math.ceil(lineHeight * lines.length + 4 * scale);
  const ctx = canvas.getContext('2d')!;
  ctx.font = font;
  ctx.fillStyle = color;
  ctx.textBaseline = 'top';
  const rtl = /[֐-ࣿ]/.test(text);
  ctx.direction = rtl ? 'rtl' : 'ltr';
  ctx.textAlign = rtl ? 'right' : 'left';
  lines.forEach((line, i) => ctx.fillText(line, rtl ? canvas.width - 4 * scale : 4 * scale, 2 * scale + i * lineHeight + points * scale * 0.12));
  const png = await toPng(canvas);
  return { png, url: pngUrl(png), aspect: canvas.width / canvas.height, widthPoints: canvas.width / scale };
}

export async function placedFromPng(png: Uint8Array): Promise<Placed> {
  const url = pngUrl(png);
  const image = new Image();
  image.src = url;
  await image.decode();
  return { png, url, aspect: image.naturalWidth / image.naturalHeight };
}

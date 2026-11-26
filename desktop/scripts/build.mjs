// Bundles the main process, the preload and the window with esbuild into out/, and copies what pdf.js loads at run
// time (its worker, character maps, standard fonts and image decoders), so the window never needs the network.
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { build } from 'esbuild';

const production = process.env.NODE_ENV !== 'development';
rmSync('out', { recursive: true, force: true });
mkdirSync('out/ui', { recursive: true });

const node = {
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node22',
  sourcemap: !production,
  minify: production,
  logLevel: 'warning',
  external: ['electron', 'electron-updater'],
  // import('electron-updater') (loaded only when an update is found) becomes a plain require(): Node's ESM loader
  // would not see the CommonJS getter exports of electron-updater.
  supported: { 'dynamic-import': false },
};
await build({ ...node, entryPoints: ['src/main/main.ts'], outfile: 'out/main.cjs' });
await build({ ...node, entryPoints: ['src/preload/preload.ts'], outfile: 'out/preload.cjs' });
await build({
  bundle: true,
  platform: 'browser',
  format: 'esm',
  target: 'chrome140',
  sourcemap: !production,
  minify: production,
  logLevel: 'warning',
  entryPoints: ['src/ui/app.ts'],
  outfile: 'out/ui/app.js',
});
cpSync('src/ui/index.html', 'out/ui/index.html');
cpSync('src/ui/app.css', 'out/ui/app.css');
cpSync('assets/logo.png', 'out/ui/logo.png');
const pdfjs = 'node_modules/pdfjs-dist';
cpSync(`${pdfjs}/build/pdf.worker.min.mjs`, 'out/ui/pdf.worker.min.mjs');
for (const dir of ['cmaps', 'standard_fonts', 'wasm']) cpSync(`${pdfjs}/${dir}`, `out/ui/pdfjs/${dir}`, { recursive: true });
console.log('Built to out/');

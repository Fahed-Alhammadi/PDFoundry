// After `npm run dist:*`: Make Searchable must work in the packaged app. For every unpacked app in dist/ that this
// machine can run, runs OCR on the test pictures with the app's own binary, exactly as the main process loads it
// (tesseract.js from the archive, its worker and engine files unpacked, the data in resources/ocr), with the network
// blocked. Fails when an engine file or the data is missing from the package, or the text is not read.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';

const dist = path.resolve('dist');
const runnable = (arch) => arch === process.arch;
const apps = [];
for (const dir of existsSync(dist) ? readdirSync(dist) : []) {
  const root = path.join(dist, dir);
  if (process.platform === 'darwin' && /^mac(-arm64|-universal)?$/.test(dir)) {
    const arch = dir.endsWith('arm64') ? 'arm64' : 'x64';
    const app = path.join(root, 'PDFoundry.app', 'Contents');
    if (existsSync(app) && runnable(arch)) apps.push({ binary: path.join(app, 'MacOS', 'PDFoundry'), resources: path.join(app, 'Resources') });
  } else if (process.platform === 'win32' && /^win(-arm64)?-unpacked$/.test(dir)) {
    if (runnable(dir.includes('arm64') ? 'arm64' : 'x64')) apps.push({ binary: path.join(root, 'PDFoundry.exe'), resources: path.join(root, 'resources') });
  } else if (process.platform === 'linux' && /^linux(-arm64)?-unpacked$/.test(dir)) {
    const binary = ['pdfoundry-desktop', 'pdfoundry', 'PDFoundry'].map((n) => path.join(root, n)).find(existsSync);
    if (binary && runnable(dir.includes('arm64') ? 'arm64' : 'x64')) apps.push({ binary, resources: path.join(root, 'resources') });
  }
}
if (!apps.length) {
  console.error('No packaged app for this machine in dist/. Run npm run dist:mac, dist:win or dist:linux first.');
  process.exit(1);
}
let failed = false;
for (const app of apps) {
  for (const [picture, expected] of [['ocr-english.png', /Invoice/], ['ocr-arabic.png', /فاتورة/]]) {
    const run = spawnSync(app.binary, [path.resolve('scripts/packaged-ocr.cjs'), app.resources, path.resolve('test/fixtures', picture), path.resolve('test/fixtures/no-network.cjs')], {
      env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' }, encoding: 'utf8', timeout: 180_000,
    });
    const said = `${run.stdout ?? ''}${run.stderr ?? ''}`.trim();
    const ok = run.status === 0 && expected.test(said);
    failed ||= !ok;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${path.relative(dist, app.resources)}: ${picture}: ${ok ? said : `\n${said || run.error}`}`);
  }
}
process.exit(failed ? 1 : 0);

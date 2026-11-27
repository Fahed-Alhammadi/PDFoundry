// Runs the app's own checks in a real window and fails if any of them does:
//   npm run selftest       pixel checks of the PDF engine and a drive through the interface
//   npm run screenshots    fills a throwaway library with sample documents and saves each screen to ../screenshots/desktop
//                          (or to the folder in PDFOUNDRY_SHOTS_DIR)
// Both use a temporary data folder, so the installed app's documents are never touched. Needs `npm run build` first.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import path from 'node:path';

const shots = process.argv.includes('--screenshots');
// --reopen: only checks that the window can be closed and opened again (what the Dock icon does on macOS).
const reopen = process.argv.includes('--reopen');
const electron = createRequire(import.meta.url)('electron');
const work = mkdtempSync(path.join(tmpdir(), 'pdfoundry-selftest-'));
const result = path.join(work, 'result.json');
const env = { ...process.env, PDFOUNDRY_DATA: path.join(work, 'data'), ...(reopen ? { PDFOUNDRY_REOPEN: result } : { PDFOUNDRY_SELFTEST: result }) };
if (shots) env.PDFOUNDRY_SHOTS = path.resolve(process.env.PDFOUNDRY_SHOTS_DIR || '../screenshots/desktop');
// Linux CI runners cannot start Chromium's sandbox.
const args = ['.', ...(process.platform === 'linux' && process.env.CI ? ['--no-sandbox'] : [])];

const child = spawn(electron, args, { env, stdio: 'inherit' });
const timer = setTimeout(() => {
  console.error('The self-test did not finish within 3 minutes.');
  child.kill();
}, 180_000);
child.on('exit', () => {
  clearTimeout(timer);
  let failed = true;
  if (existsSync(result)) {
    const { checks, error } = JSON.parse(readFileSync(result, 'utf8'));
    for (const c of checks) console.log(`${c.pass ? 'ok  ' : 'FAIL'} ${c.name}${c.pass || !c.detail ? '' : `\n     ${c.detail}`}`);
    if (error) console.error(error);
    failed = !!error || checks.some((c) => !c.pass) || (!shots && !reopen && checks.length < 77);
    console.log(failed ? 'Self-test failed.' : `${checks.length} checks passed.`);
  } else console.error('The app closed without a result.');
  rmSync(work, { recursive: true, force: true });
  process.exit(failed ? 1 : 0);
});

// Run by check-package-ocr.mjs with a packaged app's own binary (ELECTRON_RUN_AS_NODE=1): loads tesseract.js from
// inside app.asar the way the main process does (src/main/ocr.ts), with the worker unpacked and the data in
// resources/ocr, the network blocked in every thread, and prints what it reads on a picture.
//   <binary> packaged-ocr.cjs <resources folder> <picture.png> <no-network.cjs>
const fs = require('fs');
const path = require('path');
const [resources, picture, blocker] = process.argv.slice(-3);
require(blocker);
const threads = require('worker_threads');
const Original = threads.Worker;
threads.Worker = class extends Original {
  constructor(file, options = {}) {
    super(file, { ...options, execArgv: [...(options.execArgv || []), '--require', blocker] });
  }
};
const { createWorker, OEM } = require(path.join(resources, 'app.asar', 'node_modules', 'tesseract.js'));
(async () => {
  const worker = await createWorker(['eng', 'ara'], OEM.LSTM_ONLY, {
    langPath: path.join(resources, 'ocr'),
    cacheMethod: 'none',
    gzip: true,
    workerPath: path.join(resources, 'app.asar.unpacked', 'node_modules', 'tesseract.js', 'src', 'worker-script', 'node', 'index.js'),
  });
  const { data } = await worker.recognize(fs.readFileSync(picture));
  console.log(`READ ${JSON.stringify(data.text.trim())}`);
  await worker.terminate();
})().catch((e) => {
  console.error('FAILED', e && e.message ? e.message : e);
  process.exit(1);
});

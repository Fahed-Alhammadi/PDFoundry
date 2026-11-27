// Loaded with --require into the OCR test's worker threads (and the test itself): any attempt to reach the network
// throws, so OCR can only pass with the files bundled on disk.
const refuse = (what) => () => {
  throw new Error(`OCR tried to use the network (${what})`);
};
globalThis.fetch = refuse('fetch');
for (const name of ['http', 'https']) {
  const mod = require(name);
  mod.request = refuse(`${name}.request`);
  mod.get = refuse(`${name}.get`);
}
const net = require('net');
net.connect = refuse('net.connect');
net.createConnection = refuse('net.createConnection');

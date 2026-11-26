// The window's only door to the main process: `pdfoundry.call(name, ...args)` (handlers in main.ts),
// `pdfoundry.on(fn)` for events pushed to the window, and the path of a file dropped onto it.
import { contextBridge, ipcRenderer, webUtils } from 'electron';

contextBridge.exposeInMainWorld('pdfoundry', {
  call: (name: string, ...args: unknown[]) => ipcRenderer.invoke('pdfoundry', name, ...args),
  on: (fn: (event: string, data: unknown) => void) => {
    ipcRenderer.on('pdfoundry:event', (_e, event: string, data: unknown) => fn(event, data));
  },
  /** Tells the main process the window can take events (files opened from the system are handed over then). */
  ready: () => ipcRenderer.send('pdfoundry:ready'),
  pathOf: (file: File) => webUtils.getPathForFile(file),
  platform: process.platform,
});

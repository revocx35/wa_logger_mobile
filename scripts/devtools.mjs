#!/usr/bin/env node
// Evaluates a JavaScript expression in the debug app's WhatsApp Web page through Chrome DevTools
// (debug builds enable WebView debugging). For development checks of page state only — never use it to
// read message content.
//   node scripts/devtools.mjs 'window.Debug.VERSION'
//   ANDROID_SERIAL=emulator-5554 node scripts/devtools.mjs 'document.title'
import { execFileSync } from 'node:child_process';

const ADB = `${process.env.ANDROID_HOME || '/opt/android-sdk'}/platform-tools/adb`;
const serial = process.env.ANDROID_SERIAL ? ['-s', process.env.ANDROID_SERIAL] : [];
const pkg = process.env.WALM_PKG || 'io.github.revocx35.walmobile.debug';
const port = Number(process.env.DEVTOOLS_PORT || 9333);
const expr = process.argv[2] || 'location.href';

const adb = (...a) => execFileSync(ADB, [...serial, ...a], { encoding: 'utf8' });
const pid = adb('shell', 'pidof', pkg).trim();
if (!pid) throw new Error(`${pkg} is not running`);
adb('forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`);

const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find((p) => p.url.startsWith('https://web.whatsapp.com'));
if (!page) throw new Error(`no WhatsApp page (pages: ${pages.map((p) => p.url).join(', ')})`);
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => {
  ws.onopen = r;
  ws.onerror = j;
});
const result = await new Promise((resolve) => {
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id === 1) resolve(d.result);
  };
  ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, awaitPromise: true, returnByValue: true } }));
});
ws.close();
console.log(JSON.stringify(result.result?.value ?? result.exceptionDetails?.exception?.description ?? result, null, 1));

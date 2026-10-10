// A headless browser guest on trunk's guest page, for GuestVideoE2ETest.
// Fake camera and fake screen come from the browser's own test devices.
// Reads one command per line on stdin (join NAME, camera, share, quit) and
// answers "ok <command>" or "err <command> <why>" on stdout.
//
//   node guest-driver.mjs <guest page url> [browser binary]
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';

const url = process.argv[2];
const binary = process.argv[3] || 'brave';
const port = 9300 + Math.floor(Math.random() * 600);
const browser = spawn(binary, [
  '--headless=new', `--remote-debugging-port=${port}`,
  `--user-data-dir=${mkdtempSync(join(tmpdir(), 'guest-'))}`,
  '--no-first-run', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  '--auto-select-desktop-capture-source=Entire screen', '--autoplay-policy=no-user-gesture-required',
  url,
], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let page;
for (let i = 0; i < 100 && !page; i++) {
  await sleep(200);
  try {
    const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    page = list.find((t) => t.type === 'page' && t.url.includes('/apps/trunk/guest/'));
  } catch (_) { /* not up yet */ }
}
if (!page) { console.log('err start no page'); browser.kill(); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const waiting = new Map();
ws.addEventListener('message', (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id); }
});
function cdp(method, params = {}) {
  const id = ++seq;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((r) => waiting.set(id, r));
}
// A click counts as the user's: getDisplayMedia wants a gesture.
async function run(expr) {
  const r = await cdp('Runtime.evaluate', { expression: expr, userGesture: true, awaitPromise: true, returnByValue: true });
  if (r.result && r.result.exceptionDetails) { const d = r.result.exceptionDetails; throw new Error((d.exception && d.exception.description) || d.text); }
  return r.result && r.result.result && r.result.result.value;
}
const $ = (id) => `document.getElementById('${id}')`;

const done = (c) => console.log(`ok ${c}`);
const fail = (c, e) => console.log(`err ${c} ${e.message || e}`);
for await (const line of createInterface({ input: process.stdin })) {
  const [cmd, ...rest] = line.trim().split(' ');
  try {
    if (cmd === 'join') {
      // The page fills in the form once it has asked the ship about the room.
      const ready = `(function () { var f = ${$('form')}; return !!f && !f.hidden; })()`;
      for (let i = 0; i < 75 && !(await run(ready)); i++) await sleep(200);
      await run(`${$('name')}.value = ${JSON.stringify(rest.join(' ') || 'Guest')}; ${$('form')}.requestSubmit(); true`);
      for (let i = 0; i < 100 && (await run(`${$('controls')}.hidden`)); i++) await sleep(200);
      if (await run(`${$('controls')}.hidden`)) throw new Error(await run(`${$('status')}.textContent`));
    } else if (cmd === 'camera' || cmd === 'share' || cmd === 'mute') {
      await run(`${$(cmd)}.click(); true`);
      await sleep(1500);
    } else if (cmd === 'quit') {
      done(cmd); break;
    } else throw new Error('unknown command');
    done(cmd);
  } catch (e) { fail(cmd, e); }
}
ws.close();
browser.kill();
process.exit(0);

/**
 * Screenshots of the main routes, for visual review.
 *
 * The packaged build has DevTools removed, so this drives whichever Electron
 * window exposes a CDP port (dev: --remoteDebuggingPort 9223; installed:
 * --remote-debugging-port=9222) and writes PNGs to dist/ui-shots.
 *
 *   AISTUDY_USER=... AISTUDY_PASS=... node scripts/ui-shots.mjs
 */
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { findPageTarget, openClient } from './cdp.mjs';

const USER = process.env.AISTUDY_USER ?? '';
const PASS = process.env.AISTUDY_PASS ?? '';
const OUT = process.env.AISTUDY_SHOT_DIR ?? join(process.cwd(), 'dist', 'ui-shots');
const target = await findPageTarget(process.env.AISTUDY_CDP ?? 'http://127.0.0.1:9223', { timeoutMs: 60000 });
const { send, evaluate, close } = await openClient(target.webSocketDebuggerUrl);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await mkdir(OUT, { recursive: true });
console.log('shots ->', OUT);

const shot = async (name) => {
  await send('Page.enable');
  const res = await send('Page.captureScreenshot', { format: 'png' });
  const data = res.result?.data;
  if (!data) {
    throw new Error(`no screenshot data: ${JSON.stringify(res).slice(0, 200)}`);
  }
  await writeFile(`${OUT}/${name}.png`, Buffer.from(data, 'base64'));
  console.log(`shot ${name}`);
};

const state = async () => evaluate('(() => location.hash)()');
const goto = async (path) => {
  await evaluate(`(() => { location.hash = ${JSON.stringify(path)}; return 1; })()`);
  await sleep(3500);
};

await sleep(3000);
if (String(await state()).includes('/login')) {
  await shot('01-login');
  await evaluate(`(() => {
    const set = (el, v) => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
    set(document.querySelector('input[name="username"]'), ${JSON.stringify(USER)});
    set(document.querySelector('input[type="password"]'), ${JSON.stringify(PASS)});
    [...document.querySelectorAll('button')].find((b) => /登录/.test(b.textContent)).click();
    return 1;
  })()`);
  await sleep(6000);
}

const spaceId = await evaluate(`(() => {
  const link = [...document.querySelectorAll('a')].find((a) => /^#\\/spaces\\/\\d+\\//.test(a.getAttribute('href') || ''));
  return link ? link.getAttribute('href').split('/')[2] : null;
})()`);
const base = `/spaces/${spaceId}`;
console.log('spaceId', spaceId);

await goto('/spaces');
await shot('02-spaces');
await goto(`${base}/questions`);
await shot('03-questions');
await goto(`${base}/exams`);
await shot('04-exams');
await goto(`${base}/notes`);
await shot('05-notes');
await goto(`${base}/sources`);
await shot('06-sources');
await goto(`${base}/search`);
await shot('07-search');
await goto(`${base}/knowledge`);
await shot('08-knowledge');
close();

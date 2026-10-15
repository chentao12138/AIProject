/**
 * R03–R05 driver for the INSTALLED build (packaged `AIStudy.exe`, DevTools disabled).
 *
 * The packaged app could never be observed before: it issues no requests while
 * anonymous and ADR-037 removes the console, so "the renderer paints" and "the
 * configured origin is actually used" were unprovable. Electron still accepts
 * `--remote-debugging-port`, so this drives the real window over CDP.
 *
 * Credentials come from the environment only. Run:
 *   powershell: start dist\win-unpacked\AIStudy.exe --remote-debugging-port=9222
 *   node scripts/r03-installed-app.mjs
 */

import { findPageTarget, openClient } from './cdp.mjs';

const USER = process.env.AISTUDY_USER ?? '';
const PASS = process.env.AISTUDY_PASS ?? '';
const DEBUG = process.env.AISTUDY_CDP ?? 'http://127.0.0.1:9222';

const rows = [];
function check(name, pass, detail) {
  rows.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${String(detail).slice(0, 220)}`);
}

const page = await findPageTarget(DEBUG);
const client = await openClient(page.webSocketDebuggerUrl);
const { send, evaluate } = client;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 1. The renderer actually paints, and the app is on the login route.
const first = await evaluate(`(() => ({
  hash: location.hash,
  inputs: [...document.querySelectorAll('input')].map((i) => i.type + ':' + (i.name || i.id || '')),
  buttons: [...document.querySelectorAll('button')].map((b) => b.textContent.trim()),
}))()`);
check(
  'installed renderer paints the login route',
  first.hash.includes('/login') && first.inputs.length > 0,
  `hash=${first.hash} inputs=${JSON.stringify(first.inputs)} buttons=${JSON.stringify(first.buttons)}`
);

// 2. The token must never reach web storage — it is main's vault, not the DOM's.
const storageBefore = await evaluate(`(() => ({
  local: Object.keys(localStorage), session: Object.keys(sessionStorage),
}))()`);
check(
  'no credentials in renderer web storage before login',
  storageBefore.local.length === 0 && storageBefore.session.length === 0,
  JSON.stringify(storageBefore)
);

// 3. Log in. React owns these inputs, so a value assignment has to go through
//    the native setter plus an input event or React never sees the edit.
const filled = await evaluate(`(async () => {
  const set = (el, value) => {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const user = document.querySelector('input[name="username"], input[type="text"]');
  const pass = document.querySelector('input[type="password"]');
  if (!user || !pass) return 'missing inputs';
  set(user, ${JSON.stringify(USER)});
  set(pass, ${JSON.stringify(PASS)});
  const btn = [...document.querySelectorAll('button')].find((b) => /登录|登 录|Login/i.test(b.textContent));
  if (!btn) return 'missing submit button';
  btn.click();
  return 'clicked';
})()`);
check('login submitted from the installed window', filled === 'clicked', filled);

await sleep(6000);
const afterLogin = await evaluate(`(() => ({
  hash: location.hash,
  text: document.body.innerText.slice(0, 160),
  local: Object.keys(localStorage),
}))()`);
check(
  'login lands on the spaces route',
  afterLogin.hash.includes('/spaces') && !afterLogin.hash.includes('/login'),
  `hash=${afterLogin.hash} text=${JSON.stringify(afterLogin.text)}`
);
check(
  'the token stayed out of web storage after login',
  afterLogin.local.length === 0,
  JSON.stringify(afterLogin.local)
);

// 4. R04 evidence: the app reached the origin from config.json, not the build
//    default. A CSP-violating cross-origin fetch from the page proves both the
//    connect-src source and which origin the renderer is allowed to talk to.
const cspProbe = await evaluate(`(async () => {
  try {
    const r = await fetch('http://127.0.0.1:9999/api/v1/spaces');
    return 'unexpectedly allowed: ' + r.status;
  } catch (e) {
    return 'blocked: ' + e.name;
  }
})()`);
check('connect-src blocks an origin the config never named', String(cspProbe).startsWith('blocked'), cspProbe);

const configuredBase = await evaluate(
  `(async () => window.aistudyConfig?.getApiBaseUrl() ?? 'no bridge')()`
);
check(
  'main reports the origin from config.json, not the build default',
  /:8081\b/.test(String(configuredBase)) && !/:8080\b/.test(String(configuredBase)),
  configuredBase
);

// The fetch has to go to that absolute origin. A relative call would resolve
// against app://aistudy — the renderer's own document origin, handled by main's
// protocol — and reject with "Failed to fetch", proving nothing about the
// backend. A bogus bearer plus a 401 is the pair of facts wanted here: the CSP
// lets exactly this origin through, and something is answering there.
const originProbe = await evaluate(`(async () => {
  try {
    const base = await window.aistudyConfig.getApiBaseUrl();
    const r = await fetch(base.replace(/\\/$$/, '') + '/api/v1/spaces', {
      headers: { Authorization: 'Bearer probe' },
    });
    return JSON.stringify({ base, status: r.status });
  } catch (e) {
    return JSON.stringify({ error: e.name + ': ' + e.message });
  }
})()`);
const originProbeParsed = JSON.parse(originProbe);
check(
  'the configured backend answers over the CSP-allowed origin',
  originProbeParsed.status === 401,
  originProbe
);

// 5. R03's restart-restore half: a fresh document must restore from the vault
//    without a second login.
await send('Page.reload', { ignoreCache: true });
await sleep(7000);
const restored = await evaluate(`(() => ({ hash: location.hash, text: document.body.innerText.slice(0, 120) }))()`);
check(
  'session survives a reload without returning to /login',
  restored.hash.includes('/spaces'),
  `hash=${restored.hash} text=${JSON.stringify(restored.text)}`
);

// 6. Logout returns to the login route and leaves no web storage behind.
const loggedOut = await evaluate(`(async () => {
  const btn = [...document.querySelectorAll('button, a')].find((b) => /退出|登出|Logout/i.test(b.textContent));
  if (!btn) return 'no logout control: ' + document.body.innerText.slice(0, 80);
  btn.click();
  await new Promise((r) => setTimeout(r, 2500));
  return JSON.stringify({ hash: location.hash, local: Object.keys(localStorage) });
})()`);
const logoutParsed = (() => {
  try {
    return JSON.parse(loggedOut);
  } catch {
    return null;
  }
})();
check(
  'logout returns to the login route and leaves no web storage',
  Boolean(logoutParsed?.hash?.includes('/login')) && logoutParsed.local.length === 0,
  loggedOut
);

client.close();
const failed = rows.filter((r) => !r.pass).length;
console.log(`\nSUMMARY: ${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 2 : 0);

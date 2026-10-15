/**
 * R03 (the half a page reload cannot prove): the INSTALLED app's auth lifecycle
 * across real process restarts.
 *
 *   anonymous start -> login -> kill the process -> restart -> restore from the
 *   safeStorage vault -> offline reload keeps the session -> logout -> kill ->
 *   restart -> anonymous again
 *
 * The audit's R03 asks for 关闭/重启/恢复/登出/重启匿名, so the restarts are done
 * by killing the process tree and spawning the installed exe again — a reload
 * only proves the renderer kept React state, not that main's vault survived.
 * Credentials come from the environment only. Run:
 *   powershell: nothing; this script spawns and kills the app itself.
 *   node scripts/r03-auth-lifecycle.mjs
 */

import { execFile, spawn } from 'node:child_process';
import { existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { findPageTarget, openClient } from './cdp.mjs';

const run = promisify(execFile);

const USER = process.env.AISTUDY_USER ?? '';
const PASS = process.env.AISTUDY_PASS ?? '';
const DEBUG = process.env.AISTUDY_CDP ?? 'http://127.0.0.1:9222';
const EXE = join(process.env.LOCALAPPDATA ?? '', 'Programs', 'AIStudy', 'AIStudy.exe');
const VAULT = join(process.env.APPDATA ?? '', 'AIStudy', 'auth-refresh.bin');

if (!USER || !PASS) {
  console.log('FAIL | prerequisites | AISTUDY_USER / AISTUDY_PASS are required');
  process.exit(1);
}
if (!existsSync(EXE)) {
  console.log(`FAIL | prerequisites | installed exe missing: ${EXE}`);
  process.exit(1);
}

const rows = [];
function check(name, pass, detail) {
  rows.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${String(detail).slice(0, 240)}`);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function vaultState() {
  if (!existsSync(VAULT)) {
    return { present: false, bytes: 0 };
  }
  return { present: true, bytes: statSync(VAULT).size };
}

async function killTree(pid) {
  await run('taskkill', ['/PID', String(pid), '/T', '/F']).catch(() => {});
  // Electron tears its child processes down asynchronously.
  for (let i = 0; i < 20; i += 1) {
    const { stdout } = await run('tasklist', ['/FI', 'IMAGENAME eq AIStudy.exe']).catch(() => ({
      stdout: '',
    }));
    if (!stdout.includes('AIStudy.exe')) {
      return;
    }
    await sleep(500);
  }
}

async function startApp() {
  const child = spawn(EXE, ['--remote-debugging-port=9222'], { stdio: 'ignore', detached: false });
  child.unref();
  const target = await findPageTarget(DEBUG, { timeoutMs: 60000 });
  const client = await openClient(target.webSocketDebuggerUrl);
  return { child, client };
}

/** Poll an evaluated expression until the predicate accepts it. */
async function waitFor(evaluate, expression, predicate, timeoutMs = 25000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await evaluate(expression);
    if (predicate(last)) {
      return last;
    }
    await sleep(500);
  }
  return last;
}

const STATE = `(() => ({
  hash: location.hash,
  passwordFields: document.querySelectorAll('input[type="password"]').length,
  local: Object.keys(localStorage).length,
  session: Object.keys(sessionStorage).length,
  text: document.body.innerText.slice(0, 120),
}))()`;

const LOGIN = `(async () => {
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
})()`;

const LOGOUT = `(async () => {
  const btn = [...document.querySelectorAll('button, a')].find((b) => /退出|登出|Logout/i.test(b.textContent));
  if (!btn) return 'no logout control';
  btn.click();
  await new Promise((r) => setTimeout(r, 2000));
  return location.hash;
})()`;

// Anything left running would collide with the single-instance lock, whose
// second launch only focuses the existing window instead of starting fresh.
await run('taskkill', ['/IM', 'AIStudy.exe', '/F']).catch(() => {});
await sleep(2500);

// --- 1. anonymous start ---------------------------------------------------
let { child, client } = await startApp();
let state = await waitFor(client.evaluate, STATE, (s) => s.hash.length > 0);
check(
  'R03 a fresh start is anonymous (login route, no vault, no web storage)',
  state.hash.includes('/login') && vaultState().present === false && state.local === 0,
  `hash=${state.hash} vault=${JSON.stringify(vaultState())} storage=${state.local}/${state.session}`
);

// --- 2. login writes the vault -------------------------------------------
const clicked = await client.evaluate(LOGIN);
state = await waitFor(client.evaluate, STATE, (s) => s.hash.includes('/spaces'), 30000);
await sleep(1500);
const vaultAfterLogin = vaultState();
check(
  'R03 login lands on /spaces and main stores the refresh token in the vault',
  clicked === 'clicked' && state.hash.includes('/spaces') && vaultAfterLogin.present && vaultAfterLogin.bytes > 0,
  `submit=${clicked} hash=${state.hash} vault=${JSON.stringify(vaultAfterLogin)}`
);
check(
  'R03 the token stayed out of renderer web storage',
  state.local === 0 && state.session === 0,
  `local=${state.local} session=${state.session}`
);

// --- 3. real process restart restores from the vault ---------------------
await killTree(child.pid);
client.close();
({ child, client } = await startApp());
state = await waitFor(client.evaluate, STATE, (s) => s.hash.length > 0 && s.text.length > 0);
check(
  'R03 after the process is killed and started again the session restores without a login',
  state.hash.includes('/spaces') && state.passwordFields === 0,
  `hash=${state.hash} passwordFields=${state.passwordFields} text=${JSON.stringify(state.text)}`
);

// --- 4. offline does not lose the restored session -----------------------
await client.send('Network.enable');
await client.send('Network.emulateNetworkConditions', {
  offline: true,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
});
await client.send('Page.reload', { ignoreCache: true });
await sleep(4000);
await client.send('Network.enable');
state = await waitFor(client.evaluate, STATE, (s) => s.hash.length > 0);
check(
  'R03 an offline reload keeps the session (auth restore is main-side, not a request)',
  state.hash.includes('/spaces'),
  `hash=${state.hash} text=${JSON.stringify(state.text)}`
);
await client.send('Network.emulateNetworkConditions', {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
});
await client.send('Page.reload', { ignoreCache: true });
await sleep(4000);
state = await waitFor(client.evaluate, STATE, (s) => s.hash.length > 0 && s.text.length > 0);
check(
  'R03 the same session recovers its data when the network returns',
  state.hash.includes('/spaces') && state.passwordFields === 0,
  `hash=${state.hash} text=${JSON.stringify(state.text)}`
);

// --- 5. logout clears the vault ------------------------------------------
const logoutHash = await client.evaluate(LOGOUT);
const vaultAfterLogout = vaultState();
check(
  'R03 logout returns to /login and deletes the vault file',
  String(logoutHash).includes('/login') && vaultAfterLogout.present === false,
  `hash=${logoutHash} vault=${JSON.stringify(vaultAfterLogout)}`
);

// --- 6. restart after logout stays anonymous -----------------------------
await killTree(child.pid);
client.close();
({ child, client } = await startApp());
state = await waitFor(client.evaluate, STATE, (s) => s.hash.length > 0);
check(
  'R05/R03 after a logout the next launch is anonymous again',
  state.hash.includes('/login') && vaultState().present === false && state.local === 0,
  `hash=${state.hash} vault=${JSON.stringify(vaultState())} storage=${state.local}/${state.session}`
);

client.close();
await killTree(child.pid);

const failed = rows.filter((r) => !r.pass).length;
console.log(`\nSUMMARY: ${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 2 : 0);

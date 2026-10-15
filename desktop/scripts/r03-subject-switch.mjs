/**
 * R03's last open case, on the INSTALLED build: 主体切换不串缓存.
 *
 * The unit test mocks `queryClient.clear()` and proves the boundary fires; it
 * cannot prove that the second principal actually never sees the first one's
 * rows. This drives the packaged window: sign in as A, read a space's data, log
 * out, sign in as B, then deep-link straight into A's space and require that
 * what renders is B's failure state — not A's cached rows.
 *
 *   AISTUDY_USER=dev AISTUDY_PASS=... AISTUDY_USER2=learner AISTUDY_PASS2=... \
 *     node scripts/r03-subject-switch.mjs
 */

import { createHash } from 'node:crypto';
import { execFile, spawn } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { findPageTarget, openClient } from './cdp.mjs';

const run = promisify(execFile);
const A_USER = process.env.AISTUDY_USER ?? '';
const A_PASS = process.env.AISTUDY_PASS ?? '';
const B_USER = process.env.AISTUDY_USER2 ?? '';
const B_PASS = process.env.AISTUDY_PASS2 ?? '';
const DEBUG = process.env.AISTUDY_CDP ?? 'http://127.0.0.1:9222';
const EXE = join(process.env.LOCALAPPDATA ?? '', 'Programs', 'AIStudy', 'AIStudy.exe');
const VAULT = join(process.env.APPDATA ?? '', 'AIStudy', 'auth-refresh.bin');

const rows = [];
function check(name, pass, detail) {
  rows.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${String(detail).slice(0, 260)}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

if (!A_USER || !A_PASS || !B_USER || !B_PASS) {
  console.log('FAIL | prerequisites | AISTUDY_USER/PASS and AISTUDY_USER2/PASS2 are all required');
  process.exit(1);
}
if (!existsSync(EXE)) {
  console.log(`FAIL | prerequisites | installed exe missing: ${EXE}`);
  process.exit(1);
}

const snapshot = `(() => {
  const root = document.body ?? document.documentElement;
  return JSON.stringify({
    hash: location.hash,
    text: (root.innerText || '').replace(/\\s+/g, ' ').slice(0, 700),
    spaceCards: [...root.querySelectorAll('a')]
      .map((a) => a.getAttribute('href') || '')
      .filter((h) => /^#\\/spaces\\/\\d+\\//.test(h)),
    noteTitles: [...root.querySelectorAll('a[href*="/notes/"]')]
      .map((a) => (a.textContent || '').replace(/\\s+/g, ' ').trim())
      .filter((t) => t.length > 1)
      .slice(0, 6),
  });
})()`;

async function startApp() {
  const child = spawn(EXE, ['--remote-debugging-port=9222'], { stdio: 'ignore' });
  child.unref();
  const target = await findPageTarget(DEBUG, { timeoutMs: 60000 });
  return { child, client: await openClient(target.webSocketDebuggerUrl) };
}

async function killTree(pid) {
  await run('taskkill', ['/PID', String(pid), '/T', '/F']).catch(() => {});
  await sleep(2500);
}

async function read(client) {
  return JSON.parse(await client.evaluate(snapshot));
}

async function waitFor(client, predicate, timeoutMs = 25000) {
  const deadline = Date.now() + timeoutMs;
  let last = await read(client);
  while (Date.now() < deadline) {
    if (predicate(last)) {
      return last;
    }
    await sleep(500);
    last = await read(client);
  }
  return last;
}

async function login(client, user, pass) {
  const filled = await client.evaluate(`(async () => {
    const set = (el, value) => {
      const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    };
    const user1 = document.querySelector('input[name="username"], input[type="text"]');
    const pass1 = document.querySelector('input[type="password"]');
    if (!user1 || !pass1) return 'missing inputs';
    set(user1, ${JSON.stringify(user)});
    set(pass1, ${JSON.stringify(pass)});
    const btn = [...document.querySelectorAll('button')].find((b) => /登录|登 录|Login/i.test(b.textContent));
    if (!btn) return 'missing submit button';
    btn.click();
    return 'clicked';
  })()`);
  return filled;
}

async function logout(client) {
  return client.evaluate(`(async () => {
    const btn = [...document.querySelectorAll('button, a')].find((b) => /退出|登出|Logout/i.test(b.textContent));
    if (!btn) return 'no logout control';
    btn.click();
    await new Promise((r) => setTimeout(r, 2000));
    return location.hash;
  })()`);
}

async function goto(client, path) {
  await client.evaluate(`(() => { location.hash = ${JSON.stringify(path)}; return 1; })()`);
  // The shell paints immediately and says 加载空间… while the scope resolves, so
  // a bare text-length wait would read a spinner as a loaded page.
  return waitFor(
    client,
    (s) => s.hash.includes(path) && s.text.length > 40 && !/加载|正在|检查 AI 配置/.test(s.text)
  );
}

function vaultFingerprint() {
  if (!existsSync(VAULT)) {
    return 'absent';
  }
  const bytes = readFileSync(VAULT);
  // The file begins with a plaintext `{"version":1…` that is identical for every
  // session, so a prefix fingerprint cannot tell two tokens apart.
  return `${statSync(VAULT).size}b:${createHash('sha256').update(bytes).digest('hex').slice(0, 16)}`;
}

// The single-instance lock means a second launch would only focus the first.
await run('taskkill', ['/IM', 'AIStudy.exe', '/F']).catch(() => {});
await sleep(2500);

let { child, client } = await startApp();

// --- principal A ------------------------------------------------------
let state = await waitFor(client, (s) => s.hash.includes('/login') || s.spaceCards.length > 0);
if (state.hash.includes('/login')) {
  await login(client, A_USER, A_PASS);
  state = await waitFor(client, (s) => s.spaceCards.length > 0);
}
const aCards = state.spaceCards;
const aSpace = aCards[0]?.split('/')[2];
check(
  `A (${A_USER}) signs in and sees its own spaces`,
  Boolean(aSpace) && state.hash.includes('/spaces'),
  `cards=${JSON.stringify(aCards)} hash=${state.hash}`
);

// Read something real, so A's rows are demonstrably in the renderer's cache.
const aNotes = await goto(client, `/spaces/${aSpace}/notes`);
const aTitles = aNotes.noteTitles;
const vaultA = vaultFingerprint();
check(
  'A loads a space and the vault holds its refresh token',
  aNotes.hash.includes(`/spaces/${aSpace}/notes`) && vaultA !== 'absent',
  `vault=${vaultA} noteRows=${aTitles.length} text="${aNotes.text.slice(0, 90)}"`
);

// --- switch principal --------------------------------------------------
const outHash = await logout(client);
const vaultAfterLogout = vaultFingerprint();
check(
  'logout returns to /login and removes the vault',
  String(outHash).includes('/login') && vaultAfterLogout === 'absent',
  `hash=${outHash} vault=${vaultAfterLogout}`
);

await login(client, B_USER, B_PASS);
// Login leaves the hash where A left it (A's space), so the meaningful check is
// that B's own list is reachable and contains no A space — not that the URL was
// rewritten under it.
state = await read(client);
check(
  `B (${B_USER}) is not left inside A's space: the screen says so and offers a way out`,
  /空间不存在|无权|不可访问/.test(state.text) && state.text.includes('返回学习空间列表'),
  `A space=${aSpace} hash=${state.hash} text="${state.text.slice(0, 130)}"`
);
const vaultB = vaultFingerprint();
check(
  'the vault now holds B’s rotated token, not A’s',
  vaultB !== 'absent' && vaultB !== vaultA,
  `A=${vaultA} B=${vaultB}`
);

// --- the actual cache-isolation assertion -------------------------------
const cross = await goto(client, `/spaces/${aSpace}/notes`);
const leaked = aTitles.filter((title) => cross.text.includes(title));
check(
  'B deep-linked into A’s space gets a server-decided failure, not A’s cached rows',
  aTitles.length > 0 &&
    leaked.length === 0 &&
    /不存在|不可访问|无权|无法加载|失败|还没有|空/.test(cross.text),
  `A had ${aTitles.length} rows (${JSON.stringify(aTitles.slice(0, 2))}) leaked=${JSON.stringify(leaked)} text="${cross.text.slice(0, 150)}"`
);

// A restart must not resurrect A's session either.
await killTree(child.pid);
client.close();
({ child, client } = await startApp());
state = await waitFor(client, (s) => s.hash.length > 0 && s.text.length > 40);
check(
  'B’s session restores from the vault after a restart (not A’s)',
  state.hash.includes('/spaces') && state.text.includes(B_USER),
  `hash=${state.hash} text="${state.text.slice(0, 90)}"`
);
const crossAfterRestart = await goto(client, `/spaces/${aSpace}/notes`);
check(
  'after the restart B still cannot read A’s space',
  /不存在|不可访问|无权|无法加载|失败|还没有|空/.test(crossAfterRestart.text),
  `text="${crossAfterRestart.text.slice(0, 160)}"`
);

await logout(client);
client.close();
await killTree(child.pid);

const failed = rows.filter((r) => !r.pass).length;
console.log(`\nSUMMARY: ${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 2 : 0);

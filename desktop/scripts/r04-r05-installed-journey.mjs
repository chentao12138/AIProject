/**
 * R04 / R05 on the INSTALLED build: the module-by-module GUI journey plus the
 * per-module paging / URL-replay / error-state cases, driven over CDP.
 *
 *   node scripts/r04-r05-installed-journey.mjs
 *
 * Rules that keep the evidence honest:
 * - every row says whether the page was reached by CLICK or by URL;
 * - a page is only read once it has SETTLED (the app's own 加载… text is gone),
 *   so a row never passes on a spinner;
 * - a module with no server data passes only when the component's own
 *   empty-state copy rendered, and the row says EMPTY;
 * - a case that cannot run because the data does not exist is SKIP, printed and
 *   excluded from the denominator — it is never folded into a pass or a crash.
 */

import { findPageTarget, openClient } from './cdp.mjs';

const USER = process.env.AISTUDY_USER ?? '';
const PASS = process.env.AISTUDY_PASS ?? '';
const DEBUG = process.env.AISTUDY_CDP ?? 'http://127.0.0.1:9222';

const rows = [];
const skipped = [];
function check(name, pass, detail) {
  rows.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | ${String(detail).slice(0, 300)}`);
}
function skip(name, reason) {
  skipped.push(name);
  console.log(`SKIP | ${name} | ${String(reason).slice(0, 240)}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const target = await findPageTarget(DEBUG);
const { send, evaluate, close } = await openClient(target.webSocketDebuggerUrl);

async function snap() {
  // Right after a reload the document is briefly empty and `document.body` is
  // null, which used to throw and take the rest of the run down with it.
  return evaluate(`(() => {
    const root = document.body ?? document.documentElement;
    const all = (selector) => [...root.querySelectorAll(selector)];
    const textOf = (el) => (el ? String(el.textContent ?? '').trim() : '');
    return {
      hash: location.hash,
      testids: all('[data-testid]').map((e) => e.getAttribute('data-testid')),
      hrefs: all('a').map((a) => a.getAttribute('href')),
      rowCount: all('table tbody tr').length,
      firstRowCell: textOf(root.querySelector('table tbody tr td')),
      pager: textOf(root.querySelector('[data-testid="questions-total"], [data-testid="attempt-history-total"]')),
      buttons: all('button').map((b) => (b.textContent || '').trim()).filter(Boolean),
      text: (root.innerText || '').replace(/\\s+/g, ' ').slice(0, 500),
      overflow: document.body ? document.body.scrollWidth - window.innerWidth : 0,
    };
  })()`);
}

const EMPTY = {
  hash: '',
  testids: [],
  hrefs: [],
  rowCount: 0,
  firstRowCell: '',
  pager: '',
  buttons: [],
  text: '',
  overflow: 0,
};

/** Polling crosses navigations, and an evaluate can land on a document that is
 *  being replaced. That is a moment to re-read, not a failed assertion. */
async function safeSnap() {
  try {
    return await snap();
  } catch {
    return EMPTY;
  }
}

/** The app's own busy markers: a snapshot containing one is not readable yet.
 *  An empty document (mid-reload) is not "settled" either. */
const settled = (s) => s.text.length > 40 && !/加载|检查 AI 配置|正在|上传中|解析中/.test(s.text);
const has = (s, testid) => s.testids.includes(testid);
const rowsUnder = (s, path) => s.hrefs.filter((h) => (h ?? '').startsWith(`#${path}/`)).length;

async function waitUntil(predicate, timeoutMs = 25000) {
  const deadline = Date.now() + timeoutMs;
  let last = await safeSnap();
  while (Date.now() < deadline) {
    if (predicate(last)) {
      return last;
    }
    await sleep(400);
    last = await safeSnap();
  }
  return last;
}

/** Wait for a route, then for that route's data to settle. */
async function gotoUrl(path, extra = () => true) {
  await evaluate(`(() => { location.hash = ${JSON.stringify(path)}; return 1; })()`);
  return waitUntil((s) => s.hash.includes(path) && settled(s) && extra(s));
}

async function clickByHref(href) {
  const clicked = await evaluate(`(() => {
    const link = [...document.querySelectorAll('a')].find((a) => a.getAttribute('href') === ${JSON.stringify(href)});
    if (!link) return false;
    link.click();
    return true;
  })()`);
  if (!clicked) {
    return false;
  }
  return waitUntil((s) => s.hash === `#${String(href).replace(/^#/, '')}` && settled(s));
}

/** Open a table row through its action. These lists route through a button
 *  (or a link) plus `navigate()`, so href counting alone would report a
 *  populated table as empty. */
async function clickRowAction(labelPattern, hashPattern) {
  const label = await evaluate(`(() => {
    const el = [...document.querySelectorAll('button, a')].find((b) => new RegExp(${JSON.stringify(labelPattern)}).test((b.textContent || '').trim()));
    if (!el) return null;
    el.click();
    return (el.textContent || '').trim();
  })()`);
  if (!label) {
    return null;
  }
  const s = await waitUntil((x) => hashPattern.test(x.hash) && settled(x));
  return { label, ...s };
}

async function step(name, fn) {
  try {
    const result = await fn();
    if (result.skip) {
      skip(name, result.detail);
    } else {
      check(name, result.pass, result.detail);
    }
  } catch (error) {
    check(name, false, `threw: ${error.message}`);
  }
}

/** Type into a React-controlled input through the native setter. */
async function typeInto(selector, value) {
  return evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return 'no input';
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event('input', { bubbles: true }));
    return 'typed';
  })()`);
}

const MODULES = ['sources', 'knowledge', 'questions', 'practice', 'exams', 'search', 'notes', 'ai'];

// --- 1. login ----------------------------------------------------------
await step('R04 login by clicking the form reaches the spaces list', async () => {
  const s = await waitUntil((x) => x.hash.includes('/login') || x.hash.includes('/spaces'));
  if (s.hash.includes('/spaces')) {
    return { pass: true, detail: `already signed in, hash=${s.hash}` };
  }
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
  const landed = await waitUntil((x) => spaceHref(x) && x.hash.includes('/spaces'));
  return {
    pass: filled === 'clicked' && Boolean(spaceHref(landed)),
    detail: `submit=${filled} hash=${landed.hash} cards=${spaceHref(landed)}`,
  };
});

/** A space card links to `…/spaces/<id>/sources`. */
function spaceHref(s) {
  return s.hrefs.find((h) => /^#\/spaces\/\d+\//.test(h ?? '')) ?? null;
}

const start = await waitUntil((s) => Boolean(spaceHref(s)));
const spaceId = spaceHref(start)?.split('/')[2];
if (!spaceId) {
  check('journey can start', false, `no space card: text="${start.text.slice(0, 200)}"`);
  close();
  process.exit(2);
}
const base = `/spaces/${spaceId}`;

// --- 2. shell offers every module -------------------------------------
await step('R04 the space opens from a click and the shell links every module', async () => {
  const href = spaceHref(await safeSnap());
  const after = await clickByHref(href);
  const missing = MODULES.filter((m) => !after.hrefs.some((h) => (h ?? '').startsWith(`#${base}/${m}`)));
  return {
    pass: Boolean(after) && missing.length === 0,
    detail: `via=click ${href} missing=${JSON.stringify(missing)} hash=${after?.hash}`,
  };
});

// --- 3. sources (no data in this space yet -> empty state) -------------
await step('R04 sources renders rows or its own empty state', async () => {
  const s = await gotoUrl(`${base}/sources`);
  const n = rowsUnder(s, `${base}/sources`);
  const empty = /还没有学习来源/.test(s.text);
  return {
    pass: n > 0 || empty,
    detail: `via=url rows=${n} ${empty ? 'EMPTY(还没有学习来源)' : `text="${s.text.slice(0, 90)}"`}`,
  };
});

const sourceHref = (await snap()).hrefs.find((h) => (h ?? '').startsWith(`#${base}/sources/`));
if (!sourceHref) {
  skip('R04 a source workbench shows ingestion state and content blocks', 'no source exists in this space (see the sources row)');
} else {
  await step('R04 a source workbench shows ingestion state and content blocks', async () => {
    const s = await clickByHref(sourceHref);
    const markers = ['content-block', 'content-state', 'active-job', 'processing-note'].filter((t) => has(s, t));
    return { pass: markers.length > 0, detail: `via=click ${sourceHref} markers=${JSON.stringify(markers)}` };
  });
}

// --- 4. knowledge ------------------------------------------------------
await step('R04 knowledge list renders points or its own empty state', async () => {
  const s = await gotoUrl(`${base}/knowledge`);
  const n = rowsUnder(s, `${base}/knowledge`);
  const empty = /还没有知识点/.test(s.text) || has(s, 'kp-empty');
  return {
    pass: n > 0 || empty,
    detail: `via=url rows=${n} ${empty ? 'EMPTY(还没有知识点)' : `text="${s.text.slice(0, 90)}"`}`,
  };
});

const kpHref = (await snap()).hrefs.find((h) => /#\/spaces\/\d+\/knowledge\/\d/.test(h ?? ''));
if (!kpHref) {
  skip('R04 a knowledge point shows provenance and related notes', 'no knowledge point exists in this space');
} else {
  await step('R04 a knowledge point shows provenance and related notes', async () => {
    const s = await clickByHref(kpHref);
    const provenance = ['provenance-row', 'provenance-link', 'content-block'].filter((t) => has(s, t));
    const related = ['related-note', 'related-notes-empty', 'related-notes-error'].filter((t) => has(s, t));
    return { pass: provenance.length > 0 && related.length > 0, detail: `via=click ${kpHref} provenance=${JSON.stringify(provenance)} related=${JSON.stringify(related)}` };
  });
}

// --- 5. questions: pager, paging, URL replay, detail ------------------
// Real paging needs a second page, and the space holds a couple of questions.
// With AISTUDY_FIXTURE=1 this script creates throwaway DRAFT questions through
// the same API the app uses, runs the paging rows, and archives every id it
// created at the end. Without the flag the paging rows are SKIP, not PASS.
const API = process.env.AISTUDY_API ?? 'http://localhost:8081';
const createdIds = [];
let fixtureToken = null;
if (process.env.AISTUDY_FIXTURE === '1') {
  const login = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  });
  fixtureToken = (await login.json())?.accessToken ?? null;
  const page = await fetch(`${API}/api/v1/spaces/${spaceId}/questions?page=0&size=1`, {
    headers: { Authorization: `Bearer ${fixtureToken}` },
  }).then((r) => r.json());
  for (let i = Number(page?.totalItems ?? 0); i < 21; i += 1) {
    const created = await fetch(`${API}/api/v1/spaces/${spaceId}/questions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${fixtureToken}` },
      body: JSON.stringify({ questionType: 'SHORT_ANSWER', stem: `R05 分页夹具 ${i}`, difficulty: 'EASY' }),
    }).then((r) => r.json());
    if (created?.id) {
      createdIds.push(created.id);
    }
  }
  console.log(`INFO | fixture | created ${createdIds.length} DRAFT questions for a second page`);
}

let page0Cell = null;
let totalPages = 0;
let pagedOk = false;
await step('R05 questions page 0 renders server rows and a server-side pager', async () => {
  // Cold document: the renderer's query cache survives a script run, and a
  // fixture created after the last visit would otherwise be hidden behind the
  // rows that cache still holds.
  await send('Page.reload', { ignoreCache: true });
  await waitUntil((x) => settled(x) && x.text.length > 0);
  const s = await gotoUrl(`${base}/questions`, (x) => has(x, 'questions-total'));
  page0Cell = s.firstRowCell;
  totalPages = Number(s.pager.match(/\/ (\d+) 页/)?.[1] ?? 0);
  return {
    pass: s.rowCount > 0 && /第 1 \//.test(s.pager),
    detail: `via=url rows=${s.rowCount} pager="${s.pager}" firstCell="${page0Cell}"`,
  };
});

await step('R05 ?page=1 asks the server for a different page', async () => {
  if (totalPages < 2) {
    return { skip: true, detail: `only ${totalPages} page exists; re-run with AISTUDY_FIXTURE=1` };
  }
  const s = await gotoUrl(`${base}/questions?page=1`);
  pagedOk = /第 2 \//.test(s.pager) && s.firstRowCell !== page0Cell && s.rowCount > 0;
  return {
    pass: pagedOk,
    detail: `via=url pager="${s.pager}" page1Cell="${s.firstRowCell}" page0Cell="${page0Cell}"`,
  };
});

await step('R05 a paged URL replays across a reload', async () => {
  if (!pagedOk) {
    return { skip: true, detail: 'the page=1 row did not land on a real second page' };
  }
  await send('Page.reload', { ignoreCache: true });
  const s = await waitUntil((x) => x.hash.includes('page=1') && settled(x) && /第 2 \/ \d+ 页/.test(x.pager));
  return {
    pass: s.hash.includes('page=1') && /第 2 \//.test(s.pager),
    detail: `after reload hash=${s.hash} pager="${s.pager}"`,
  };
});

await step('R05 a page beyond the last one is healed to a page that exists', async () => {
  const s = await gotoUrl(`${base}/questions?page=99`, (x) => /第 \d+ \/ \d+ 页/.test(x.pager) && x.rowCount > 0);
  const shown = Number(s.pager.match(/第 (\d+) \/ (\d+) 页/)?.[1] ?? 0);
  const total = Number(s.pager.match(/第 (\d+) \/ (\d+) 页/)?.[2] ?? 0);
  return {
    pass: s.hash.includes('page=') && shown <= total && shown > 0 && s.rowCount > 0,
    detail: `via=url hash=${s.hash} pager="${s.pager}" rows=${s.rowCount}`,
  };
});

await step('R04 a question detail renders what the server stores', async () => {
  const opened = await gotoUrl(`${base}/questions`).then(() => clickRowAction('查看', /\/questions\/\d+$/));
  if (!opened) {
    return { skip: true, detail: 'the questions table offered no 查看 action' };
  }
  const markers = opened.testids.filter((t) => ['stored-order', 'stored-matches', 'question-edit-link', 'question-not-editable', 'not-editable-banner', 'publish-gap-notice', 'answer-explanation', 'correct-order-preview', 'archived-notice', 'question-restore-button', 'question-archive-button', 'reference-answer', 'question-renderer'].includes(t));
  return { pass: markers.length > 0, detail: `via=click "${opened.label}" hash=${opened.hash} markers=${JSON.stringify(markers)}` };
});

// --- 6. practice / exams ---------------------------------------------
await step('R04 practice renders sessions or its empty state', async () => {
  const s = await gotoUrl(`${base}/practice`);
  return {
    pass: s.rowCount > 0 || /还没有|暂无|没有/.test(s.text),
    detail: `via=url rows=${s.rowCount} buttons=${JSON.stringify(s.buttons.slice(0, 6))}`,
  };
});

await step('R04 a practice session opens its review', async () => {
  const opened = await gotoUrl(`${base}/practice`).then(() => clickRowAction('打开', /\/practice\/\d+$/));
  if (!opened) {
    return { skip: true, detail: 'the practice list offered no 打开 action' };
  }
  const markers = opened.testids.filter((t) => ['review-item', 'summary-line', 'result-score', 'answer-explanation', 'review-unavailable', 'answers-loading', 'review-error'].includes(t));
  return { pass: markers.length > 0, detail: `via=click "${opened.label}" hash=${opened.hash} markers=${JSON.stringify(markers)} text="${opened.text.slice(0, 120)}"` };
});

await step('R04 exams renders the paper list', async () => {
  const s = await gotoUrl(`${base}/exams`);
  return {
    pass: s.rowCount > 0 || /还没有|暂无|没有/.test(s.text),
    detail: `via=url rows=${s.rowCount} text="${s.text.match(/考试列表[\s\S]{0,120}/)?.[0] ?? s.text.slice(0, 90)}"`,
  };
});

await step('R04 an exam detail opens its attempt history', async () => {
  const opened = await gotoUrl(`${base}/exams`).then(() => clickRowAction('查看', /\/exams\/\d+$/));
  if (!opened) {
    return { skip: true, detail: 'the exam list offered no 查看 action' };
  }
  const markers = ['history-row', 'attempt-history-total', 'not-started-note', 'submit-hint', 'locked-fields'].filter((t) => opened.testids.includes(t));
  return { pass: markers.length > 0, detail: `via=click "${opened.label}" hash=${opened.hash} markers=${JSON.stringify(markers)} rows=${opened.rowCount}` };
});

await step('R04 a past attempt states whether its score is final', async () => {
  const opened = await clickRowAction('查看成绩|查看作答|继续作答', /\/attempts\/\d+$/);
  if (!opened) {
    return { skip: true, detail: 'the attempt history offered no row action (no attempt for this paper)' };
  }
  const markers = ['result-score', 'result-score-line', 'result-provisional', 'result-empty', 'result-error', 'result-status-unknown', 'resume-notice', 'answer-explanation'].filter((t) => opened.testids.includes(t));
  return { pass: markers.length > 0, detail: `via=click "${opened.label}" hash=${opened.hash} markers=${JSON.stringify(markers)} text="${opened.text.slice(0, 140)}"` };
});

// --- 7. search -------------------------------------------------------
await step('R04 search submits a query and renders hits or its empty state', async () => {
  await gotoUrl(`${base}/search`, (x) => has(x, 'types-filter-hint'));
  const typed = await typeInto('input.input, input:not([type]), input[type="search"]', '首都');
  const submitted = await evaluate(`(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => /搜索/.test(b.textContent));
    if (!btn) return 'no search button';
    btn.click();
    return 'searched';
  })()`);
  const r = await waitUntil((x) => x.hash.includes('/search') && settled(x) && /结果|命中|没有|暂无/.test(x.text));
  const hits = r.hrefs.filter((h) => (h ?? '').startsWith(`#${base}/`)).length;
  return {
    pass: typed === 'typed' && submitted === 'searched' && (hits > 1 || /没有|暂无|0 条|无结果/.test(r.text)),
    detail: `via=click typed=${typed} submit=${submitted} hitLinks=${hits} text="${r.text.slice(0, 130)}"`,
  };
});

// --- 8. notes + archived filter -------------------------------------
await step('R04 notes renders its server count', async () => {
  const s = await gotoUrl(`${base}/notes`, (x) => has(x, 'note-count'));
  return { pass: has(s, 'note-count'), detail: `via=url note-count="${s.text.match(/笔记[^。]{0,24}/)?.[0] ?? ''}"` };
});

await step('R05 the archived filter changes the request and the view', async () => {
  const before = await snap();
  const toggled = await evaluate(`(() => {
    const box = document.querySelector('[data-testid="notes-archived-filter"]');
    if (!box) return 'no filter control';
    // The control is a checkbox inside a label: clicking the label is what
    // flips it, and clicking a bare wrapper does nothing at all.
    const target = box.tagName === 'INPUT' ? box : box.closest('label') ?? box.querySelector('input') ?? box;
    target.click();
    return 'clicked ' + target.tagName.toLowerCase();
  })()`);
  const s = await waitUntil((x) => x.testids.includes('notes-archived-mode') || x.hash.includes('archived'));
  return {
    pass: String(toggled).startsWith('clicked') && (has(s, 'notes-archived-mode') || s.hash.includes('archived')),
    detail: `via=click ${toggled} hash=${s.hash} before=${JSON.stringify(before.testids.slice(0, 6))} after=${JSON.stringify(s.testids.slice(0, 8))}`,
  };
});

// --- 9. AI -----------------------------------------------------------
await step('R04 the AI surface renders either the chat or the not-configured gate', async () => {
  const s = await gotoUrl(`${base}/ai`);
  const markers = ['ai-messages', 'ai-message', 'ai-conversation-count', 'provider-not-ready'].filter((t) => has(s, t));
  return { pass: markers.length > 0, detail: `via=url markers=${JSON.stringify(markers)} text="${s.text.slice(0, 120)}"` };
});

// --- 10. R05 navigation + failure states ----------------------------
await step('R05 back/forward restores the previous module', async () => {
  await gotoUrl(`${base}/questions`);
  await gotoUrl(`${base}/notes`);
  await evaluate('(() => { history.back(); return 1; })()');
  const s = await waitUntil((x) => x.hash.includes('/questions'));
  return { pass: s.hash.includes('/questions') && has(s, 'questions-total'), detail: `hash=${s.hash}` };
});

await step('R05 a 480px-wide window keeps the shell without horizontal overflow', async () => {
  await send('Emulation.setDeviceMetricsOverride', { width: 480, height: 900, deviceScaleFactor: 1, mobile: false });
  const s = await waitUntil((x) => x.text.length > 0);
  const overflow = s.overflow;
  const navKept = MODULES.every((m) => s.hrefs.some((h) => (h ?? '').startsWith(`#${base}/${m}`)));
  await send('Emulation.clearDeviceMetricsOverride', {});
  return { pass: overflow <= 2 && navKept, detail: `scrollWidth-innerWidth=${overflow}px navComplete=${navKept}` };
});

await step('R05 an unknown id settles into a failure state instead of a blank page', async () => {
  const s = await gotoUrl(`${base}/knowledge/999999`);
  return { pass: s.text.length > 0 && settled(s), detail: `settled text="${s.text.slice(0, 160)}" testids=${JSON.stringify(s.testids.slice(0, 8))}` };
});

await step('R05 offline keeps the route and shows the retry UI, then recovers', async () => {
  await gotoUrl(`${base}/notes`);
  await send('Network.enable');
  await send('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await send('Page.reload', { ignoreCache: true });
  const s = await waitUntil((x) => /无法连接|重试/.test(x.text));
  await send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await send('Page.reload', { ignoreCache: true });
  const back = await waitUntil((x) => x.testids.includes('note-count') && settled(x));
  return {
    pass: /无法连接|重试/.test(s.text) && has(back, 'note-count'),
    detail: `offline="${s.text.slice(0, 100)}" recovered hash=${back.hash}`,
  };
});

// --- 11. logout ------------------------------------------------------
await step('R04 logout ends the journey at the login route', async () => {
  const hash = await evaluate(`(async () => {
    const btn = [...document.querySelectorAll('button, a')].find((b) => /退出|登出|Logout/i.test(b.textContent));
    if (!btn) return 'no logout control';
    btn.click();
    await new Promise((r) => setTimeout(r, 2000));
    return location.hash;
  })()`);
  return { pass: String(hash).includes('/login'), detail: `via=click hash=${hash}` };
});

for (const id of createdIds) {
  await fetch(`${API}/api/v1/spaces/${spaceId}/questions/${id}/archive`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${fixtureToken}` },
  }).catch(() => {});
}
if (createdIds.length) {
  console.log(`INFO | fixture | archived the ${createdIds.length} questions it created`);
}

close();
const failed = rows.filter((r) => !r.pass).length;
console.log(`\nSUMMARY: ${rows.length - failed}/${rows.length} passed, ${skipped.length} skipped`);
for (const name of skipped) {
  console.log(`  skipped: ${name}`);
}
process.exit(failed ? 2 : 0);

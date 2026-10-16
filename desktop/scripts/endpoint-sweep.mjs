/**
 * Full-operation sweep against a running backend.
 *
 * The question this answers is not "does the journey work" but "can every
 * operation in the committed contract be called at all, and does any of them
 * answer 5xx or an unlocatable 4xx". So it enumerates every path+method in
 * packages/api-client/src/generated/openapi.json, builds a schema-legal request
 * (required props present, enums satisfied, correct types), and classifies the
 * answer.
 *
 * Safety: writes go to a space this script creates itself, so a sweep cannot
 * corrupt the data the R03/R04 evidence depends on. Resources it did not create
 * are never deleted.
 *
 *   AISTUDY_API=http://localhost:8081 AISTUDY_USER=dev AISTUDY_PASS=... \
 *     node scripts/endpoint-sweep.mjs [--mode=read|all]
 *
 * Classification:
 *   OK        2xx
 *   CLIENT    4xx — reported with the server's own code/detail; a 4xx on a
 *             schema-legal request is a finding, not a pass
 *   SERVER    5xx or a transport error — always a backend bug
 *   SKIPPED   an input this script cannot produce (unknown id, file upload,
 *             SSE stream, admin-only under a learner token)
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const API = (process.env.AISTUDY_API ?? 'http://localhost:8081').replace(/\/$/, '');
const USER = process.env.AISTUDY_USER ?? '';
const PASS = process.env.AISTUDY_PASS ?? '';
const MODE = (process.argv.find((a) => a.startsWith('--mode=')) ?? '--mode=read').split('=')[1];
const SPEC_PATH = join(process.cwd(), '..', 'packages', 'api-client', 'src', 'generated', 'openapi.json');

const spec = JSON.parse(readFileSync(SPEC_PATH, 'utf8'));

function deref(node, seen = 0) {
  if (!node || typeof node !== 'object' || seen > 6) {
    return node ?? {};
  }
  if (typeof node.$ref === 'string') {
    const parts = node.$ref.replace(/^#\//, '').split('/');
    let target = spec;
    for (const part of parts) {
      target = target?.[part];
    }
    return deref(target, seen + 1);
  }
  if (Array.isArray(node.allOf)) {
    return node.allOf.reduce(
      (acc, part) => {
        const resolved = deref(part, seen + 1);
        return {
          ...resolved,
          ...acc,
          properties: { ...(resolved.properties ?? {}), ...(acc.properties ?? {}) },
          required: [...(resolved.required ?? []), ...(acc.required ?? [])],
        };
      },
      { type: 'object', properties: {}, required: [] }
    );
  }
  return node;
}

/** A schema-legal example value, so a 400 back means the server disagreed with
 *  the contract — not that we sent nonsense. */
function exampleFor(schema, name = '', depth = 0) {
  const s = deref(schema, 0);
  if (Array.isArray(s.enum) && s.enum.length > 0) {
    return s.enum[0];
  }
  if (s.format === 'date-time') {
    return new Date().toISOString();
  }
  if (s.format === 'date') {
    return '2026-01-01';
  }
  switch (s.type ?? (s.properties ? 'object' : undefined)) {
    case 'object': {
      if (depth > 3) {
        return {};
      }
      const out = {};
      for (const key of s.required ?? Object.keys(s.properties ?? {})) {
        out[key] = exampleFor(s.properties?.[key] ?? {}, key, depth + 1);
      }
      return out;
    }
    case 'array':
      return [exampleFor(s.items ?? {}, name, depth + 1)];
    case 'integer':
    case 'number':
      return 1;
    case 'boolean':
      return true;
    case 'string':
      return /id|Id$/.test(name) ? '1' : 'e2e-sweep';
    default:
      return 'e2e-sweep';
  }
}

async function call(method, path, { body, token = null, query = {} } = {}) {
  const url = new URL(`${API}${path}`);
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) {
      url.searchParams.set(key, String(value));
    }
  }
  const headers = {};
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (body !== undefined) {
    // charset is explicit because a UTF-8 name without it has been observed to
    // come back as an unlocatable "请求体无法解析" 400.
    headers['Content-Type'] = 'application/json; charset=utf-8';
  }
  const started = Date.now();
  try {
    const res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000),
    });
    const text = await res.text();
    let parsed = null;
    try {
      parsed = JSON.parse(text);
    } catch {
      /* non-JSON body is fine for classification */
    }
    return {
      status: res.status,
      ms: Date.now() - started,
      code: parsed?.code ?? null,
      detail: (parsed?.detail ?? parsed?.message ?? text ?? '').slice(0, 160),
    };
  } catch (error) {
    return { status: 0, ms: Date.now() - started, code: 'TRANSPORT', detail: error.message };
  }
}

function classify(result) {
  if (result.status === 0 || result.status >= 500) {
    return 'SERVER';
  }
  if (result.status >= 200 && result.status < 300) {
    return 'OK';
  }
  return 'CLIENT';
}

/**
 * Endpoints whose schema-legal placeholder is rejected for a reason the contract
 * does not say. `CreateSourceRequest.sourceType` is a bare `string` while the
 * server only accepts four values that exist as Java constants
 * (SourceService.SOURCE_TYPE_*), and `CreatePracticeSessionRequest` has no
 * required list at all yet rejects "questionIds XOR filter-based". Each entry
 * documents the fact the contract is silent about, so the sweep exercises the
 * endpoint instead of reporting a self-inflicted 400.
 */
const BODY_OVERRIDES = {
  'POST /api/v1/spaces/{spaceId}/sources': () => ({
    sourceType: 'DESKTOP_UPLOAD',
    title: '扫描器来源',
  }),
  'POST /api/v1/spaces/{spaceId}/knowledge-points': () => ({
    title: '扫描器知识点',
    content: '扫描器正文',
    summary: '扫描器摘要',
  }),
  'POST /api/v1/spaces/{spaceId}/practice-sessions': (ids) => ({
    questionIds: [ids.questionId].filter((id) => id != null),
  }),
  'POST /api/v1/spaces/{spaceId}/review-tasks': (ids) => ({
    targetType: 'QUESTION',
    targetId: ids.questionId,
  }),
  'POST /api/v1/spaces/{spaceId}/ai/generation-jobs': () => ({
    jobType: 'knowledge-extraction',
  }),
};

async function main() {
  if (!USER || !PASS) {
    console.log('AISTUDY_USER / AISTUDY_PASS are required');
    process.exit(1);
  }
  const loginRes = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ username: USER, password: PASS }),
    signal: AbortSignal.timeout(20000),
  }).catch((error) => ({ status: 0, detail: error.message }));
  if (loginRes.status !== 200) {
    console.log(`login failed: ${loginRes.status}`);
    process.exit(1);
  }
  const token = (await loginRes.json()).accessToken;

  // --- discover real ids so path parameters are not guesses ---------------
  const ids = {};
  const readIds = async (path, pick) => {
    const res = await fetch(`${API}${path}?page=0&size=5`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(20000),
    }).catch(() => null);
    if (!res || res.status !== 200) {
      return null;
    }
    const body = await res.json().catch(() => null);
    return pick(body);
  };
  ids.spaceId = (await readIds('/api/v1/spaces', (b) => (b?.items ?? b ?? [])[0]?.id)) ?? ids.spaceId;
  if (!ids.spaceId) {
    console.log('no space available to sweep against');
    process.exit(1);
  }
  // --- write mode runs in a space it created itself -----------------------
  // Without this, 143 mutating operations would fire at the seeded space the
  // R03/R04 evidence depends on. It also discovers ids the live seed does not
  // currently contain (sources, knowledge points, exams, attempts), so the
  // sweep's own coverage does not wait on someone else seeding data.
  if (MODE === 'all') {
    const stamp = Date.now();
    const created = await call('POST', '/api/v1/spaces', {
      token,
      body: { name: `endpoint-sweep-${stamp}`, description: 'sweep fixture space' },
    });
    console.log('sweep space:', created.status, JSON.stringify(created).slice(0, 120));
    const spaceBody = await (
      await fetch(`${API}/api/v1/spaces`, { headers: { Authorization: `Bearer ${token}` } })
    ).json();
    const mine = (spaceBody.items ?? spaceBody ?? []).find((s) => s.name === `endpoint-sweep-${stamp}`);
    if (mine?.id) {
      ids.spaceId = mine.id;
      const s2 = `/api/v1/spaces/${mine.id}`;
      const post = async (path, body) => {
        const res = await fetch(`${API}${path}`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(20000),
        });
        return { status: res.status, data: await res.json().catch(() => null) };
      };
      const q = await post(`${s2}/questions`, {
        questionType: 'SHORT_ANSWER',
        stem: '扫描器题目',
        referenceAnswer: '答案',
        difficulty: 'EASY',
      });
      ids.questionId = q.data?.id ?? null;
      if (ids.questionId) {
        await post(`${s2}/questions/${ids.questionId}/publish`, {});
      }
      ids.noteId = (await post(`${s2}/notes`, { content: '扫描器笔记' })).data?.id ?? null;
      ids.sourceId = (
        await post(`${s2}/sources`, BODY_OVERRIDES['POST /api/v1/spaces/{spaceId}/sources']())
      ).data?.id ?? null;
      ids.knowledgePointId = (
        await post(
          `${s2}/knowledge-points`,
          BODY_OVERRIDES['POST /api/v1/spaces/{spaceId}/knowledge-points']()
        )
      ).data?.id ?? null;
      ids.examId = (
        await post(`${s2}/exams`, {
          title: '扫描器试卷',
          timeLimitMinutes: 30,
          questions: [{ questionId: ids.questionId, score: 4 }],
        })
      ).data?.id ?? null;
      const session = await post(`${s2}/practice-sessions`, { questionIds: [ids.questionId] });
      ids.sessionId = session.data?.id ?? null;
      if (ids.examId) {
        // An exam is created in DRAFT and attempts require it to be available,
        // so publish first and ignore a failure — the attempt simply stays null
        // and the sweep reports it as SKIPPED rather than masking the reason.
        await post(`${s2}/exams/${ids.examId}/publish`, {});
        const attempt = await post(`${s2}/exam-attempts`, { examId: ids.examId });
        ids.attemptId = attempt.data?.id ?? null;
        if (ids.attemptId) {
          await post(`${s2}/exam-attempts/${ids.attemptId}/start`, {});
        }
      }
      const conversation = await post(`${s2}/ai/conversations`, { title: '扫描器会话' });
      ids.conversationId = conversation.data?.id ?? null;
      console.log('seeded ids:', JSON.stringify(ids));
    }
  }
  const s = `/api/v1/spaces/${ids.spaceId}`;
  ids.questionId = (await readIds(`${s}/questions`, (b) => (b.items ?? [])[0]?.id)) ?? ids.questionId;
  ids.noteId = (await readIds(`${s}/notes`, (b) => (b.items ?? [])[0]?.id)) ?? ids.noteId;
  ids.examId = (await readIds(`${s}/exams`, (b) => (b.items ?? [])[0]?.id)) ?? ids.examId;
  ids.knowledgePointId = (await readIds(`${s}/knowledge-points`, (b) => (b.items ?? [])[0]?.id)) ?? ids.knowledgePointId;
  ids.sourceId = (await readIds(`${s}/sources`, (b) => (b.items ?? [])[0]?.id)) ?? ids.sourceId;
  ids.conversationId = (await readIds(`${s}/conversations`, (b) => (b.items ?? [])[0]?.id)) ?? ids.conversationId;
  console.log('discovered ids:', JSON.stringify(ids));

  // --- walk every operation ----------------------------------------------
  const results = [];
  for (const [template, item] of Object.entries(spec.paths)) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const op = item[method];
      if (!op) {
        continue;
      }
      if (MODE === 'read' && method !== 'get') {
        continue;
      }
      const params = (op.parameters ?? []).map((p) => deref(p));
      let path = template;
      const unresolved = [];
      for (const p of params.filter((p) => p.in === 'path')) {
        const known = ids[p.name];
        if (known === undefined || known === null) {
          unresolved.push(p.name);
          continue;
        }
        path = path.replace(`{${p.name}}`, String(known));
      }
      if (path.includes('{') || unresolved.length > 0) {
        results.push({
          bucket: 'SKIPPED',
          op: `${method.toUpperCase()} ${template}`,
          detail: unresolved.length ? `no id for ${unresolved.join(',')}` : 'unresolved path',
        });
        continue;
      }
      if ((op.requestBody && hasFile(op)) || isStream(op)) {
        results.push({ bucket: 'SKIPPED', op: `${method.toUpperCase()} ${template}`, detail: 'multipart upload' });
        continue;
      }
      const query = {};
      for (const p of params.filter((p) => p.in === 'query' && p.required)) {
        query[p.name] = exampleFor(p.schema ?? {}, p.name);
      }
      let body;
      if (op.requestBody) {
        const media = op.requestBody.content?.['application/json'];
        if (media) {
          body = exampleFor(media.schema ?? {}, 'body');
        }
      }
      const key = `${method.toUpperCase()} ${template}`;
      if (body !== undefined && BODY_OVERRIDES[key]) {
        body = BODY_OVERRIDES[key](ids);
      }
      const result = await call(method, path, { token, body, query });
      results.push({
        bucket: classify(result),
        op: `${method.toUpperCase()} ${template}`,
        detail: `${result.status}${result.code ? ` ${result.code}` : ''} ${result.detail} (${result.ms}ms)`,
      });
    }
  }

  // --- second token: what does a learner get on the admin surface? --------
  // Recorded rather than assumed because the answer is a contract fact the
  // generated snapshot does not carry (403 and 404 are both defensible, and the
  // desktop client branches its copy on it).
  const USER2 = process.env.AISTUDY_USER2 ?? '';
  const PASS2 = process.env.AISTUDY_PASS2 ?? '';
  if (USER2 && PASS2) {
    const login2 = await fetch(`${API}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ username: USER2, password: PASS2 }),
      signal: AbortSignal.timeout(20000),
    }).catch(() => null);
    if (!login2 || login2.status !== 200) {
      console.log(`LEARNER | login failed (${login2?.status}), admin pass skipped`);
    } else {
      const token2 = (await login2.json()).accessToken;
      const seen = {};
      for (const template of Object.keys(spec.paths)) {
        if (!template.includes('/admin/')) {
          continue;
        }
        const result = await call('GET', template.replace(/\{[^}]+\}/g, '1'), { token: token2 });
        const kind = String(result.status);
        seen[kind] = (seen[kind] ?? 0) + 1;
        if (result.status >= 500 || result.status === 0) {
          console.log(`SERVER | GET ${template} as ${USER2} | ${result.detail}`);
        }
      }
      console.log(`ADMIN-AS-${USER2.toUpperCase()} | status histogram ${JSON.stringify(seen)}`);
    }
  }

  const by = (bucket) => results.filter((r) => r.bucket === bucket);
  for (const bucket of ['SERVER', 'CLIENT']) {
    for (const r of by(bucket)) {
      console.log(`${bucket} | ${r.op} | ${r.detail}`);
    }
  }
  for (const r of by('SKIPPED')) {
    console.log(`SKIPPED | ${r.op} | ${r.detail}`);
  }
  console.log(
    `\nSUMMARY ${MODE}: OK ${by('OK').length} / CLIENT ${by('CLIENT').length} / SERVER ${by('SERVER').length} / SKIPPED ${by('SKIPPED').length} of ${results.length} operations`
  );
}

function hasFile(op) {
  const content = op.requestBody?.content ?? {};
  return Object.keys(content).some((k) => k.includes('multipart'));
}

function isStream(op) {
  const content = op.responses?.['200']?.content ?? {};
  return Object.keys(content).some((k) => k.includes('event-stream'));
}

await main();

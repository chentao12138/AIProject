#!/usr/bin/env node
/**
 * OpenAPI contract drift gate.
 *
 * `src/generated/api.d.ts` and `src/generated/openapi.json` are committed
 * artifacts of a RUNNING server (see generate-api.mjs). Until now nothing
 * proved they still agreed with each other: a hand-edit of the generated
 * file, or a spec snapshot refreshed without regenerating the types, were
 * both invisible — and ADR-030's whole point is that no client drifts.
 *
 * Usage:
 *   node scripts/check-api.mjs            verify the committed pair
 *   node scripts/check-api.mjs --record   (re)baseline after an api:generate
 *   node scripts/check-api.mjs --live     also prove the snapshot equals a
 *                                         running server's /v3/api-docs
 *
 * The manifest records the SHA-256 of both committed files plus where and
 * when the spec came from. When openapi-typescript is installed locally the
 * check also regenerates the types from the committed spec and diffs them,
 * which catches a type file that was edited by hand.
 */
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKG_ROOT = resolve(__dirname, '..');
const SPEC_FILE = join(PKG_ROOT, 'src/generated/openapi.json');
const TYPES_FILE = join(PKG_ROOT, 'src/generated/api.d.ts');
const MANIFEST_FILE = join(PKG_ROOT, 'src/generated/manifest.json');
const GENERATOR = join(PKG_ROOT, 'node_modules/openapi-typescript/bin/cli.js');

/**
 * Hash content with CRLF folded to LF.
 *
 * `core.autocrlf=true` on a Windows checkout rewrites these artifacts to CRLF
 * while openapi-typescript emits LF, so a byte-exact hash made the gate fail
 * on a snapshot that had not drifted at all. Line endings are not content:
 * any real edit to the spec or the generated types still changes this hash.
 */
const sha256 = (bytes) =>
  createHash('sha256')
    .update(bytes.toString('utf8').replace(/\r\n/g, '\n'))
    .digest('hex');

function fail(message) {
  console.error(`[api:check] FAILED: ${message}`);
  process.exit(1);
}

/**
 * Canonical form of a spec document: keys sorted, whitespace removed.
 *
 * `--live` compares the committed snapshot against a running server. Byte
 * equality would be a lie — the generator pretty-prints, springdoc's key order
 * can shift between builds — so the comparison runs on the document's meaning.
 */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(',')}}`;
  }
  return JSON.stringify(value);
}

/**
 * Where two canonical forms first part ways, as a bounded excerpt.
 *
 * Without this the `--live` failure could only say "the meaning differs", and
 * the way to find out was to fetch the document by hand and diff it — which is
 * how a genuine 198-path regeneration looked broken for several rounds: the
 * path sets matched, so the message gave no clue where the documents did not.
 */
function firstCanonicalDiff(a, b, window = 200) {
  const ca = canonical(a);
  const cb = canonical(b);
  if (ca === cb) return 'the canonical forms are equal (so the hash mismatch is internal)';
  let i = 0;
  while (i < ca.length && i < cb.length && ca[i] === cb[i]) i += 1;
  const from = Math.max(0, i - window);
  return (
    `at char ${i} — committed ${JSON.stringify(ca.slice(from, i + window))} ` +
    `vs live ${JSON.stringify(cb.slice(from, i + window))}`
  );
}

/**
 * `--live`: the committed snapshot must still equal what the server publishes.
 *
 * `api:check` proves openapi.json and api.d.ts agree with EACH OTHER, which is
 * no help when both are a faithful copy of an outdated server. The exam form
 * shipped a `durationMinutes` field the server never bound — the snapshot said
 * it existed, the types said it existed, tsc was happy, and every exam was
 * silently created with no time limit. Only a comparison against the live
 * server can see that, so this mode exists and `docs/SMOKE-INSTRUCTIONS.md`
 * tells you to run it whenever the backend is reachable.
 */
/**
 * `--live`: the committed snapshot must still equal what a running server.
 */
async function checkAgainstLiveServer(spec) {
  const url =
    process.env.OPENAPI_URL ??
    'http://localhost:8080/v3/api-docs';
  let live;
  try {
    const response = await fetch(url);
    if (!response.ok) {
      fail(`--live: GET ${url} returned HTTP ${response.status}`);
    }
    live = await response.json();
  } catch (error) {
    fail(`--live: cannot read ${url} — ${error.message}`);
  }

  // Refuse to compare against a foreign server (the same anchor the generator
  // checks): matching some other project's doc would report a 100% drift and
  // invite a wholesale clobber.
  const paths = Object.keys(live.paths ?? {});
  for (const anchor of ['/api/v1/spaces', '/api/v1/auth/login']) {
    if (!paths.includes(anchor)) {
      fail(
        `--live: ${url} has no ${anchor} — that is not the AIStudy server, ` +
          'so its document says nothing about this snapshot.'
      );
    }
  }

  // `servers[].url` is springdoc echoing the Host it was asked for, so a
  // document fetched through `localhost` differs from the same document fetched
  // through `127.0.0.1` — or from one recorded while the server sat on another
  // port — without any contract changing. Comparing it would make this gate
  // permanently red on the machine it is meant to protect, so the comparison is
  // of everything except that field.
  const comparable = (doc) => {
    const { servers, ...rest } = doc ?? {};
    return rest;
  };
  const liveDoc = comparable(live);
  const committedDoc = comparable(spec);

  const liveHash = sha256(canonical(liveDoc));
  const committedHash = sha256(canonical(committedDoc));
  if (liveHash !== committedHash) {
    const livePaths = new Set(paths);
    const committedPaths = new Set(Object.keys(spec.paths ?? {}));
    const added = [...livePaths].filter((p) => !committedPaths.has(p));
    const removed = [...committedPaths].filter((p) => !livePaths.has(p));
    fail(
      `the committed snapshot is not what ${url} publishes (canonical spec ` +
        `differs, ignoring servers[].url). Paths added by the server: ` +
        `${added.length ? added.join(', ') : 'none'}. ` +
        `Paths removed: ${removed.length ? removed.join(', ') : 'none'}. ` +
        `Committed ${SPEC_FILE}: ${committedPaths.size} paths, canonical ` +
        `${canonical(committedDoc).length} chars. Live: ${livePaths.size} paths, canonical ` +
        `${canonical(liveDoc).length} chars. First canonical difference: ` +
        `${firstCanonicalDiff(committedDoc, liveDoc)}. ` +
        'Field-level changes are not listed above — run `npm run api:generate` ' +
        'against this server, then `npm run typecheck`: the derived request ' +
        'and response types are what report renamed or added fields.'
    );
  }
  console.log(`[api:check] committed snapshot equals ${url}`);
}

for (const file of [SPEC_FILE, TYPES_FILE]) {
  if (!existsSync(file)) {
    fail(`${file} is missing — run \`npm run api:generate\` with the server up`);
  }
}

const specBytes = readFileSync(SPEC_FILE);
const typesBytes = readFileSync(TYPES_FILE);
const specHash = sha256(specBytes);
const typesHash = sha256(typesBytes);

let spec;
try {
  spec = JSON.parse(specBytes.toString('utf8'));
} catch (error) {
  fail(`openapi.json is not valid JSON: ${error.message}`);
}

if (process.argv.includes('--record')) {
  const manifest = {
    generatedFrom: {
      url: process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs',
      openapi: spec.openapi,
      // springdoc leaves this as "v0" unless api.info.version is configured;
      // the hash is what actually identifies the snapshot.
      infoVersion: spec.info?.version ?? null,
    },
    recordedAt: new Date().toISOString(),
    files: {
      'openapi.json': `sha256-${specHash}`,
      'api.d.ts': `sha256-${typesHash}`,
    },
  };
  writeFileSync(MANIFEST_FILE, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`[api:check] recorded baseline for sha256-${specHash.slice(0, 12)}`);
  process.exit(0);
}

const manifest = existsSync(MANIFEST_FILE)
  ? JSON.parse(readFileSync(MANIFEST_FILE, 'utf8'))
  : fail(
      'src/generated/manifest.json is missing — run `node scripts/check-api.mjs --record` ' +
        'once after a genuine api:generate, then keep it committed'
    );

if (manifest.files?.['openapi.json'] !== `sha256-${specHash}`) {
  fail(
    'openapi.json changed without the types being regenerated. ' +
      'Run `npm run api:generate` (server up) or `node scripts/check-api.mjs --record` ' +
      'if this snapshot came from a genuine regeneration.'
  );
}
if (manifest.files?.['api.d.ts'] !== `sha256-${typesHash}`) {
  fail(
    'src/generated/api.d.ts does not match the recorded baseline — it is a ' +
      'generated file; never hand-edit it. Regenerate with `npm run api:generate`.'
  );
}

if (!existsSync(GENERATOR)) {
  console.log(
    '[api:check] hashes match; type regeneration not re-checked ' +
      '(openapi-typescript is not installed — run `npm install` in packages/api-client)'
  );
  if (process.argv.includes('--live')) await checkAgainstLiveServer(spec);
  process.exit(0);
}

const tempDir = mkdtempSync(join(tmpdir(), 'aistudy-api-'));
const outFile = join(tempDir, 'api.d.ts');
try {
  execFileSync(process.execPath, [GENERATOR, SPEC_FILE, '--output', outFile], {
    stdio: 'inherit',
  });
  const fresh = readFileSync(outFile, 'utf8');
  const committed = typesBytes.toString('utf8');
  if (sha256(fresh) !== sha256(committed)) {
    fail(
      'api.d.ts is not what openapi.json generates — commit the output of ' +
        '`npm run api:generate`.'
    );
  }
  console.log('[api:check] openapi.json and api.d.ts are in sync');
  if (process.argv.includes('--live')) await checkAgainstLiveServer(spec);
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}

#!/usr/bin/env node
/**
 * Shared OpenAPI → TypeScript types generation script.
 *
 * Fetches the live OpenAPI JSON from the running AIStudy server and
 * generates typed API definitions with openapi-typescript.
 *
 * Usage:
 *   npm run api:generate
 *
 * Environment:
 *   OPENAPI_URL   default http://localhost:8080/v3/api-docs
 *
 * The generated file is:
 *   src/generated/api.d.ts
 *
 * This script deliberately FAILS (non-zero exit) when the server is
 * not reachable or the response is not valid OpenAPI JSON — it never
 * fabricates a success. Run the Spring Boot server first:
 *
 *   cd server && .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=it
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OPENAPI_URL = process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs';
const OUT_DIR = join(__dirname, '..', 'src', 'generated');
const OUT_FILE = join(OUT_DIR, 'api.d.ts');
const TMP_FILE = join(OUT_DIR, 'openapi.json');

console.log(`[api:generate] fetching ${OPENAPI_URL} ...`);

/**
 * A refused connection must read as the documented failure, not as an
 * unhandled rejection. `fetch` rejects instead of returning a response object,
 * so without this guard the script dumped a Node stack trace even though its
 * own header promises it "deliberately FAILS (non-zero exit) when the server is
 * not reachable".
 */
let response;
try {
  response = await fetch(OPENAPI_URL, {
    headers: { Accept: 'application/json' },
  });
} catch (error) {
  console.error(
    `[api:generate] FAILED: cannot reach ${OPENAPI_URL} (${error.cause?.code ?? error.message}). ` +
      'Is the AIStudy server running, and is that the right port? ' +
      'Another service on this port would be rejected below anyway. ' +
      'Not writing any generated file.'
  );
  process.exit(1);
}
if (!response.ok) {
  console.error(
    `[api:generate] FAILED: HTTP ${response.status} from ${OPENAPI_URL}. ` +
      'Is the Spring Boot server running? Not writing any generated file.'
  );
  process.exit(1);
}

const openapiJson = await response.text();

/**
 * The document must be AIStudy's, not merely "some OpenAPI".
 *
 * A regeneration pulled whatever Spring Boot service happened to own port 8080
 * and overwrote the contract with 124 `/api/identity/...` paths: the previous
 * check only asked whether the payload looked like an OpenAPI document, which
 * any Spring app satisfies, and it ran after nothing had been written only by
 * luck of ordering. Anchoring on paths this client cannot function without
 * turns a silent clobber into a refusal that names the intruder.
 */
let doc;
try {
  doc = JSON.parse(openapiJson);
} catch (error) {
  console.error(
    `[api:generate] FAILED: response is not valid JSON (${error.message}). ` +
      'Not writing any generated file.'
  );
  process.exit(1);
}
if (!doc.openapi || !doc.paths || typeof doc.paths !== 'object') {
  console.error(
    '[api:generate] FAILED: response has no openapi/paths members. ' +
      'Not writing any generated file.'
  );
  process.exit(1);
}

const ANCHOR_PATHS = ['/api/v1/spaces', '/api/v1/auth/login'];
const missingAnchors = ANCHOR_PATHS.filter((path) => !doc.paths[path]);
if (missingAnchors.length > 0) {
  console.error(
    `[api:generate] FAILED: ${OPENAPI_URL} is not the AIStudy API — it has none ` +
      `of ${missingAnchors.join(', ')} (title: ${doc.info?.title ?? 'unknown'}, ` +
      `${Object.keys(doc.paths).length} paths). Another service probably owns ` +
      'this port; pass OPENAPI_URL pointing at AIStudy. Not writing any file.'
  );
  process.exit(1);
}

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(TMP_FILE, openapiJson, 'utf8');

console.log('[api:generate] running openapi-typescript ...');
try {
  execFileSync(
    process.execPath,
    [
      join(__dirname, '..', 'node_modules', 'openapi-typescript', 'bin', 'cli.js'),
      TMP_FILE,
      '--output',
      OUT_FILE,
    ],
    { stdio: 'inherit' }
  );
} catch (err) {
  console.error('[api:generate] FAILED: openapi-typescript exited non-zero.', err);
  process.exit(1);
}

// Record what the committed pair is derived from, so `check-api.mjs` can
// tell a refreshed spec, a hand-edited type file and an untouched baseline
// apart without contacting the server.
const manifest = {
  generatedFrom: {
    url: OPENAPI_URL,
    openapi: doc.openapi ?? null,
    // springdoc leaves this as "v0" unless api.info.version is configured;
    // the hash is what actually identifies the snapshot.
    infoVersion: doc.info?.version ?? null,
  },
  recordedAt: new Date().toISOString(),
  files: {
    'openapi.json': `sha256-${createHash('sha256').update(readFileSync(TMP_FILE)).digest('hex')}`,
    'api.d.ts': `sha256-${createHash('sha256').update(readFileSync(OUT_FILE)).digest('hex')}`,
  },
};
writeFileSync(
  join(OUT_DIR, 'manifest.json'),
  `${JSON.stringify(manifest, null, 2)}
`,
  'utf8'
);

console.log(`[api:generate] DONE: ${OUT_FILE}`);

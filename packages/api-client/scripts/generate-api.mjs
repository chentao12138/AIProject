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
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OPENAPI_URL = process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs';
const OUT_DIR = join(__dirname, '..', 'src', 'generated');
const OUT_FILE = join(OUT_DIR, 'api.d.ts');
const TMP_FILE = join(OUT_DIR, 'openapi.json');

console.log(`[api:generate] fetching ${OPENAPI_URL} ...`);

const response = await fetch(OPENAPI_URL, {
  headers: { Accept: 'application/json' },
});
if (!response.ok) {
  console.error(
    `[api:generate] FAILED: HTTP ${response.status} from ${OPENAPI_URL}. ` +
      'Is the Spring Boot server running? Not writing any generated file.'
  );
  process.exit(1);
}

const openapiJson = await response.text();

// Cheap sanity check: must look like an OpenAPI document.
if (!openapiJson.includes('"openapi"') || !openapiJson.includes('"paths"')) {
  console.error(
    '[api:generate] FAILED: response does not look like an OpenAPI document. ' +
      'Not writing any generated file.'
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

console.log(`[api:generate] DONE: ${OUT_FILE}`);

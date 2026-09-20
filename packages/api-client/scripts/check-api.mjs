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

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

function fail(message) {
  console.error(`[api:check] FAILED: ${message}`);
  process.exit(1);
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
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}

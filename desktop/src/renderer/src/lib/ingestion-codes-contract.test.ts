/**
 * Ingestion error-code contract guard (A2.3 / BUSINESS-005).
 *
 * The server promises stable machine-readable codes (`IngestionErrorCode`)
 * plus a safe message, and the renderer translates each code into Chinese copy.
 * That only holds while the two vocabularies agree: a code the server adds and
 * we do not know falls through to echoing the backend's `errorMessage`, which
 * is English — the exact failure mode the checklist forbids for diagnostics.
 *
 * Reads the Java enum from this repository, like the other contract guards, and
 * skips when the server sources are not next to this checkout.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  INGESTION_ERROR_COPY,
  INGESTION_GENERIC_COPY,
  describeIngestionErrorCode,
} from './media-policy';

const ENUM_PATH = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'java',
  'com',
  'aistudy',
  'server',
  'ingestion',
  'job',
  'service',
  'IngestionErrorCode.java'
);

const enumExists = existsSync(ENUM_PATH);
const enumSource = enumExists ? readFileSync(ENUM_PATH, 'utf8') : '';

/** `NAME("NAME"),` declarations, ignoring javadoc and the type line. */
function serverCodes(source: string): string[] {
  const codes = new Set<string>();
  for (const match of source.matchAll(/^\s{4}([A-Z][A-Z_0-9]+)\(/gm)) {
    codes.add(match[1]);
  }
  return [...codes].sort();
}

describe.skipIf(!enumExists)('ingestion error codes match the server enum', () => {
  const codes = serverCodes(enumSource);

  it('finds the codes in the enum at all', () => {
    // Guards against the regex silently matching nothing and the assertions
    // below passing vacuously.
    expect(codes.length).toBeGreaterThan(5);
  });

  it('translates every code the server can report', () => {
    const untranslated = codes.filter(
      (code) =>
        describeIngestionErrorCode(code, null) === INGESTION_GENERIC_COPY ||
        !(code in INGESTION_ERROR_COPY)
    );
    expect(untranslated).toEqual([]);
  });

  it('keeps no copy for a code the server no longer declares', () => {
    const stale = Object.keys(INGESTION_ERROR_COPY).filter(
      (code) => !codes.includes(code)
    );
    expect(stale).toEqual([]);
  });

  it('never shows an unknown code as a bare backend message', () => {
    // The fallback may echo `errorMessage` for diagnostics, but an unlisted
    // code with no message must stay neutral rather than blank.
    expect(describeIngestionErrorCode('SOMETHING_NEW', null)).toBe(
      INGESTION_GENERIC_COPY
    );
  });
});

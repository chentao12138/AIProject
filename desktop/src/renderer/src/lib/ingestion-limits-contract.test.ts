/**
 * Ingestion limit contract guard (A2.3 / FE-002A upload precheck).
 *
 * `media-policy.ts` refuses an upload client-side before it wastes a request,
 * which is only honest while its numbers are the server's. The ceilings are
 * configurable per deployment (`AISTUDY_INGESTION_*`), so this reads the
 * defaults straight out of `server/src/main/resources/application.yml` and
 * fails if the two sides drift — the same trick `question-type-contract.test.ts`
 * uses for the wire vocabulary.
 *
 * Skips when the server sources are not next to this checkout.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { classifyMediaFile } from './media-policy';

const SERVER_CONFIG = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'resources',
  'application.yml'
);

const configExists = existsSync(SERVER_CONFIG);
const configText = configExists ? readFileSync(SERVER_CONFIG, 'utf8') : '';

/** Spring `DataSize` ("64MB", "1024", "2GB") to bytes. */
function dataSizeToBytes(value: string): number {
  const match = /^(\d+)\s*(KB|MB|GB|TB)?$/i.exec(value.trim());
  if (!match) {
    throw new Error(`unparsable DataSize: ${value}`);
  }
  const amount = Number(match[1]);
  const unit = (match[2] ?? 'B').toUpperCase();
  const multipliers: Record<string, number> = {
    B: 1,
    KB: 1024,
    MB: 1024 * 1024,
    GB: 1024 * 1024 * 1024,
    TB: 1024 * 1024 * 1024 * 1024,
  };
  return amount * multipliers[unit];
}

function serverDefault(envKey: string): number {
  const pattern = new RegExp(`\\$\\{${envKey}:([^}]+)\\}`);
  const match = pattern.exec(configText);
  if (!match) {
    throw new Error(`${envKey} default not found in application.yml`);
  }
  return dataSizeToBytes(match[1]);
}

describe.skipIf(!configExists)('ingestion limits match the server defaults', () => {
  it('caps PDF at the server ingestion pdf ceiling', () => {
    expect(classifyMediaFile({ name: 'deck.pdf' }).maxBytes).toBe(
      serverDefault('AISTUDY_INGESTION_PDF_MAX_BYTES')
    );
  });

  it('caps images at the server ingestion image ceiling', () => {
    const image = serverDefault('AISTUDY_INGESTION_IMAGE_MAX_BYTES');
    expect(classifyMediaFile({ name: 'scan.png' }).maxBytes).toBe(image);
    expect(classifyMediaFile({ name: 'photo.JPG' }).maxBytes).toBe(image);
  });

  it('caps text and markdown at the server document ceiling', () => {
    const text = serverDefault('AISTUDY_INGESTION_TEXT_MAX_DOCUMENT_BYTES');
    expect(classifyMediaFile({ name: 'notes.txt' }).maxBytes).toBe(text);
    expect(classifyMediaFile({ name: 'readme.md' }).maxBytes).toBe(text);
  });
});

describe.skipIf(!configExists)('the servlet multipart envelope is not tighter', () => {
  /**
   * A per-type ceiling above the container limit could never be reached, so
   * the client would let through a file the server rejects for a different
   * reason than the one the copy states.
   */
  it('keeps every ingestion ceiling under max-file-size', () => {
    const maxFile = serverDefault('AISTUDY_UPLOAD_MAX_FILE_SIZE');
    for (const key of [
      'AISTUDY_INGESTION_PDF_MAX_BYTES',
      'AISTUDY_INGESTION_IMAGE_MAX_BYTES',
      'AISTUDY_INGESTION_TEXT_MAX_DOCUMENT_BYTES',
    ]) {
      expect(serverDefault(key)).toBeLessThanOrEqual(maxFile);
    }
  });
});

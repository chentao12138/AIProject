/**
 * lib/ids.ts edge cases (FE-001.5 PHASE 3).
 *
 * The full matrix from the hardening plan: undefined, null, NaN,
 * Infinity, 0, negatives, numeric strings, garbage strings — plus the
 * browser-route realities (hex strings, exponents, leading zeros,
 * huge values that overflow Number.isSafeInteger).
 */

import { describe, expect, it } from 'vitest';
import {
  isPositiveId,
  parseOptionalPositiveId,
  parsePositiveIdParam,
  parseOptionalInteger,
} from './ids';

describe('isPositiveId', () => {
  it('accepts finite positive integers', () => {
    expect(isPositiveId(1)).toBe(true);
    expect(isPositiveId(42)).toBe(true);
    expect(isPositiveId(Number.MAX_SAFE_INTEGER)).toBe(true);
  });

  it('rejects undefined / null', () => {
    expect(isPositiveId(undefined)).toBe(false);
    expect(isPositiveId(null)).toBe(false);
  });

  it('rejects NaN and Infinity', () => {
    expect(isPositiveId(NaN)).toBe(false);
    expect(isPositiveId(Infinity)).toBe(false);
    expect(isPositiveId(-Infinity)).toBe(false);
  });

  it('rejects zero, negatives and floats', () => {
    expect(isPositiveId(0)).toBe(false);
    expect(isPositiveId(-1)).toBe(false);
    expect(isPositiveId(-42)).toBe(false);
    expect(isPositiveId(1.5)).toBe(false);
  });

  it('rejects non-number values', () => {
    expect(isPositiveId('1')).toBe(false);
    expect(isPositiveId(true)).toBe(false);
    expect(isPositiveId({})).toBe(false);
    expect(isPositiveId([])).toBe(false);
  });
});

describe('parsePositiveIdParam', () => {
  it('parses plain digit strings', () => {
    expect(parsePositiveIdParam('1')).toBe(1);
    expect(parsePositiveIdParam('42')).toBe(42);
  });

  it('rejects undefined and empty strings', () => {
    expect(parsePositiveIdParam(undefined)).toBe(null);
    expect(parsePositiveIdParam('')).toBe(null);
  });

  it('rejects zero and negative-looking strings', () => {
    expect(parsePositiveIdParam('0')).toBe(null);
    expect(parsePositiveIdParam('-1')).toBe(null);
    expect(parsePositiveIdParam('-42')).toBe(null);
  });

  it('rejects garbage strings', () => {
    expect(parsePositiveIdParam('abc')).toBe(null);
    expect(parsePositiveIdParam('12abc')).toBe(null);
    expect(parsePositiveIdParam('1.5')).toBe(null);
    expect(parsePositiveIdParam('1e3')).toBe(null);
    expect(parsePositiveIdParam(' 12 ')).toBe(null);
  });

  it('rejects hex/octal/binary-looking strings that Number() would coerce', () => {
    expect(parsePositiveIdParam('0x10')).toBe(null);
    expect(parsePositiveIdParam('0b101')).toBe(null);
    expect(parsePositiveIdParam('0o17')).toBe(null);
  });

  it('accepts leading zeros and normalizes them', () => {
    expect(parsePositiveIdParam('007')).toBe(7);
  });

  it('rejects values that overflow the safe integer range', () => {
    expect(parsePositiveIdParam('99999999999999999999')).toBe(null);
    expect(parsePositiveIdParam(String(Number.MAX_SAFE_INTEGER))).toBe(
      Number.MAX_SAFE_INTEGER
    );
  });

  it('rejects NaN and Infinity literals', () => {
    expect(parsePositiveIdParam('NaN')).toBe(null);
    expect(parsePositiveIdParam('Infinity')).toBe(null);
  });
});

describe('parseOptionalInteger', () => {
  it('parses plain and signed integers', () => {
    expect(parseOptionalInteger('3')).toBe(3);
    expect(parseOptionalInteger('-5')).toBe(-5);
    expect(parseOptionalInteger(' 7 ')).toBe(7);
  });

  it('returns undefined for empty / whitespace-only input', () => {
    expect(parseOptionalInteger('')).toBe(undefined);
    expect(parseOptionalInteger('   ')).toBe(undefined);
  });

  it('returns undefined for garbage, hex, floats and exponents', () => {
    expect(parseOptionalInteger('abc')).toBe(undefined);
    expect(parseOptionalInteger('0x10')).toBe(undefined);
    expect(parseOptionalInteger('1.5')).toBe(undefined);
    expect(parseOptionalInteger('1e3')).toBe(undefined);
    expect(parseOptionalInteger('NaN')).toBe(undefined);
    expect(parseOptionalInteger('Infinity')).toBe(undefined);
  });

  it('returns undefined for safe-integer overflow', () => {
    expect(parseOptionalInteger('99999999999999999999')).toBe(undefined);
  });
});

describe('parseOptionalPositiveId (FIX-01 — entity-id semantics)', () => {
  it('parses plain positive safe integers', () => {
    expect(parseOptionalPositiveId('3')).toBe(3);
    expect(parseOptionalPositiveId(' 7 ')).toBe(7);
    expect(parseOptionalPositiveId(String(Number.MAX_SAFE_INTEGER))).toBe(
      Number.MAX_SAFE_INTEGER
    );
  });

  it('returns undefined for empty / whitespace-only input', () => {
    expect(parseOptionalPositiveId('')).toBe(undefined);
    expect(parseOptionalPositiveId('   ')).toBe(undefined);
  });

  it('rejects zero and negatives (unlike parseOptionalInteger)', () => {
    expect(parseOptionalPositiveId('0')).toBe(undefined);
    expect(parseOptionalPositiveId('-5')).toBe(undefined);
    expect(parseOptionalPositiveId('-0')).toBe(undefined);
  });

  it('rejects floats, NaN and Infinity', () => {
    expect(parseOptionalPositiveId('1.5')).toBe(undefined);
    expect(parseOptionalPositiveId('NaN')).toBe(undefined);
    expect(parseOptionalPositiveId('Infinity')).toBe(undefined);
  });

  it('rejects hex and exponent notation', () => {
    expect(parseOptionalPositiveId('0x10')).toBe(undefined);
    expect(parseOptionalPositiveId('0b101')).toBe(undefined);
    expect(parseOptionalPositiveId('0o17')).toBe(undefined);
    expect(parseOptionalPositiveId('1e3')).toBe(undefined);
  });

  it('rejects garbage strings', () => {
    expect(parseOptionalPositiveId('abc')).toBe(undefined);
    expect(parseOptionalPositiveId('12abc')).toBe(undefined);
    expect(parseOptionalPositiveId('+5')).toBe(undefined);
  });

  it('rejects safe-integer overflow', () => {
    expect(parseOptionalPositiveId('99999999999999999999')).toBe(undefined);
  });
});

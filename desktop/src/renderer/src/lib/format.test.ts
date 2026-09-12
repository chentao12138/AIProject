/**
 * formatBytes tests (FE-002A PHASE 11).
 */

import { describe, expect, it } from 'vitest';
import { formatBytes } from './format';

describe('formatBytes', () => {
  it('returns — for undefined / null', () => {
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(null)).toBe('—');
  });

  it('returns — for invalid and negative values', () => {
    expect(formatBytes(NaN)).toBe('—');
    expect(formatBytes(Infinity)).toBe('—');
    expect(formatBytes(-1)).toBe('—');
    expect(formatBytes(-1024)).toBe('—');
  });

  it('formats 0 as 0 B', () => {
    expect(formatBytes(0)).toBe('0 B');
  });

  it('formats byte values', () => {
    expect(formatBytes(1)).toBe('1 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1023)).toBe('1023 B');
  });

  it('formats KB values', () => {
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(102400)).toBe('100 KB');
  });

  it('formats MB values', () => {
    expect(formatBytes(1024 * 1024)).toBe('1 MB');
    expect(formatBytes(1024 * 1024 * 5.5)).toBe('5.5 MB');
  });

  it('formats GB values', () => {
    expect(formatBytes(1024 * 1024 * 1024)).toBe('1 GB');
    expect(formatBytes(1024 * 1024 * 1024 * 2.25)).toBe('2.3 GB');
  });
});

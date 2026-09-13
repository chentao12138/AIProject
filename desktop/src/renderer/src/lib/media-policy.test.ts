/**
 * Media policy tests (FE-002B Batch C contract).
 *
 * Classification, size pre-check, failure-code mapping, and
 * success-state detection for PDF/Image — all against the stable
 * backend contract, no invented enums.
 */

import { describe, expect, it } from 'vitest';
import {
  MEDIA_ACCEPT_ATTR,
  classifyMediaFile,
  describeIngestionErrorCode,
  isImageOnlyPdfSuccess,
  isStandaloneImageSuccess,
  precheckFileSize,
} from './media-policy';

describe('classifyMediaFile', () => {
  it('classifies PDF by extension (case-insensitive)', () => {
    expect(classifyMediaFile({ name: 'a.pdf' }).kind).toBe('pdf');
    expect(classifyMediaFile({ name: 'A.PDF' }).kind).toBe('pdf');
  });

  it('classifies PNG and JPEG', () => {
    expect(classifyMediaFile({ name: 'a.png' }).kind).toBe('image');
    expect(classifyMediaFile({ name: 'a.jpg' }).kind).toBe('image');
    expect(classifyMediaFile({ name: 'a.JPEG' }).kind).toBe('image');
  });

  it('classifies TXT and Markdown', () => {
    expect(classifyMediaFile({ name: 'a.txt' }).kind).toBe('text');
    expect(classifyMediaFile({ name: 'a.md' }).kind).toBe('markdown');
    expect(classifyMediaFile({ name: 'a.markdown' }).kind).toBe('markdown');
  });

  it('returns unknown for unsupported extensions', () => {
    expect(classifyMediaFile({ name: 'a.webp' }).kind).toBe('unknown');
    expect(classifyMediaFile({ name: 'a.gif' }).kind).toBe('unknown');
    expect(classifyMediaFile({ name: 'noext' }).kind).toBe('unknown');
  });
});

describe('precheckFileSize', () => {
  it('allows PDF under 100MB', () => {
    expect(precheckFileSize({ name: 'a.pdf', size: 50 * 1024 * 1024 })).toBeNull();
  });

  it('rejects PDF over 100MB', () => {
    const err = precheckFileSize({ name: 'a.pdf', size: 101 * 1024 * 1024 });
    expect(err).toContain('PDF');
    expect(err).toContain('100 MB');
  });

  it('allows image under 50MB', () => {
    expect(precheckFileSize({ name: 'a.png', size: 40 * 1024 * 1024 })).toBeNull();
  });

  it('rejects image over 50MB', () => {
    const err = precheckFileSize({ name: 'a.jpg', size: 51 * 1024 * 1024 });
    expect(err).toContain('JPEG');
    expect(err).toContain('50 MB');
  });

  it('rejects TXT over 64MB', () => {
    const err = precheckFileSize({ name: 'a.txt', size: 65 * 1024 * 1024 });
    expect(err).toContain('TXT');
  });

  it('allows unknown types without pre-check', () => {
    expect(precheckFileSize({ name: 'a.bin', size: 999 * 1024 * 1024 })).toBeNull();
  });
});

describe('describeIngestionErrorCode', () => {
  it('maps PDF_ENCRYPTED to clear Chinese copy', () => {
    const msg = describeIngestionErrorCode('PDF_ENCRYPTED');
    expect(msg).toContain('加密');
    expect(msg).not.toContain('PDF_ENCRYPTED');
  });

  it('maps IMAGE_TOO_LARGE', () => {
    expect(describeIngestionErrorCode('IMAGE_TOO_LARGE')).toContain('50MB');
  });

  it('maps PDF_PAGE_LIMIT_EXCEEDED', () => {
    expect(describeIngestionErrorCode('PDF_PAGE_LIMIT_EXCEEDED')).toContain('200');
  });

  it('maps UNSUPPORTED_FORMAT', () => {
    expect(describeIngestionErrorCode('UNSUPPORTED_FORMAT')).toContain('不支持');
  });

  it('falls back to safe message for unknown codes', () => {
    expect(describeIngestionErrorCode('FUTURE_CODE_XYZ')).toContain('失败');
  });

  it('falls back when no code and no safe message', () => {
    expect(describeIngestionErrorCode(null, null)).toContain('失败');
  });

  it('uses short safe errorMessage when no code matches', () => {
    expect(describeIngestionErrorCode(null, 'custom safe msg')).toBe(
      'custom safe msg'
    );
  });

  it('rejects long/multiline errorMessage (no stack leak)', () => {
    const long = 'x'.repeat(300);
    expect(describeIngestionErrorCode(null, long)).not.toBe(long);
    expect(describeIngestionErrorCode(null, 'line1\nline2')).not.toContain(
      'line1'
    );
  });
});

describe('isStandaloneImageSuccess', () => {
  it('true for SUCCEEDED + 1 IMAGE page + 0 blocks', () => {
    expect(
      isStandaloneImageSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'IMAGE', extractedText: null }],
        blockCount: 0,
      })
    ).toBe(true);
  });

  it('false when blocks exist', () => {
    expect(
      isStandaloneImageSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'IMAGE' }],
        blockCount: 1,
      })
    ).toBe(false);
  });

  it('false when not SUCCEEDED', () => {
    expect(
      isStandaloneImageSuccess({
        status: 'RUNNING',
        pages: [{ pageType: 'IMAGE' }],
        blockCount: 0,
      })
    ).toBe(false);
  });

  it('false when page is not IMAGE', () => {
    expect(
      isStandaloneImageSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'BODY' }],
        blockCount: 0,
      })
    ).toBe(false);
  });
});

describe('isImageOnlyPdfSuccess', () => {
  it('true for SUCCEEDED + BODY pages with empty text + 0 blocks', () => {
    expect(
      isImageOnlyPdfSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'BODY', extractedText: '  ' }],
        blockCount: 0,
      })
    ).toBe(true);
  });

  it('false when blocks exist', () => {
    expect(
      isImageOnlyPdfSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'BODY', extractedText: '' }],
        blockCount: 2,
      })
    ).toBe(false);
  });

  it('false when text is present', () => {
    expect(
      isImageOnlyPdfSuccess({
        status: 'SUCCEEDED',
        pages: [{ pageType: 'BODY', extractedText: 'hello' }],
        blockCount: 0,
      })
    ).toBe(false);
  });
});

describe('MEDIA_ACCEPT_ATTR', () => {
  it('includes all Batch C stable extensions', () => {
    for (const ext of ['.txt', '.md', '.pdf', '.png', '.jpg', '.jpeg']) {
      expect(MEDIA_ACCEPT_ATTR).toContain(ext);
    }
  });

  it('does not include WebP', () => {
    expect(MEDIA_ACCEPT_ATTR).not.toContain('webp');
  });
});

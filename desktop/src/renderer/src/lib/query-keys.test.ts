/**
 * Cache-key semantics (FE-ARCH stage C).
 *
 * Two failure modes these pin: an unresolved id must never be smuggled into
 * a key as `0`, and a mutation that invalidates a list must match the cached
 * entries of that list *with* filters applied.
 */

import { describe, expect, it } from 'vitest';
import { queryKeys } from './query-keys';

describe('queryKeys', () => {
  it('encodes an unresolved id as none, never as 0', () => {
    expect(queryKeys.sources(null)).toEqual(['sources', 'none']);
    expect(queryKeys.sources(null)).not.toEqual(['sources', 0]);
    expect(queryKeys.exam(7, null)).toEqual(['exam', 7, 'none']);
  });

  it('keeps a list key a prefix of its filtered variants so invalidation lands', () => {
    const unfiltered = queryKeys.questions(7);
    const filtered = queryKeys.questions(7, {
      status: 'PUBLISHED',
      questionType: 'SINGLE_CHOICE',
    });

    expect(unfiltered).toEqual(['questions', 7]);
    expect(filtered).toEqual([
      'questions',
      7,
      'status',
      'PUBLISHED',
      'questionType',
      'SINGLE_CHOICE',
    ]);
    // TanStack matches by comparing every index of the shorter key.
    expect(filtered.slice(0, unfiltered.length)).toEqual(unfiltered);
  });

  it('ignores filters that are absent or blank', () => {
    expect(
      queryKeys.questions(7, { status: '', questionType: undefined })
    ).toEqual(['questions', 7]);
  });

  it('keys search on the filters that change the result set', () => {
    expect(queryKeys.search(7, 'java', { types: 'QUESTION', page: 2, size: 20 }))
      .toEqual(['search', 7, 'java', 'types', 'QUESTION', 'page', 2, 'size', 20]);
    expect(queryKeys.search(7, 'java', { page: 0 })).toEqual([
      'search',
      7,
      'java',
      'page',
      0,
    ]);
  });

  it('separates paged content blocks from the unfiltered list', () => {
    expect(queryKeys.contentBlocks(7, 5)).toEqual(['content-blocks', 7, 5]);
    expect(queryKeys.contentBlocks(7, 5, 3)).toEqual([
      'content-blocks',
      7,
      5,
      'page',
      3,
    ]);
  });
});

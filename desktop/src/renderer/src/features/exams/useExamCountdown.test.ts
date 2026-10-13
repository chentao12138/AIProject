/**
 * Exam countdown (W06).
 *
 * The point of these tests is that the timer has exactly one source —
 * `ExamAttemptView.deadlineAt` — and that the expired state is reached without
 * the client ever deciding a deadline on its own.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import {
  deadlineTime,
  formatRemaining,
  remainingMs,
  useExamCountdown,
} from './useExamCountdown';

const DEADLINE = '2026-10-13T12:00:00';
const DEADLINE_MS = Date.parse(DEADLINE);

describe('remainingMs / deadlineTime', () => {
  it('returns null when the attempt has no deadline', () => {
    // `ExamAttemptService.start` leaves deadlineAt null for an exam without
    // timeLimitMinutes — the UI must then show no timer at all.
    expect(remainingMs(undefined, Date.now())).toBeNull();
    expect(remainingMs(null, Date.now())).toBeNull();
    expect(deadlineTime('not-a-date')).toBeNull();
  });

  it('counts down from the server value', () => {
    expect(remainingMs(DEADLINE, DEADLINE_MS - 60_000)).toBe(60_000);
  });

  it('clamps at zero instead of going negative', () => {
    expect(remainingMs(DEADLINE, DEADLINE_MS + 5_000)).toBe(0);
  });
});

describe('formatRemaining', () => {
  it('uses mm:ss under an hour', () => {
    expect(formatRemaining(9 * 60_000 + 5_000)).toBe('09:05');
    expect(formatRemaining(0)).toBe('00:00');
  });

  it('switches to h:mm:ss past an hour', () => {
    expect(formatRemaining(90 * 60_000)).toBe('1:30:00');
  });

  it('floors a partial second', () => {
    expect(formatRemaining(1_999)).toBe('00:01');
  });
});

describe('useExamCountdown', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(DEADLINE_MS - 10_000);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('ticks the remaining time down second by second', async () => {
    const { result } = renderHook(() => useExamCountdown(DEADLINE));

    expect(result.current.text).toBe('00:10');
    expect(result.current.expired).toBe(false);

    await act(async () => {
      vi.advanceTimersByTime(3_000);
    });

    expect(result.current.text).toBe('00:07');
  });

  it('flips to expired at the deadline and stops the timer', async () => {
    const { result } = renderHook(() => useExamCountdown(DEADLINE));

    await act(async () => {
      vi.advanceTimersByTime(10_000);
    });

    expect(result.current.expired).toBe(true);
    expect(result.current.remaining).toBe(0);

    await act(async () => {
      vi.advanceTimersByTime(5_000);
    });
    expect(result.current.remaining).toBe(0);
  });

  it('treats an already-past deadline as expired on mount', () => {
    vi.setSystemTime(DEADLINE_MS + 1_000);
    const { result } = renderHook(() => useExamCountdown(DEADLINE));

    expect(result.current.expired).toBe(true);
    expect(result.current.hasDeadline).toBe(true);
  });

  it('has no deadline state at all when the server sent none', () => {
    const { result } = renderHook(() => useExamCountdown(undefined));

    expect(result.current.hasDeadline).toBe(false);
    expect(result.current.text).toBeNull();
    expect(result.current.remaining).toBeNull();
    // "no deadline" must never be rendered as "expired".
    expect(result.current.expired).toBe(false);
  });
});

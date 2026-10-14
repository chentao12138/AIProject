/**
 * Exam countdown (W06).
 *
 * `ExamAttemptView.deadlineAt` used to be shown ONLY on the not-started
 * screen, so a candidate mid-exam had no idea how much time was left.
 *
 * The deadline is never invented here: every value derives from the server's
 * own `deadlineAt`, which `ExamAttemptService.start` sets as
 * `startedAt + exam.timeLimitMinutes` — and leaves NULL when the exam has no
 * time limit. No `deadlineAt` therefore means no timer and no remaining-time
 * copy at all; a locally computed deadline would disagree with the server and
 * could block or allow answers the backend decides otherwise.
 *
 * Expiry is handled by REFERRING to the server, not by copying its rules:
 * `answer` / `submit` / `getResult` all call `finalizeIfExpired` first, so the
 * next read flips the attempt to SUBMITTED and the result view takes over.
 * The UI only stops the candidate from typing into an expired paper meanwhile.
 */

import { useEffect, useState } from 'react';

/**
 * `deadlineAt` as a timestamp, or null when absent/unparsable.
 *
 * The backend serializes a `LocalDateTime` (`2026-10-13T21:30:00`), which
 * `Date.parse` reads in the LOCAL zone — the same reading `formatDateTime`
 * already applies repo-wide, so the countdown and the displayed deadline agree.
 */
export function deadlineTime(deadlineAt?: string | null): number | null {
  if (!deadlineAt) return null;
  const parsed = Date.parse(deadlineAt);
  return Number.isNaN(parsed) ? null : parsed;
}

/** Milliseconds left, clamped at 0. Null when there is no deadline at all. */
export function remainingMs(
  deadlineAt: string | null | undefined,
  nowMs: number
): number | null {
  const deadline = deadlineTime(deadlineAt);
  if (deadline === null) return null;
  return Math.max(0, deadline - nowMs);
}

/** `mm:ss` under an hour, else `h:mm:ss`. Negative input clamps to 00:00. */
export function formatRemaining(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  if (hours > 0) {
    return `${hours}:${pad(minutes)}:${pad(seconds)}`;
  }
  return `${pad(minutes)}:${pad(seconds)}`;
}

export interface ExamCountdown {
  /** False when the attempt carries no `deadlineAt` — render nothing then. */
  hasDeadline: boolean;
  /** Null without a deadline; otherwise clamped remaining milliseconds. */
  remaining: number | null;
  /** `mm:ss` / `h:mm:ss`, null without a deadline. */
  text: string | null;
  /** Deadline reached (or already reached on mount). */
  expired: boolean;
}

/** Ticks once a second while a live deadline remains; idle otherwise. */
export function useExamCountdown(deadlineAt?: string | null): ExamCountdown {
  const deadline = deadlineTime(deadlineAt);
  const [now, setNow] = useState(() => Date.now());

  const remaining = deadline === null ? null : Math.max(0, deadline - now);
  const expired = remaining !== null && remaining <= 0;

  useEffect(() => {
    if (deadline === null || expired) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [deadline, expired]);

  return {
    hasDeadline: deadline !== null,
    remaining,
    text: remaining === null ? null : formatRemaining(remaining),
    expired,
  };
}

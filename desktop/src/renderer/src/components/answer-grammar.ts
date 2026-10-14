/**
 * The ORDERING / MATCHING answer grammar, in one place.
 *
 * These live apart from `QuestionRenderer` for two reasons: the renderer is a
 * component module (mixing exports breaks fast refresh), and the answer is built
 * in more than one place — the widget the learner uses and the payload builders
 * in `features/practice` that turn a drafted answer into a request body. Both
 * must agree on the wire format, because the server grades by comparing what it
 * stored against what the client sent, and it is unforgiving:
 *
 *  - ORDERING: `orderingAnswer` is a list of **1-based** option positions
 *    (`AnswerPayloadView.orderingAnswer.items.minimum = 1`) and
 *    `QuestionAnswerEvaluator.gradeOrdering` compares it to the stored
 *    `correctOrder` with exact list equality. No partial credit, so a repeated or
 *    out-of-range index is never "mostly right" — it is refused before sending.
 *  - MATCHING: `matchingAnswer` is a JSON string of `{"left","right"}` objects
 *    and `gradeMatching` parses both sides into maps and compares them as sets.
 *    Order is irrelevant; a key that is not exactly `left`/`right` — or one extra
 *    key — makes a pair that can never match.
 */

/**
 * One MATCHING pair. The wire format is an array of these serialized, and the
 * server's grader compares the parsed maps for equality, so a pair with an
 * extra or renamed key can only ever be graded wrong — `parseMatchingPairs`
 * refuses to produce one.
 */
export interface MatchingPair {
  left: string;
  right: string;
}

/** 1 … count: the order the backend presented the options in. */
export function identityPositions(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index + 1);
}

/**
 * Read a drafted/stored `orderingAnswer` back into rows. Only whole positions
 * inside the question are kept, and a repeat is dropped: the contract demands
 * `minimum: 1` per item and the grader wants one permutation, so anything else
 * is shown as unanswered rather than submitted as a 400.
 */
export function normalizeOrderingAnswer(
  value: readonly number[] | undefined,
  count: number
): number[] {
  const seen = new Set<number>();
  const kept: number[] = [];
  for (const position of value ?? []) {
    if (!Number.isInteger(position) || position < 1 || position > count) continue;
    if (seen.has(position)) continue;
    seen.add(position);
    kept.push(position);
  }
  return kept;
}

/** Every position exactly once — what makes an ORDERING answer submittable. */
export function isCompleteOrdering(
  value: readonly number[] | undefined,
  count: number
): boolean {
  if (count <= 0) return false;
  return normalizeOrderingAnswer(value, count).length === count;
}

/**
 * The left / right pools of a MATCHING question. `OptionView` has no side
 * field, so the first half (rounded up) is the left column — the same split
 * the read side of the renderer shows.
 */
export function splitMatchingOptions<T extends { optionKey: string; content: string }>(
  options: readonly T[]
): { left: T[]; right: T[] } {
  const mid = Math.ceil(options.length / 2);
  return { left: options.slice(0, mid), right: options.slice(mid) };
}

function isPairShape(value: unknown): value is MatchingPair {
  if (typeof value !== 'object' || value === null) return false;
  const record = value as { left?: unknown; right?: unknown };
  const keys = Object.keys(record);
  // Exactly {left,right}: the grader compares maps, so a stray key — or a
  // renamed one — is a pair that can never match.
  return (
    keys.length === 2 &&
    typeof record.left === 'string' &&
    typeof record.right === 'string' &&
    record.left.trim() !== '' &&
    record.right.trim() !== ''
  );
}

/**
 * The one pair grammar the wire format allows, exposed for the callers that
 * build a payload out of a drafted `matchingAnswer` string. Anything that is
 * not a `{left,right}` map of non-blank strings comes back as null.
 */
export function readMatchingPair(value: unknown): MatchingPair | null {
  return isPairShape(value) ? { left: value.left, right: value.right } : null;
}

/**
 * Parse the wire string back into rows, keeping only pairs this question can
 * still express: known keys, a left item not yet paired, a non-blank right.
 * Malformed JSON reads as "no pairs yet" — the server rejects that string with
 * a 400, so it must never be re-sent just because something stored it.
 */
export function parseMatchingPairs(
  value: string | undefined,
  leftKeys: readonly string[],
  rightKeys: readonly string[]
): MatchingPair[] {
  const raw = value ?? '';
  if (raw.trim() === '') return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const knownLeft = new Set(leftKeys);
  const knownRight = new Set(rightKeys);
  const usedLeft = new Set<string>();
  const pairs: MatchingPair[] = [];
  for (const entry of parsed) {
    if (!isPairShape(entry)) continue;
    if (!knownLeft.has(entry.left) || !knownRight.has(entry.right)) continue;
    if (usedLeft.has(entry.left)) continue;
    usedLeft.add(entry.left);
    pairs.push({ left: entry.left, right: entry.right });
  }
  return pairs;
}

/** The wire value for a row model: `{left,right}` and nothing else. */
export function serializeMatchingPairs(pairs: readonly MatchingPair[]): string {
  return JSON.stringify(pairs.map((pair) => ({ left: pair.left, right: pair.right })));
}

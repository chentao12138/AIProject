/**
 * Numeric id guards (FE-001.5 PHASE 3).
 *
 * Generated OpenAPI ids are optional and arrive as JSON numbers. Every
 * navigation URL and every API call must be gated by these helpers so
 * the renderer can never build `/spaces/undefined`, `/knowledge/NaN`
 * or send a non-positive id to the backend.
 *
 * Rule: only finite positive INTEGER ids are navigable/queryable.
 */

/**
 * true only for a finite positive integer (the only valid entity id).
 * Rejects undefined, null, NaN, Infinity, 0, negatives, floats,
 * strings.
 */
export function isPositiveId(value: unknown): value is number {
  return (
    typeof value === 'number' && Number.isInteger(value) && value > 0
  );
}

/**
 * Parse a route param string into a positive integer id.
 *
 * Strict: only plain decimal digit strings (no sign, no exponent, no
 * hex like "0x10", no floats) that fit in a safe integer are accepted.
 * Leading zeros are normalized ("007" -> 7). Everything else returns
 * null.
 */
export function parsePositiveIdParam(value: string | undefined): number | null {
  if (value === undefined || !/^\d+$/.test(value)) {
    return null;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

/**
 * Parse an OPTIONAL form integer (sortOrder, categoryId, ...) strictly.
 *
 * Empty / whitespace-only / garbage / hex / floats / overflow -> undefined
 * (the field is omitted from the payload). Signed integers are accepted
 * (sortOrder may legitimately be negative).
 */
export function parseOptionalInteger(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return undefined;
  }
  if (!/^-?\d+$/.test(trimmed)) {
    return undefined;
  }
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

/**
 * Parse an OPTIONAL ENTITY-ID form field (parentId, categoryId, ...).
 *
 * Same strictness as parseOptionalInteger, PLUS the entity-id rule:
 * only a plain positive safe integer is a valid id. Rejects 0,
 * negatives, floats, NaN, Infinity, hex, exponents, garbage and
 * safe-integer overflow — all of which could otherwise be submitted as
 * a malformed parentId/categoryId.
 *
 * Empty / whitespace-only -> undefined (the field is omitted).
 */
export function parseOptionalPositiveId(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') {
    return undefined;
  }
  if (!/^\d+$/.test(trimmed)) {
    return undefined;
  }
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

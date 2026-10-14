/**
 * AI surface contract helpers: citation routing (A3.3 / G08) and message
 * context attachment (A3.2 / G07).
 *
 * Split out of `AiChatPage.tsx` so the page stays a component-only module
 * (Fast Refresh) and the contract mapping is unit-testable on its own — the
 * same shape `features/search/searchContract.ts` uses. Everything here is
 * pinned to `packages/api-client/src/generated/openapi.json` plus the backend
 * that serves it; no field is invented.
 *
 * WHY `locator` IS NEVER PARSED (A3.3 / C-04)
 * `ContextReference.locator` is a human label only. The backend builds it as
 * `"P" + pageNumber` out of the search hit
 * (`ai/context/AiContextItem.java:44`), i.e. a 1-based DOCUMENT page number,
 * which is not the `source_page` ROW id the reader selects on
 * (`SourceWorkbenchPage.tsx` reads `?page=` as an id). Parsing it could only
 * ever produce a link to the wrong page — and page numbers repeat across
 * assets of the same source, so it would look right. The route therefore comes
 * from `sourceId` / `sourcePageId` / `contentBlockId` exclusively
 * (`ai/dto/AiDto.java:68-83`: "the ID fields say which level the pointer lives
 * at"). `locator` is shown as text, and nothing else.
 *
 * THE TYPE VOCABULARY (V065, not `SearchEntityType`)
 * `ai_message_reference.reference_type` is CHECK-constrained to exactly
 * `SOURCE, SOURCE_PAGE, CONTENT_BLOCK, KNOWLEDGE_POINT, QUESTION,
 * WRONG_QUESTION, NOTE` (`V065__ai_reference_structured_locators.sql:32-35`).
 * `SOURCE_PAGE` exists only for a learner's ID selection — pages are not
 * full-text searchable, so `SearchEntityType` has no member for them
 * (`AiLearningContextService.java:40-46`). The contract types `type` as a bare
 * `string`, so TypeScript cannot see a new member: `aiContext.test.ts` pins
 * this list against the backend's own declarations instead.
 *
 * WHICH ID IS WHICH PER TYPE (read off the backend, not guessed)
 *   SOURCE         `id` = `sourceId` = the source
 *                  (`AiLearningContextService.java:247-257` `sourceItem`)
 *   SOURCE_PAGE    `id` = `sourcePageId` = the page row; `sourceId` = its owner
 *                  (`:234-245` `pageItem`)
 *   CONTENT_BLOCK  `id` = `contentBlockId` = the block; `sourceId` +
 *                  `sourcePageId` carry the owning chain
 *                  (`:221-232` `blockItem`, `AiContextItem.java:34-49` for an
 *                  automatically retrieved hit: `contentBlockId` is set only
 *                  for CONTENT_BLOCK rows)
 *   KNOWLEDGE_POINT `id` = the point. `ContextReference` has NO
 *                  `knowledgePointId` field, so `id` is the only — and
 *                  provably the correct — knowledge point id
 *                  (`:259-271` `knowledgePointItem`: entityId = point.getId()).
 *   QUESTION       `id` = the question (`SearchMapper.xml`: `q.id AS entity_id`
 *                  and `q.id AS question_id` are the same column)
 *   NOTE           `id` = the note (`n.id AS entity_id` = `n.id AS note_id`)
 *   WRONG_QUESTION `id` is the WRONG_QUESTION ROW id and the payload carries no
 *                  `questionId`, so this type has NO destination at all. Its
 *                  `id` must never be interpolated into `/questions/`.
 *
 * The three source-tree pointers are taken from the structured fields only:
 * `ai_message_reference.source_id` / `.source_page_id` / `.content_block_id`
 * are `ON DELETE SET NULL` (V065:24-27), so a reference to deleted material
 * arrives with those pointers ABSENT while `id` still names the dead row. A
 * link built from `id` there would 404 — which is exactly the "plausible
 * guess" this module must not emit.
 */

import type { components } from '@aistudy/api-client';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';

/** Contract `ContextReference` — one citation the tutor attached to a reply. */
export type AiCitation = components['schemas']['ContextReference'];

/** Contract `SendMessageRequest` — the body `sendMessage()` posts. */
export type AiMessageBody = components['schemas']['SendMessageRequest'];

/**
 * The four optional context ids `SendMessageRequest` accepts. Named by the
 * contract, so a field the backend does not have cannot be added here.
 */
export type AiAttachedContext = Pick<
  AiMessageBody,
  'sourceId' | 'sourcePageId' | 'contentBlockId' | 'knowledgePointId'
>;

/* ─────────────── A3.3 — citation → route ─────────────── */

/**
 * The `reference_type` vocabulary the backend can write (V065:32-35).
 * `CITATION_ROUTES` below is keyed by this union, so a member added by a
 * migration without a route here is a compile error rather than a citation
 * that silently stops being clickable.
 */
export const CITATION_TYPES = Object.freeze([
  'SOURCE',
  'SOURCE_PAGE',
  'CONTENT_BLOCK',
  'KNOWLEDGE_POINT',
  'QUESTION',
  'WRONG_QUESTION',
  'NOTE',
] as const);

export type CitationType = (typeof CITATION_TYPES)[number];

/** Display copy for every citable type, in the reader's own words. */
const CITATION_LABELS: Record<CitationType, string> = {
  SOURCE: '学习来源',
  SOURCE_PAGE: '来源页',
  CONTENT_BLOCK: '正文片段',
  KNOWLEDGE_POINT: '知识点',
  QUESTION: '题目',
  WRONG_QUESTION: '错题',
  NOTE: '笔记',
};

/** Chinese label for a raw contract type; unknown names show as sent. */
export function citationLabel(type?: string | null): string {
  if (!type) {
    return '—';
  }
  const key = type.trim().toUpperCase();
  const known = CITATION_TYPES.find((candidate) => candidate === key);
  return known ? CITATION_LABELS[known] : key;
}

/**
 * The workbench reader. `?page=` is the source PAGE ROW id the reader selects
 * on and `?block=` the content block to highlight — the same convention
 * Knowledge provenance and search results use
 * (`SourceWorkbenchPage.tsx:831-832`). A block with no page to look in means
 * nothing to the reader, so `block` is only ever emitted together with `page`.
 */
function readerHref(
  spaceId: number,
  sourceId: number,
  deepLink?: { pageId?: number | null; blockId?: number | null }
): string {
  const params = new URLSearchParams();
  if (isPositiveId(deepLink?.pageId)) {
    params.set('page', String(deepLink.pageId));
    if (isPositiveId(deepLink?.blockId)) {
      params.set('block', String(deepLink.blockId));
    }
  }
  const query = params.toString();
  return `/spaces/${spaceId}/sources/${sourceId}${query ? `?${query}` : ''}`;
}

/**
 * One route builder per citable type, keyed by the union so the compiler
 * forces an entry for every type the backend can write. `null` means "this
 * reference has no destination this build can name" — the row then renders as
 * plain text, which is honest, instead of a link that lands somewhere wrong.
 */
const CITATION_ROUTES: Record<
  CitationType,
  (spaceId: number, ref: AiCitation) => string | null
> = {
  SOURCE: (_spaceId, ref) =>
    isPositiveId(ref.sourceId) ? readerHref(_spaceId, ref.sourceId) : null,

  SOURCE_PAGE: (spaceId, ref) =>
    // The page row id is the deep link; without its owning source there is no
    // route to put it in (`pageItem` fills both, V065 nulls both on delete).
    isPositiveId(ref.sourceId) && isPositiveId(ref.sourcePageId)
      ? readerHref(spaceId, ref.sourceId, { pageId: ref.sourcePageId })
      : null,

  CONTENT_BLOCK: (spaceId, ref) => {
    if (!isPositiveId(ref.sourceId)) {
      return null;
    }
    // No page id: the block cannot be located, but the Source itself is still
    // a real destination, so it opens at the top without a highlight.
    if (!isPositiveId(ref.sourcePageId)) {
      return readerHref(spaceId, ref.sourceId);
    }
    return readerHref(spaceId, ref.sourceId, {
      pageId: ref.sourcePageId,
      blockId: ref.contentBlockId,
    });
  },

  KNOWLEDGE_POINT: (spaceId, ref) =>
    // `ContextReference` carries no `knowledgePointId`; `id` is the point
    // itself for this type, and the route is the registered knowledge detail
    // route (`app/router.tsx`: `knowledge/:knowledgePointId`).
    isPositiveId(ref.id)
      ? `/spaces/${spaceId}/knowledge/${ref.id}`
      : null,

  QUESTION: (spaceId, ref) =>
    isPositiveId(ref.id) ? `/spaces/${spaceId}/questions/${ref.id}` : null,

  WRONG_QUESTION: () =>
    // Deliberately never navigable: `id` is the wrong_question ROW
    // (`SearchMapper.xml`: `wq.id AS entity_id` vs `wq.question_id AS
    // question_id`) and no question id travels with the citation, so any
    // destination here would be a guess. There is also no wrong-question
    // route registered (see report).
    null,

  NOTE: (spaceId, ref) =>
    isPositiveId(ref.id) ? `/spaces/${spaceId}/notes/${ref.id}` : null,
};

/** The citable type of a raw payload value, or `undefined` for an unknown one. */
function citationTypeOf(type?: string | null): CitationType | undefined {
  const key = (type ?? '').trim().toUpperCase();
  return CITATION_TYPES.find((candidate) => candidate === key);
}

/**
 * Where a citation opens (A3.3 / G08), built only from fields
 * `ContextReference` actually sends: {type,id,title,snippet,locator,sourceId,
 * sourcePageId,contentBlockId}. `locator` is never consulted — it is a label.
 */
export function citationHref(spaceId: number, ref: AiCitation): string | null {
  const type = citationTypeOf(ref.type);
  if (!type) {
    // A type newer than this build, or a row with no type at all.
    return null;
  }
  return CITATION_ROUTES[type](spaceId, ref);
}

/* ─────────────── A3.2 — context attachment ─────────────── */

/**
 * Query keys `/spaces/:spaceId/ai` consumes to carry material into a question.
 *
 * Deliberately `attach`-prefixed rather than the bare contract names:
 * `sourceId` / `sourcePageId` / `contentBlockId` / `knowledgePointId` /
 * `questionId` are the Notes list prefill keys (`features/notes/NotesPage.tsx`
 * 12-13, `note-state.ts:210-216`), and the reader owns `page` / `block`. A
 * distinct prefix keeps the two vocabularies from meaning different things on
 * one URL, and makes the param → body-field mapping explicit at a glance.
 * Each key maps 1:1 onto the optional `SendMessageRequest` field of the same
 * name, which is what the request body then carries.
 */
export const AI_ATTACH_PARAM_KEYS = Object.freeze({
  contentBlockId: 'attachContentBlockId',
  sourcePageId: 'attachSourcePageId',
  sourceId: 'attachSourceId',
  knowledgePointId: 'attachKnowledgePointId',
} as const);

/** What the URL offered, what survived, and what had to be refused. */
export interface AiAttachmentPrefill {
  /** The ids the request body will carry. Empty when nothing is attached. */
  context: AiAttachedContext;
  /** Param names present but unusable — named in the UI, never dropped silently. */
  rejected: string[];
}

const ATTACH_ORDER = [
  'contentBlockId',
  'sourcePageId',
  'sourceId',
  'knowledgePointId',
] as const satisfies readonly (keyof typeof AI_ATTACH_PARAM_KEYS)[];

/**
 * Read the attached material out of the AI route's own query string (A3.2).
 *
 * Only positive integer ids count: `?attachSourceId=abc` must not become a
 * message pinned to `NaN`, and it is reported back as refused instead of being
 * ignored quietly.
 *
 * NARROWEST POINTER WINS for the source trio. The backend resolves a block's
 * own page and source for us, and a page its source
 * (`AiLearningContextService.java:152-158`, `:167-169`), so the upper levels
 * are derivable; sending them is how a mismatch arises, and a mismatched
 * `sourceId` + `sourcePageId` pair is a hard 400 VALIDATION_ERROR
 * (`AiLearningContextService.java:165-166` → `:349-352`). `knowledgePointId` is
 * an independent branch (`:185-191`), so it is kept as-is.
 */
export function readAiAttachment(
  params: URLSearchParams
): AiAttachmentPrefill {
  const parsed = new Map<(typeof ATTACH_ORDER)[number], number>();
  const rejected: string[] = [];

  for (const field of ATTACH_ORDER) {
    const key = AI_ATTACH_PARAM_KEYS[field];
    const raw = params.get(key);
    if (raw === null || raw.trim() === '') {
      continue;
    }
    const id = parsePositiveIdParam(raw.trim());
    if (id === null) {
      rejected.push(key);
      continue;
    }
    parsed.set(field, id);
  }

  const context: AiAttachedContext = {};
  // block > page > source, first hit wins, so the body never carries a trio the
  // server would overwrite or reject.
  for (const field of ['contentBlockId', 'sourcePageId', 'sourceId'] as const) {
    const id = parsed.get(field);
    if (id !== undefined) {
      context[field] = id;
      break;
    }
  }
  const pointId = parsed.get('knowledgePointId');
  if (pointId !== undefined) {
    context.knowledgePointId = pointId;
  }

  return { context, rejected };
}

/** Anything attached at all? Drives both the chip and the request body. */
export function hasAttachedContext(context: AiAttachedContext): boolean {
  return attachmentBody(context).length > 0;
}

/**
 * The whole `SendMessageRequest`: the typed text plus EXACTLY the attached ids.
 *
 * No other field is ever set, so the body cannot claim material the learner
 * did not pin — and the chip beside the composer lists the same pairs this
 * object carries, because both read {@link attachmentBody}.
 */
export function messageBodyWithAttachment(
  content: string,
  context: AiAttachedContext
): AiMessageBody {
  const body: AiMessageBody = { content };
  for (const [field, id] of attachmentBody(context)) {
    body[field] = id;
  }
  return body;
}

/**
 * The context fields of the request body, and nothing else (A3.2).
 *
 * Built from the ids the user attached — never from a field the payload does
 * not carry — so what the composer chip lists and what the server receives are
 * the same list by construction.
 */
export function attachmentBody(
  context: AiAttachedContext
): Array<[keyof AiAttachedContext, number]> {
  const entries: Array<[keyof AiAttachedContext, number]> = [];
  for (const field of ATTACH_ORDER) {
    const value = context[field];
    if (isPositiveId(value)) {
      entries.push([field, value]);
    }
  }
  return entries;
}

/** Chinese names of what is attached — the wording the learner reads. */
const ATTACH_LABELS: Record<keyof AiAttachedContext, string> = {
  contentBlockId: '正文片段',
  sourcePageId: '来源页',
  sourceId: '学习来源',
  knowledgePointId: '知识点',
};

/** What is attached, in the words the user sees next to the composer. */
export function attachmentSummary(context: AiAttachedContext): string[] {
  return attachmentBody(context).map(
    ([field, id]) => `${ATTACH_LABELS[field]} #${id}`
  );
}

/**
 * The query string that keeps an attachment across in-app navigation (a
 * conversation link must not silently drop what the learner pinned).
 * Empty string when nothing is attached.
 */
export function attachmentSearch(context: AiAttachedContext): string {
  const entries = attachmentBody(context);
  if (entries.length === 0) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [field, id] of entries) {
    params.set(AI_ATTACH_PARAM_KEYS[field], String(id));
  }
  return `?${params.toString()}`;
}

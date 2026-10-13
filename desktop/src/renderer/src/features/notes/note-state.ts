/**
 * Pure note-form state (G01 / A8.1–A8.3).
 *
 * Every rule here is a copy of a decision `NoteService` already makes on the
 * server (`server/src/main/java/com/aistudy/server/note/service/NoteService.java`),
 * restated so the form refuses locally what the backend would reject (or, worse,
 * silently accept):
 *
 * - `normalizeFormat()` (NoteService.java:172-182) maps a blank format to
 *   `PLAIN_TEXT` and throws a 400 for anything outside `PLAIN_TEXT`/`MARKDOWN`,
 *   so those two are the only options the UI may offer.
 * - `applyContext()` (NoteService.java:188-238) *overwrites* the less specific
 *   source pointers instead of trusting them (a block fixes its page and
 *   source, a page fixes its source) and 400s a `sourceId` that contradicts the
 *   given `sourcePageId`. The form therefore edits exactly one source pointer at
 *   a time — see `SourcePointerKind`.
 * - `update()` (NoteService.java:131-144) is a full replacement: an omitted
 *   field is written as null, and an omitted `contentFormat` comes back as
 *   PLAIN_TEXT. A save must therefore always send the whole document, context
 *   pointers included.
 */

import type { ApiClient, components } from '@aistudy/api-client';
import { NOTE_FORM_LIMITS } from '../../lib/format';
import { isPositiveId, parseOptionalPositiveId } from '../../lib/ids';

/**
 * One note as `GET /notes/{noteId}` returns it. Derived from the client method
 * rather than hand-copied, so it cannot drift from the generated contract
 * (`NoteView` in OpenAPI — the local name avoids re-declaring a DTO shape).
 */
export type NoteDocument = NonNullable<
  Awaited<ReturnType<ApiClient['getNote']>>['data']
>;

/** One row of the paged list (`NotePageResponse.items`). */
export type NoteRow = NonNullable<
  NonNullable<Awaited<ReturnType<ApiClient['listNotes']>>['data']>['items']
>[number];

/** Body shapes, taken from the client methods that send them. */
export type NoteCreateBody = NonNullable<
  Parameters<ApiClient['createNote']>[1]
>;
export type NoteSaveBody = NonNullable<Parameters<ApiClient['updateNote']>[2]>;

/** The query `GET /notes` accepts: `page` / `size` / `keyword`, all optional. */
export type NoteListQuery = NonNullable<
  Parameters<ApiClient['listNotes']>[1]
>;

/**
 * One provenance row of `GET /notes/{noteId}/source-blocks` — contract
 * `SourceReference`. The shared client declares this method loosely, so the
 * row type is named straight out of the generated schema instead of being
 * invented here.
 */
export type NoteSourceLink = components['schemas']['SourceReference'];

/**
 * The five context pointers `NoteCreateRequest` / `NoteUpdateRequest` accept.
 * `knowledgePointId`/`questionId` are independent; the three source pointers
 * form a hierarchy the server resolves for us.
 */
export interface NoteContextIds {
  sourceId?: number;
  sourcePageId?: number;
  contentBlockId?: number;
  knowledgePointId?: number;
  questionId?: number;
}

/** The formats the backend stores; anything else is a 400. */
export type NoteContentFormat =
  (typeof NOTE_FORM_LIMITS.contentFormats)[number];

export const NOTE_CONTENT_FORMATS: readonly NoteContentFormat[] =
  NOTE_FORM_LIMITS.contentFormats;

const FORMAT_LABELS: Record<NoteContentFormat, string> = {
  PLAIN_TEXT: '纯文本',
  MARKDOWN: 'Markdown',
};

export function isNoteContentFormat(
  value?: string | null
): value is NoteContentFormat {
  return (
    typeof value === 'string' &&
    NOTE_CONTENT_FORMATS.includes(value.trim().toUpperCase() as NoteContentFormat)
  );
}

export function contentFormatLabel(value?: string | null): string {
  if (!value) {
    return '—';
  }
  const normalized = value.trim().toUpperCase();
  return isNoteContentFormat(normalized)
    ? FORMAT_LABELS[normalized]
    : normalized;
}

/**
 * Which source pointer the form edits. `block` and `page` make the levels
 * above them redundant (the server derives them), so the UI never presents a
 * source/page/block trio the backend would overwrite or reject.
 */
export type SourcePointerKind = 'none' | 'source' | 'page' | 'block';

/** The most specific pointer a note actually carries (block > page > source). */
export function sourcePointerOf(ids: NoteContextIds): SourcePointerKind {
  if (isPositiveId(ids.contentBlockId)) return 'block';
  if (isPositiveId(ids.sourcePageId)) return 'page';
  if (isPositiveId(ids.sourceId)) return 'source';
  return 'none';
}

/**
 * The editor's whole state. Ids stay raw strings so a malformed value can be
 * reported to the user instead of being dropped on the way to the payload.
 */
export interface NoteDraft {
  title: string;
  content: string;
  contentFormat: string;
  sourcePointer: SourcePointerKind;
  sourceId: string;
  sourcePageId: string;
  contentBlockId: string;
  knowledgePointId: string;
  questionId: string;
}

const SOURCE_FIELDS: Record<
  Exclude<SourcePointerKind, 'none'>,
  'sourceId' | 'sourcePageId' | 'contentBlockId'
> = {
  source: 'sourceId',
  page: 'sourcePageId',
  block: 'contentBlockId',
};

/** Display copy for each way a note can point at a source. */
export const SOURCE_POINTER_LABELS: Record<
  Exclude<SourcePointerKind, 'none'>,
  string
> = {
  source: '学习来源',
  page: '来源页',
  block: '正文片段',
};

/**
 * Why the form sends one pointer and not the trio: the server resolves the
 * chain itself, and a contradicting pair is a 400 (NoteService.java:205-208).
 */
export const SOURCE_POINTER_HINTS: Record<SourcePointerKind, string> = {
  none: '这条笔记不关联任何来源内容。',
  source: '笔记关联到整个学习来源。',
  page: '服务端会由来源页自动补出所属学习来源；与页面不一致的来源 ID 会被 400 拒绝，所以这里只填页面 ID。',
  block: '服务端会由正文片段自动补出所属页面与学习来源，所以这里只填片段 ID。',
};

function idField(value: number | null | undefined): string {
  return isPositiveId(value) ? String(value) : '';
}

/** A note document as an editable draft. */
export function draftOfNote(note: NoteDocument): NoteDraft {
  return {
    title: note.title ?? '',
    content: note.content ?? '',
    contentFormat: (note.contentFormat ?? '').trim().toUpperCase(),
    sourcePointer: sourcePointerOf(note),
    sourceId: idField(note.sourceId),
    sourcePageId: idField(note.sourcePageId),
    contentBlockId: idField(note.contentBlockId),
    knowledgePointId: idField(note.knowledgePointId),
    questionId: idField(note.questionId),
  };
}

/**
 * A blank draft, optionally prefilled with one entity's ids (W16: arriving
 * from a knowledge point or a source reader starts a note attached to it).
 * The format falls back to the contract default unless it is one of the two
 * the server accepts.
 */
export function emptyDraft(
  context: NoteContextIds = {},
  contentFormat?: string | null
): NoteDraft {
  return {
    title: '',
    content: '',
    contentFormat: isNoteContentFormat(contentFormat)
      ? (contentFormat ?? '').trim().toUpperCase()
      : NOTE_FORM_LIMITS.defaultContentFormat,
    sourcePointer: sourcePointerOf(context),
    sourceId: idField(context.sourceId),
    sourcePageId: idField(context.sourcePageId),
    contentBlockId: idField(context.contentBlockId),
    knowledgePointId: idField(context.knowledgePointId),
    questionId: idField(context.questionId),
  };
}

/** Copy for the query params this page cannot use, so a bad link is visible. */
const PREFILL_ID_KEYS = [
  'knowledgePointId',
  'sourceId',
  'sourcePageId',
  'contentBlockId',
  'questionId',
] as const;

export interface NotePrefill {
  context: NoteContextIds;
  contentFormat: string | null;
  /** Param names present in the URL but not usable — reported, never dropped silently. */
  rejected: string[];
}

/**
 * Read the W16 cross-module entry point out of the list URL. Only contract
 * fields are honoured, and only positive integer ids: `?knowledgePointId=abc`
 * must not become a note attached to `NaN`.
 */
export function readNotePrefill(params: URLSearchParams): NotePrefill {
  const context: NoteContextIds = {};
  const rejected: string[] = [];

  for (const key of PREFILL_ID_KEYS) {
    const raw = params.get(key);
    if (raw === null || raw.trim() === '') {
      continue;
    }
    const parsed = parseOptionalPositiveId(raw);
    if (parsed === undefined) {
      rejected.push(key);
      continue;
    }
    context[key] = parsed;
  }

  const rawFormat = params.get('contentFormat');
  let contentFormat: string | null = null;
  if (rawFormat !== null && rawFormat.trim() !== '') {
    if (isNoteContentFormat(rawFormat)) {
      contentFormat = rawFormat.trim().toUpperCase();
    } else {
      rejected.push('contentFormat');
    }
  }

  return { context, contentFormat, rejected };
}

/** The 0-based page the URL asks for; anything unusable is page 0. */
export function parseNotePageParam(raw: string | null): number {
  if (raw === null || raw.trim() === '') {
    return 0;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value > 0 ? value : 0;
}

/** The raw input of whichever source pointer the form is editing. */
export function pointerValueOf(
  draft: NoteDraft,
  kind: Exclude<SourcePointerKind, 'none'>
): string {
  return draft[SOURCE_FIELDS[kind]];
}

/**
 * Write the active pointer's input. The other two keep their values but are
 * never submitted, which is what `applyContext` would do to them anyway.
 */
export function withPointerValue(
  draft: NoteDraft,
  kind: Exclude<SourcePointerKind, 'none'>,
  value: string
): NoteDraft {
  switch (kind) {
    case 'source':
      return { ...draft, sourceId: value };
    case 'page':
      return { ...draft, sourcePageId: value };
    case 'block':
      return { ...draft, contentBlockId: value };
  }
}

/**
 * Contract constraints, checked before submitting. Listed on the form rather
 * than only disabling 创建/保存, so a rejected submit never feels like a dead
 * button (the pattern ExamsPage.tsx established).
 */
export function validateNoteDraft(draft: NoteDraft): string[] {
  const errors: string[] = [];

  if (draft.content.trim() === '') {
    // `NoteCreateRequest.content` is @NotBlank.
    errors.push('正文不能为空。');
  } else if (draft.content.length > NOTE_FORM_LIMITS.contentMaxLength) {
    errors.push(
      `正文最多 ${NOTE_FORM_LIMITS.contentMaxLength} 个字符，当前 ${draft.content.length} 个。`
    );
  }

  if (draft.title.length > NOTE_FORM_LIMITS.titleMaxLength) {
    errors.push(
      `标题最多 ${NOTE_FORM_LIMITS.titleMaxLength} 个字符，当前 ${draft.title.length} 个。`
    );
  }

  if (!isNoteContentFormat(draft.contentFormat)) {
    errors.push(
      `正文格式只能是 ${NOTE_CONTENT_FORMATS.join(' / ')}，服务端会拒绝其它值。`
    );
  }

  const labelled: Array<[string, string]> = [];
  const pointer = draft.sourcePointer;
  if (pointer !== 'none') {
    const field = SOURCE_FIELDS[pointer];
    labelled.push([SOURCE_POINTER_LABELS[pointer], draft[field]]);
  }
  labelled.push(['知识点 ID', draft.knowledgePointId]);
  labelled.push(['题目 ID', draft.questionId]);

  for (const [label, raw] of labelled) {
    if (raw.trim() !== '' && parseOptionalPositiveId(raw) === undefined) {
      errors.push(`${label}必须是正整数。`);
    }
  }

  return errors;
}

/**
 * The document the endpoints accept. `content` and `contentFormat` are always
 * sent — dropping either would make the server store a blank body or quietly
 * downgrade Markdown to PLAIN_TEXT (NoteService.java:136-138). Keys with no
 * value are omitted rather than sent as null, and only the ACTIVE source
 * pointer is sent, because the server overwrites the rest anyway.
 */
export function buildNoteBody(draft: NoteDraft): NoteSaveBody {
  const body: NoteSaveBody = {
    content: draft.content,
    contentFormat: draft.contentFormat.trim().toUpperCase(),
  };

  const title = draft.title.trim();
  if (title !== '') {
    body.title = title;
  }

  const pointer = draft.sourcePointer;
  if (pointer !== 'none') {
    const parsed = parseOptionalPositiveId(draft[SOURCE_FIELDS[pointer]]);
    if (parsed !== undefined) {
      body[SOURCE_FIELDS[pointer]] = parsed;
    }
  }

  const knowledgePointId = parseOptionalPositiveId(draft.knowledgePointId);
  if (knowledgePointId !== undefined) {
    body.knowledgePointId = knowledgePointId;
  }

  const questionId = parseOptionalPositiveId(draft.questionId);
  if (questionId !== undefined) {
    body.questionId = questionId;
  }

  return body;
}

/** Create and update bodies are the same document; this names the intent. */
export function buildNoteCreateBody(draft: NoteDraft): NoteCreateBody {
  return buildNoteBody(draft);
}

/**
 * Two drafts describe the same stored document when every field the server
 * writes matches. Compared through `buildNoteBody`, because the server's own
 * coherence rule (NoteService.java:193-209) means a draft that carries a block
 * plus a page stores exactly what the block alone stores — and because the
 * PUT response has the derived pointers filled back in, a save must not look
 * like a fresh set of edits.
 */
export function sameNoteDraft(a: NoteDraft, b: NoteDraft): boolean {
  return (
    a.title.trim() === b.title.trim() &&
    a.content === b.content &&
    JSON.stringify(buildNoteBody(a)) === JSON.stringify(buildNoteBody(b))
  );
}

/**
 * A note's heading. `NoteCreateRequest.title` is optional and the backend
 * documents an untitled note as rendering its first line, so the first line is
 * shown rather than the word “未命名” over real content.
 */
export function noteDisplayTitle(
  note: { title?: string | null; content?: string | null },
  fallback = '未命名笔记'
): string {
  const title = (note.title ?? '').trim();
  if (title !== '') {
    return title;
  }
  const firstLine = (note.content ?? '')
    .split('\n')
    .map((line) => line.trim())
    .find((line) => line !== '');
  if (!firstLine) {
    return fallback;
  }
  return firstLine.length > 60 ? `${firstLine.slice(0, 60)}…` : firstLine;
}

/** List-row excerpt of the body, collapsed to one line. */
export function noteExcerpt(content?: string | null, max = 120): string {
  const flat = (content ?? '').replace(/\s+/g, ' ').trim();
  if (flat === '') {
    return '';
  }
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

/**
 * Deep link into the source reader (`/spaces/:spaceId/sources/:sourceId`,
 * `?page=` = sourcePageId, `?block=` = contentBlockId — see
 * features/sources/SourceWorkbenchPage.tsx:764-805). Null when the row carries
 * no usable source id, because then there is no reader to open.
 */
export function sourceReaderHref(
  spaceId: number,
  link: { sourceId?: number | null; sourcePageId?: number | null; contentBlockId?: number | null }
): string | null {
  if (!isPositiveId(link.sourceId)) {
    return null;
  }
  const query = new URLSearchParams();
  if (isPositiveId(link.sourcePageId)) {
    query.set('page', String(link.sourcePageId));
  }
  if (isPositiveId(link.contentBlockId)) {
    query.set('block', String(link.contentBlockId));
  }
  const suffix = query.size > 0 ? `?${query.toString()}` : '';
  return `/spaces/${spaceId}/sources/${link.sourceId}${suffix}`;
}

/** Route of one knowledge point of the current space. */
export function knowledgePointHref(spaceId: number, knowledgePointId: number): string {
  return `/spaces/${spaceId}/knowledge/${knowledgePointId}`;
}

/** Route of one question of the current space. */
export function questionHref(spaceId: number, questionId: number): string {
  return `/spaces/${spaceId}/questions/${questionId}`;
}

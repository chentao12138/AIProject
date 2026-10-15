/**
 * Note List + creation (G01 / A8.1, W16 entry point).
 *
 * /spaces/:spaceId/notes
 *
 * Paging and the keyword filter are server-side: `GET /notes` accepts exactly
 * `page` / `size` / `keyword` (openapi.json; `NoteController.list` defaults them
 * to 0 / 20), and this page renders only the `items` of the page it asked for.
 * Fetching everything and slicing locally would be the fake pagination the
 * audit rejects, so the pager reads the server's own `totalItems` / `totalPages`.
 *
 * W16: another module links here with `?knowledgePointId=` / `?sourceId=` /
 * `?sourcePageId=` / `?contentBlockId=` / `?questionId=` and the create form
 * opens pre-attached to that entity. A value that is not a positive integer id
 * is named in the UI instead of being dropped on the floor — `readNotePrefill`.
 */

import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { isPositiveId } from '../../lib/ids';
import { formatDateTime, NOTE_FORM_LIMITS } from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { SelectMenu } from '../../components/SelectMenu';
import { TextArea } from '../../components/TextArea';
import {
  buildNoteCreateBody,
  contentFormatLabel,
  emptyDraft,
  NOTE_CONTENT_FORMATS,
  noteDisplayTitle,
  noteExcerpt,
  parseNotePageParam,
  pointerValueOf,
  readNotePrefill,
  SOURCE_POINTER_HINTS,
  SOURCE_POINTER_LABELS,
  validateNoteDraft,
  withPointerValue,
  type NoteContextIds,
  type NoteDraft,
  type NotePrefill,
  type NoteRow,
  type SourcePointerKind,
} from './note-state';

const POINTER_OPTIONS: readonly SourcePointerKind[] = [
  'none',
  'source',
  'page',
  'block',
];

/** What a prefill would attach the new note to, in the words the user reads. */
function prefillSummary(context: NoteContextIds): string[] {
  const parts: string[] = [];
  if (isPositiveId(context.sourceId)) parts.push(`学习来源 #${context.sourceId}`);
  if (isPositiveId(context.sourcePageId))
    parts.push(`来源页 #${context.sourcePageId}`);
  if (isPositiveId(context.contentBlockId))
    parts.push(`正文片段 #${context.contentBlockId}`);
  if (isPositiveId(context.knowledgePointId))
    parts.push(`知识点 #${context.knowledgePointId}`);
  if (isPositiveId(context.questionId)) parts.push(`题目 #${context.questionId}`);
  return parts;
}

export function NotesPage() {
  const api = useApiClient();
  const spaceId = useSpaceId();
  const [searchParams, setSearchParams] = useSearchParams();

  const page = parseNotePageParam(searchParams.get('page'));
  const keywordParam = (searchParams.get('keyword') ?? '').trim();
  const keyword = keywordParam === '' ? undefined : keywordParam;
  // The list hides archived notes unless the URL asks for them; `?archived=1`
  // is the whole state, so Back/Forward and a shared link behave the same.
  const archivedOnly = (searchParams.get('archived') ?? '').trim() === '1';
  const prefill = readNotePrefill(searchParams);

  // null = the box mirrors the URL; a string = the user is mid-edit. Back or
  // forward then repopulates the box without a second source of truth.
  const [keywordDraft, setKeywordDraft] = useState<string | null>(null);

  const listQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.notes(spaceId, { page, keyword, archived: archivedOnly || undefined }),
      (id) =>
        api.listNotes(id, {
          page,
          size: NOTE_FORM_LIMITS.pageSize,
          keyword,
          archived: archivedOnly || undefined,
        })
    )
  );

  if (spaceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  function commitUrl(
    updates: { page?: number; keyword?: string | null; archived?: boolean }
  ) {
    const next = new URLSearchParams(searchParams);
    if ('archived' in updates) {
      if (updates.archived) {
        next.set('archived', '1');
      } else {
        next.delete('archived');
      }
    }
    if ('page' in updates) {
      const value = updates.page ?? 0;
      if (value > 0) {
        next.set('page', String(value));
      } else {
        next.delete('page');
      }
    }
    if ('keyword' in updates) {
      const value = updates.keyword;
      if (value) {
        next.set('keyword', value);
      } else {
        next.delete('keyword');
      }
    }
    setSearchParams(next);
  }

  const rows = listQuery.data?.items ?? [];
  const totalItems = listQuery.data?.totalItems ?? 0;
  const totalPages = listQuery.data?.totalPages ?? 0;
  const pageSize = listQuery.data?.pageSize ?? NOTE_FORM_LIMITS.pageSize;

  return (
    <div className="page">
      <PageHeader title="笔记" />

      {prefill.rejected.length > 0 && (
        <p
          className="form__error"
          role="alert"
          data-testid="note-prefill-rejected"
        >
          链接里的 {prefill.rejected.join('、')} 不是可用的 ID，已忽略：新笔记不会带上它。
        </p>
      )}

      <NoteCreateForm
        key={JSON.stringify(prefill.context) + (prefill.contentFormat ?? '')}
        spaceId={spaceId}
        prefill={prefill}
      />

      <section className="workbench-section" aria-label="笔记搜索">
        <h2 className="section-title">搜索笔记</h2>
        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            const committed = (keywordDraft ?? '').trim();
            setKeywordDraft(null);
            // A new search restarts at page 0: page 3 of the previous result
            // set is not page 3 of this one.
            commitUrl({ keyword: committed === '' ? null : committed, page: 0 });
          }}
        >
          <Input
            label="关键词"
            hint="由服务端按标题与正文模糊匹配（GET /notes?keyword=）。"
            value={keywordDraft ?? keywordParam}
            onChange={(event) => setKeywordDraft(event.target.value)}
          />
          <div className="form__actions">
            <Button type="submit" variant="primary">
              搜索
            </Button>
            {keyword !== undefined && (
              <Button
                type="button"
                onClick={() => {
                  setKeywordDraft(null);
                  commitUrl({ keyword: null, page: 0 });
                }}
              >
                清除关键词
              </Button>
            )}
          </div>
        </form>
      </section>

      <section className="workbench-section" aria-label="笔记列表">
        <h2 className="section-title">笔记列表</h2>

        {/* The server filters archived rows for us: `GET /notes` hides them
            unless `archived=true`, so "what is archived" is only reachable by
            asking for it — hence a real control, not a client-side filter. */}
        <div className="practice-nav">
          <label className="field__inline" htmlFor="notes-archived-filter">
            <input
              id="notes-archived-filter"
              type="checkbox"
              checked={archivedOnly}
              onChange={(event) =>
                commitUrl({ archived: event.target.checked, page: 0 })
              }
              disabled={listQuery.isFetching}
              data-testid="notes-archived-filter"
            />
            只看已归档
          </label>
          {archivedOnly && (
            <span className="muted" role="status" data-testid="notes-archived-mode">
              正在列出已归档笔记
            </span>
          )}
        </div>

        {listQuery.isPending && <LoadingState text="加载笔记…" />}
        {listQuery.isError && (
          <ErrorState
            message={normalizeApiError(listQuery.error).message}
            onRetry={() => void listQuery.refetch()}
          />
        )}

        {listQuery.isSuccess && rows.length === 0 && (
          <EmptyState
            title={keyword ? `没有匹配「${keyword}」的笔记` : '还没有笔记'}
            description={
              keyword
                ? '换个关键词，或清除关键词后查看全部笔记。'
                : '用上面的表单写下第一条笔记。'
            }
          />
        )}

        {listQuery.isSuccess && rows.length > 0 && (
          <>
            <p className="page__note" data-testid="note-count">
              第 {page + 1} / {Math.max(totalPages, 1)} 页 · 共 {totalItems} 条 ·
              每页 {pageSize} 条
            </p>
            <table className="table">
              <thead>
                <tr>
                  <th>标题</th>
                  <th>正文</th>
                  <th>格式</th>
                  <th>更新时间</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((note, index) => (
                  <NoteRowItem
                    key={note.id ?? `row-${index}`}
                    spaceId={spaceId}
                    note={note}
                  />
                ))}
              </tbody>
            </table>
            {totalPages > 1 && (
              <div className="practice-nav">
                <Button
                  type="button"
                  onClick={() => commitUrl({ page: page - 1 })}
                  disabled={page <= 0 || listQuery.isFetching}
                >
                  上一页
                </Button>
                <span className="muted">
                  第 {page + 1} / {totalPages} 页
                </span>
                <Button
                  type="button"
                  onClick={() => commitUrl({ page: page + 1 })}
                  disabled={page >= totalPages - 1 || listQuery.isFetching}
                >
                  下一页
                </Button>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function NoteRowItem({ spaceId, note }: { spaceId: number; note: NoteRow }) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const noteId = note.id;
  const excerpt = noteExcerpt(note.content);
  // `archivedAt` is the server's own flag (C-Notes-archive closed): a row in the
  // archived view can be brought back, and nothing here guesses the state from
  // the list it happens to appear in.
  const archived = Boolean(note.archivedAt);

  const restore = useMutation({
    // A row without an id cannot be restored. `requireId` is the repo's single
    // id guard (the button is disabled for exactly this row, so the throw is a
    // backstop, not a user-facing path).
    mutationFn: () =>
      api.restoreNote(spaceId, requireId(noteId ?? null, 'note id')).then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.notes(spaceId),
      });
      if (isPositiveId(noteId)) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.note(spaceId, noteId),
        });
      }
    },
  });

  return (
    <tr>
      <td>
        {isPositiveId(noteId) ? (
          <Link to={`/spaces/${spaceId}/notes/${noteId}`} className="nav-link">
            {noteDisplayTitle(note)}
          </Link>
        ) : (
          /* A row without an id has no detail route to open. */
          <span className="muted">{noteDisplayTitle(note)}</span>
        )}
      </td>
      <td className="muted">{excerpt === '' ? '—' : excerpt}</td>
      <td>{contentFormatLabel(note.contentFormat)}</td>
      <td>{formatDateTime(note.updatedAt)}</td>
      <td>
        {archived ? (
          <>
            <span data-testid="note-archived-flag">已归档</span>
            <Button
              type="button"
              variant="secondary"
              disabled={!isPositiveId(noteId) || restore.isPending}
              onClick={() => restore.mutate()}
              data-testid={`note-restore-${noteId}`}
            >
              {restore.isPending ? '恢复中…' : '恢复'}
            </Button>
            {restore.isError && (
              <span className="muted" role="alert" data-testid="note-restore-error">
                {normalizeApiError(restore.error).message}
              </span>
            )}
          </>
        ) : (
          <span className="muted">在用</span>
        )}
      </td>
    </tr>
  );
}

/**
 * Create form. Rendered inline rather than behind a button (the exam list sets
 * that precedent): the W16 prefill has to be visible before it is submitted,
 * not buried in a dialog.
 */
function NoteCreateForm({
  spaceId,
  prefill,
}: {
  spaceId: number;
  prefill: NotePrefill;
}) {
  const api = useApiClient();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [draft, setDraft] = useState<NoteDraft>(() =>
    emptyDraft(prefill.context, prefill.contentFormat)
  );
  const [showErrors, setShowErrors] = useState(false);

  const formErrors = validateNoteDraft(draft);
  const prefillParts = prefillSummary(prefill.context);
  const pointer = draft.sourcePointer;

  const create = useMutation({
    mutationFn: () =>
      api.createNote(spaceId, buildNoteCreateBody(draft)).then(unwrap),
    onSuccess: (note) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.notes(spaceId),
      });
      if (isPositiveId(note?.id)) {
        navigate(`/spaces/${spaceId}/notes/${note.id}`);
      }
    },
  });

  function patch(updates: Partial<NoteDraft>) {
    setDraft((current) => ({ ...current, ...updates }));
  }

  return (
    <section className="workbench-section" aria-label="创建笔记">
      <h2 className="section-title">创建笔记</h2>

      {prefillParts.length > 0 && (
        <p className="page__note" data-testid="note-prefill-summary">
          新笔记将关联到：{prefillParts.join('、')}。
        </p>
      )}

      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          setShowErrors(true);
          if (formErrors.length > 0 || create.isPending) {
            return;
          }
          create.mutate();
        }}
      >
        {showErrors && formErrors.length > 0 && (
          <ul className="form__error" role="alert" data-testid="note-form-errors">
            {formErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        )}

        <Input
          label="标题（可选）"
          hint={`最多 ${NOTE_FORM_LIMITS.titleMaxLength} 个字符；留空时列表按正文首行显示。`}
          value={draft.title}
          maxLength={NOTE_FORM_LIMITS.titleMaxLength}
          onChange={(event) => patch({ title: event.target.value })}
        />

        <TextArea
          label="正文"
          hint={`必填，最多 ${NOTE_FORM_LIMITS.contentMaxLength} 个字符。`}
          rows={8}
          value={draft.content}
          maxLength={NOTE_FORM_LIMITS.contentMaxLength}
          onChange={(event) => patch({ content: event.target.value })}
        />

        <SelectMenu
          label="正文格式"
          hint="服务端只接受这两个值，其它值会被 400 拒绝。"
          value={draft.contentFormat}
          onValueChange={(value) => patch({ contentFormat: value })}
          options={NOTE_CONTENT_FORMATS.map((format) => ({
            // The option list is exactly `NoteService.normalizeFormat()`'s
            // accept-set, and each label shows the wire value next to its
            // display name so the id being posted is never ambiguous.
            value: format,
            label: `${format}（${contentFormatLabel(format)}）`,
          }))}
        />

        <fieldset className="field">
          <legend className="field__label">关联来源内容</legend>
          {POINTER_OPTIONS.map((kind) => (
            <label key={kind} htmlFor={`note-create-pointer-${kind}`}>
              <input
                id={`note-create-pointer-${kind}`}
                type="radio"
                name="note-create-source-pointer"
                value={kind}
                checked={pointer === kind}
                onChange={() => patch({ sourcePointer: kind })}
              />
              {kind === 'none' ? '不关联' : SOURCE_POINTER_LABELS[kind]}
            </label>
          ))}
          <p className="field__hint" data-testid="note-source-hint">
            {SOURCE_POINTER_HINTS[pointer]}
          </p>
          {pointer !== 'none' && (
            <Input
              label={`${SOURCE_POINTER_LABELS[pointer]} ID`}
              hint="只填正整数 ID。"
              inputMode="numeric"
              value={pointerValueOf(draft, pointer)}
              onChange={(event) =>
                setDraft(withPointerValue(draft, pointer, event.target.value))
              }
            />
          )}
        </fieldset>

        <Input
          label="知识点 ID（可选）"
          hint="从知识点页的笔记入口进来时会自动带上。"
          inputMode="numeric"
          value={draft.knowledgePointId}
          onChange={(event) => patch({ knowledgePointId: event.target.value })}
        />

        <Input
          label="题目 ID（可选）"
          hint="关联一道题，笔记会作为那道题的旁注保存。"
          inputMode="numeric"
          value={draft.questionId}
          onChange={(event) => patch({ questionId: event.target.value })}
        />

        {create.isError && (
          <p className="form__error" role="alert">
            {normalizeApiError(create.error).message}
          </p>
        )}

        <div className="form__actions">
          {/* Not disabled when validation fails: the click has to reveal the
              reason, not look like a dead button. */}
          <Button type="submit" variant="primary" disabled={create.isPending}>
            {create.isPending ? '创建中…' : '创建笔记'}
          </Button>
        </div>
      </form>
    </section>
  );
}

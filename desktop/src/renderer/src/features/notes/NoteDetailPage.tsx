/**
 * Note detail + editor (G01 / A8.1–A8.3).
 *
 * /spaces/:spaceId/notes/:noteId
 *
 * `PUT /notes/{noteId}` replaces the whole document: `NoteService.update()`
 * (NoteService.java:131-144) writes `title`, `content`, `contentFormat` and all
 * five context pointers straight from the body, so an omitted field is stored as
 * null and an omitted `contentFormat` comes back as PLAIN_TEXT — quietly
 * downgrading a Markdown note. Saving therefore always sends the whole document,
 * context pointers included, and the editor keeps the loaded ids in its state
 * instead of only the ones the user touched.
 *
 * `archive` (NoteService.java:146-154) sets `archived_at`, and the list query
 * filters `archived_at IS NULL` (NoteMapper.java:42, 59): an archived note leaves
 * the list for good. The contract has no un-archive and `NoteView` carries no
 * archived flag, so this page states what it just did instead of pretending the
 * note is still findable from the list.
 *
 * `delete` (NoteService.java:161-170) is a hard delete that also drops the
 * knowledge-point and source-block link rows — hence the explicit confirmation.
 *
 * The two link panels exist because `NoteKnowledgePointService.linkKnowledgePoints`
 * / `NoteSourceService.linkContentBlocks` answer an unknown id by returning 201
 * and storing nothing (early `return` on a failed lookup). Hand-typing an id
 * would look like success, so both pickers are populated from the space's own
 * knowledge points and content blocks.
 */

import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery, spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import {
  isPositiveId,
  parseOptionalPositiveId,
  parsePositiveIdParam,
} from '../../lib/ids';
import { formatDateTime, NOTE_FORM_LIMITS } from '../../lib/format';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { Select } from '../../components/Select';
import { TextArea } from '../../components/TextArea';
import { UnsavedChangesGuard } from '../../components/UnsavedChangesGuard';
import {
  buildNoteBody,
  contentFormatLabel,
  draftOfNote,
  knowledgePointHref,
  NOTE_CONTENT_FORMATS,
  noteDisplayTitle,
  pointerValueOf,
  questionHref,
  sameNoteDraft,
  SOURCE_POINTER_HINTS,
  SOURCE_POINTER_LABELS,
  sourceReaderHref,
  validateNoteDraft,
  withPointerValue,
  type NoteDocument,
  type NoteDraft,
  type NoteSourceLink,
  type SourcePointerKind,
} from './note-state';

const POINTER_OPTIONS: readonly SourcePointerKind[] = [
  'none',
  'source',
  'page',
  'block',
];

export function NoteDetailPage() {
  const api = useApiClient();
  const spaceId = useSpaceId();
  const { noteId: noteIdParam } = useParams();
  const noteId = parsePositiveIdParam(noteIdParam);

  const noteQuery = useQuery(
    entityQuery(spaceId, noteId, queryKeys.note(spaceId, noteId), (id, note) =>
      api.getNote(id, note)
    )
  );

  if (spaceId === null || noteId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  if (noteQuery.isPending) {
    return <LoadingState text="加载笔记…" />;
  }

  if (noteQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(noteQuery.error).message}
        onRetry={() => void noteQuery.refetch()}
      />
    );
  }

  const note = noteQuery.data;
  if (!note) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  return (
    <div className="page">
      {/* Keyed by the note: the editor holds a draft, and moving between two
          note ids in the same route must not carry one note's unsaved text into
          the other. */}
      <NoteEditor key={noteId} spaceId={spaceId} noteId={noteId} note={note} />
      <StoredContextSection spaceId={spaceId} note={note} />
      <KnowledgePointLinks key={`kp-${noteId}`} spaceId={spaceId} noteId={noteId} />
      <SourceBlockLinks key={`block-${noteId}`} spaceId={spaceId} noteId={noteId} />
    </div>
  );
}

/**
 * The editor owns the dirty state, and with it the A8.2 navigation guard: a
 * guard that lives above the form would have to be told about every keystroke.
 */
function NoteEditor({
  spaceId,
  noteId,
  note,
}: {
  spaceId: number;
  noteId: number;
  note: NoteDocument;
}) {
  const api = useApiClient();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [draft, setDraft] = useState<NoteDraft>(() => draftOfNote(note));
  const [baseline, setBaseline] = useState<NoteDraft>(() => draftOfNote(note));
  const [showErrors, setShowErrors] = useState(false);
  const [confirming, setConfirming] = useState<'archive' | 'delete' | null>(
    null
  );
  const [archived, setArchived] = useState(false);
  const [leaving, setLeaving] = useState(false);

  const dirty = !sameNoteDraft(draft, baseline);
  const formErrors = validateNoteDraft(draft);
  const pointer = draft.sourcePointer;

  const noteKey = queryKeys.note(spaceId, noteId);
  const listKey = queryKeys.notes(spaceId);

  /**
   * Leaving after a delete goes through an effect, not straight out of
   * `onSuccess`: within the same event handler the guard still sees the previous
   * render's `isDirty`, so an immediate `navigate()` would answer a destroyed
   * note with an unsaved-changes prompt.
   */
  useEffect(() => {
    if (leaving) {
      navigate(`/spaces/${spaceId}/notes`);
    }
  }, [leaving, navigate, spaceId]);

  const save = useMutation({
    mutationFn: () =>
      api.updateNote(spaceId, noteId, buildNoteBody(draft)).then(unwrap),
    onSuccess: (saved) => {
      // The response is the stored document; when it carries nothing, the body
      // we just validated is what the server wrote.
      setBaseline(saved ? draftOfNote(saved) : draft);
      setShowErrors(false);
      void queryClient.invalidateQueries({ queryKey: noteKey });
      void queryClient.invalidateQueries({ queryKey: listKey });
    },
  });

  const archive = useMutation({
    mutationFn: () => api.archiveNote(spaceId, noteId).then(unwrap),
    onSuccess: () => {
      setArchived(true);
      setConfirming(null);
      void queryClient.invalidateQueries({ queryKey: listKey });
    },
  });

  const remove = useMutation({
    mutationFn: () => api.deleteNote(spaceId, noteId).then(unwrap),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: noteKey });
      void queryClient.invalidateQueries({ queryKey: listKey });
      setConfirming(null);
      setLeaving(true);
    },
  });

  function patch(updates: Partial<NoteDraft>) {
    setDraft((current) => ({ ...current, ...updates }));
  }

  const failing = save.isError
    ? save
    : archive.isError
      ? archive
      : remove.isError
        ? remove
        : null;

  return (
    <>
      <PageHeader
        title={noteDisplayTitle(note)}
        actions={
          <>
            <Link
              to={`/spaces/${spaceId}/notes`}
              className="nav-link"
              data-testid="notes-back-link"
            >
              返回列表
            </Link>
            <Button
              type="button"
              onClick={() => setConfirming('archive')}
              disabled={archived || archive.isPending || remove.isPending}
            >
              归档
            </Button>
            <Button
              type="button"
              variant="danger"
              onClick={() => setConfirming('delete')}
              disabled={remove.isPending || archive.isPending}
            >
              删除
            </Button>
          </>
        }
      />

      <UnsavedChangesGuard isDirty={dirty && !leaving} />

      {archived && (
        <p className="page__note" role="status" data-testid="note-archived-notice">
          这条笔记已归档：GET /notes 只返回未归档的笔记，所以它不会再出现在列表里，
          而契约没有取消归档的接口。文档仍保存在服务端，本页可以继续编辑。
        </p>
      )}

      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          setShowErrors(true);
          if (formErrors.length > 0 || save.isPending) {
            return;
          }
          save.mutate();
        }}
      >
        <p className="muted">
          格式：{contentFormatLabel(note.contentFormat)} · 创建：{' '}
          {formatDateTime(note.createdAt)} · 更新：{' '}
          {formatDateTime(note.updatedAt)}
        </p>

        {showErrors && formErrors.length > 0 && (
          <ul className="form__error" role="alert" data-testid="note-form-errors">
            {formErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        )}

        <Input
          label="标题"
          hint={`可选，最多 ${NOTE_FORM_LIMITS.titleMaxLength} 个字符；留空时列表按正文首行显示。`}
          value={draft.title}
          maxLength={NOTE_FORM_LIMITS.titleMaxLength}
          onChange={(event) => patch({ title: event.target.value })}
        />

        <TextArea
          label="正文"
          hint={`必填，最多 ${NOTE_FORM_LIMITS.contentMaxLength} 个字符。`}
          rows={12}
          value={draft.content}
          maxLength={NOTE_FORM_LIMITS.contentMaxLength}
          onChange={(event) => patch({ content: event.target.value })}
        />

        <Select
          label="正文格式"
          hint="服务端只接受这两个值，其它值会被 400 拒绝；保存时一定会带上此字段，否则 Markdown 会被改回纯文本。"
          value={draft.contentFormat}
          onChange={(event) => patch({ contentFormat: event.target.value })}
        >
          {NOTE_CONTENT_FORMATS.map((format) => (
            <option key={format} value={format}>
              {format}（{contentFormatLabel(format)}）
            </option>
          ))}
        </Select>

        <fieldset className="field">
          <legend className="field__label">关联来源内容</legend>
          {POINTER_OPTIONS.map((kind) => (
            <label key={kind} htmlFor={`note-edit-pointer-${kind}`}>
              <input
                id={`note-edit-pointer-${kind}`}
                type="radio"
                name="note-edit-source-pointer"
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
          hint="保存时必须带上，否则服务端会把已有的知识点关联清空（PUT 是整篇替换）。"
          inputMode="numeric"
          value={draft.knowledgePointId}
          onChange={(event) => patch({ knowledgePointId: event.target.value })}
        />

        <Input
          label="题目 ID（可选）"
          hint="同上：留空表示不关联题目。"
          inputMode="numeric"
          value={draft.questionId}
          onChange={(event) => patch({ questionId: event.target.value })}
        />

        <div className="form__actions">
          <Button
            type="submit"
            variant="primary"
            disabled={save.isPending || archive.isPending || remove.isPending}
          >
            {save.isPending ? '保存中…' : '保存'}
          </Button>
          {dirty && (
            <span className="muted" role="status" data-testid="note-dirty-flag">
              有未保存的修改
            </span>
          )}
          <Link to={`/spaces/${spaceId}/notes`} className="nav-link">
            取消并返回
          </Link>
        </div>

        {failing && failing.isError && (
          <p className="form__error" role="alert" data-testid="note-mutation-error">
            {normalizeApiError(failing.error).message}
          </p>
        )}
      </form>

      {confirming === 'archive' && (
        <Dialog
          title="归档这条笔记？"
          onClose={() => setConfirming(null)}
          busy={archive.isPending}
        >
          <p>
            归档会写入归档时间，笔记随即从列表中消失，且契约没有取消归档接口：
            之后只能靠本页的链接继续访问。若要彻底移除，请改用删除。
          </p>
          <div className="dialog__actions">
            <Button type="button" onClick={() => setConfirming(null)}>
              取消
            </Button>
            <Button
              type="button"
              variant="primary"
              disabled={archive.isPending}
              onClick={() => archive.mutate()}
            >
              {archive.isPending ? '归档中…' : '确认归档'}
            </Button>
          </div>
        </Dialog>
      )}

      {confirming === 'delete' && (
        <Dialog
          title="删除这条笔记？"
          onClose={() => setConfirming(null)}
          busy={remove.isPending}
        >
          <p>
            删除是硬删除：笔记正文、知识点关联与正文片段关联会一起移除，无法撤销。
          </p>
          <div className="dialog__actions">
            <Button type="button" onClick={() => setConfirming(null)}>
              取消
            </Button>
            <Button
              type="button"
              variant="danger"
              disabled={remove.isPending}
              onClick={() => remove.mutate()}
            >
              {remove.isPending ? '删除中…' : '确认删除'}
            </Button>
          </div>
        </Dialog>
      )}
    </>
  );
}

/**
 * W16 read side: the pointers the server stores (which may be more than what the
 * form sent, because a block fixes its page and source) rendered as real links.
 */
function StoredContextSection({
  spaceId,
  note,
}: {
  spaceId: number;
  note: NoteDocument;
}) {
  const readerHref = sourceReaderHref(spaceId, note);
  const sourceLabel = isPositiveId(note.contentBlockId)
    ? `正文片段 #${note.contentBlockId}`
    : isPositiveId(note.sourcePageId)
      ? `来源页 #${note.sourcePageId}`
      : isPositiveId(note.sourceId)
        ? `学习来源 #${note.sourceId}`
        : null;

  return (
    <section className="workbench-section" aria-label="已存关联">
      <h2 className="section-title">已存关联</h2>
      <ul className="point-list" data-testid="note-stored-context">
        {sourceLabel === null && (
          <li className="muted">没有关联来源内容。</li>
        )}
        {sourceLabel !== null && (
          <li>
            来源：{' '}
            {readerHref !== null ? (
              <Link to={readerHref} className="nav-link">
                {sourceLabel}
              </Link>
            ) : (
              /* No usable source id means no reader route to build. */
              <span className="muted">{sourceLabel}</span>
            )}
            {isPositiveId(note.sourceId) && ` · 学习来源 #${note.sourceId}`}
            {isPositiveId(note.sourcePageId) && ` · 来源页 #${note.sourcePageId}`}
          </li>
        )}
        {isPositiveId(note.knowledgePointId) && (
          <li>
            知识点：{' '}
            <Link
              to={knowledgePointHref(spaceId, note.knowledgePointId)}
              className="nav-link"
            >
              #{note.knowledgePointId}
            </Link>
          </li>
        )}
        {isPositiveId(note.questionId) && (
          <li>
            题目：{' '}
            <Link to={questionHref(spaceId, note.questionId)} className="nav-link">
              #{note.questionId}
            </Link>
          </li>
        )}
        {!isPositiveId(note.knowledgePointId) &&
          !isPositiveId(note.questionId) && (
            <li className="muted">没有关联知识点或题目。</li>
          )}
      </ul>
    </section>
  );
}

/** M:N knowledge-point links: list / add / remove, each id a real route. */
function KnowledgePointLinks({
  spaceId,
  noteId,
}: {
  spaceId: number;
  noteId: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState('');

  const linksKey = queryKeys.noteKnowledgePoints(spaceId, noteId);
  const linksQuery = useQuery(
    entityQuery<number[]>(
      spaceId,
      noteId,
      linksKey,
      (id, note) => api.listNoteKnowledgePoints(id, note)
    )
  );

  /** The space's own points: the only ids the backend would accept. */
  const catalogQuery = useQuery(
    spaceQuery(spaceId, queryKeys.knowledgePoints(spaceId), (id) =>
      api.listKnowledgePoints(id)
    )
  );

  const linked = linksQuery.data ?? [];
  const candidates: Array<{ id: number; title: string }> = [];
  for (const point of catalogQuery.data ?? []) {
    const id = point.id;
    if (isPositiveId(id) && !linked.includes(id)) {
      candidates.push({ id, title: point.title ?? `知识点 #${id}` });
    }
  }

  const invalidateLinks = () => {
    void queryClient.invalidateQueries({ queryKey: linksKey });
  };

  const add = useMutation({
    mutationFn: (knowledgePointId: number) =>
      api.addNoteKnowledgePoints(spaceId, noteId, [knowledgePointId]).then(unwrap),
    onSuccess: () => {
      setSelected('');
      invalidateLinks();
    },
  });

  const unlink = useMutation({
    mutationFn: (knowledgePointId: number) =>
      api.removeNoteKnowledgePoint(spaceId, noteId, knowledgePointId).then(unwrap),
    onSuccess: () => invalidateLinks(),
  });

  const titleById = new Map<number, string>();
  for (const point of catalogQuery.data ?? []) {
    if (isPositiveId(point.id) && point.title) {
      titleById.set(point.id, point.title);
    }
  }

  return (
    <section className="workbench-section" aria-label="关联知识点">
      <h2 className="section-title">关联知识点</h2>

      {linksQuery.isPending && <p className="muted">加载知识点关联…</p>}
      {linksQuery.isError && (
        <>
          <p className="form__error" role="alert">
            {normalizeApiError(linksQuery.error).message}
          </p>
          <Button type="button" onClick={() => void linksQuery.refetch()}>
            重试
          </Button>
        </>
      )}
      {linksQuery.isSuccess && linked.length === 0 && (
        <p className="muted">尚未关联任何知识点。</p>
      )}
      {linksQuery.isSuccess && linked.length > 0 && (
        <ul className="point-list" data-testid="note-knowledge-point-links">
          {linked.map((knowledgePointId) => (
            <li key={knowledgePointId}>
              <Link
                to={knowledgePointHref(spaceId, knowledgePointId)}
                className="nav-link"
              >
                {titleById.get(knowledgePointId) ?? `知识点 #${knowledgePointId}`}
              </Link>
              <Button
                type="button"
                onClick={() => unlink.mutate(knowledgePointId)}
                disabled={unlink.isPending}
              >
                取消关联
              </Button>
            </li>
          ))}
        </ul>
      )}

      {catalogQuery.isPending && <p className="muted">加载知识点目录…</p>}
      {catalogQuery.isError && (
        <>
          <p className="form__error" role="alert">
            {normalizeApiError(catalogQuery.error).message}
          </p>
          <p className="muted">
            知识点目录读不出来时不提供关联按钮：服务端对陌生 ID 只返回 201
            而不写入任何关联，无法核对 ID 的关联不能假装成功。
          </p>
          <Button type="button" onClick={() => void catalogQuery.refetch()}>
            重试
          </Button>
        </>
      )}
      {catalogQuery.isSuccess && candidates.length === 0 && (
        <p className="muted">
          {linked.length > 0
            ? '本空间的知识点都已关联。'
            : '这个学习空间还没有知识点。'}
        </p>
      )}
      {catalogQuery.isSuccess && candidates.length > 0 && (
        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            const knowledgePointId = parseOptionalPositiveId(selected);
            if (knowledgePointId === undefined || add.isPending) {
              return;
            }
            add.mutate(knowledgePointId);
          }}
        >
          <Select
            label="选择要关联的知识点"
            hint="列表来自本空间的知识点：服务端对陌生 ID 只返回 201 而不写入任何关联，所以这里不接受手填 ID。"
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
          >
            <option value="">请选择…</option>
            {candidates.map((point) => (
              <option key={point.id} value={String(point.id)}>
                {point.title}（#{point.id}）
              </option>
            ))}
          </Select>
          {add.isError && (
            <p className="form__error" role="alert">
              {normalizeApiError(add.error).message}
            </p>
          )}
          <div className="form__actions">
            <Button
              type="submit"
              variant="primary"
              disabled={parseOptionalPositiveId(selected) === undefined || add.isPending}
            >
              {add.isPending ? '关联中…' : '关联知识点'}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

/**
 * M:N content-block links. Rows are contract `SourceReference`s, so each one
 * deep-links the reader at its own page and block
 * (`/spaces/:spaceId/sources/:sourceId?page=&block=`).
 */
function SourceBlockLinks({
  spaceId,
  noteId,
}: {
  spaceId: number;
  noteId: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [sourceValue, setSourceValue] = useState('');
  const [blockValue, setBlockValue] = useState('');

  const linksKey = queryKeys.noteSourceBlocks(spaceId, noteId);
  const linksQuery = useQuery(
    entityQuery<NoteSourceLink[]>(
      spaceId,
      noteId,
      linksKey,
      (id, note) => api.listNoteSourceBlocks(id, note)
    )
  );

  const sourcesQuery = useQuery(
    spaceQuery(spaceId, queryKeys.sources(spaceId), (id) => api.listSources(id))
  );

  const sourceId = parseOptionalPositiveId(sourceValue);

  const blocksQuery = useQuery({
    queryKey: queryKeys.contentBlocks(spaceId, sourceId ?? null),
    queryFn: () =>
      api
        .listContentBlocks(spaceId, requireId(sourceId ?? null, 'source id'))
        .then(unwrap),
    enabled: sourceId !== undefined,
  });

  const linked = linksQuery.data ?? [];
  const linkedBlockIds = new Set<number>();
  for (const link of linked) {
    if (isPositiveId(link.contentBlockId)) {
      linkedBlockIds.add(link.contentBlockId);
    }
  }

  const candidates: Array<{ id: number; label: string }> = [];
  for (const block of blocksQuery.data ?? []) {
    const id = block.id;
    if (isPositiveId(id) && !linkedBlockIds.has(id)) {
      const text = (block.normalizedText ?? '').replace(/\s+/g, ' ').trim();
      const preview = text.length > 40 ? `${text.slice(0, 40)}…` : text;
      candidates.push({
        id,
        label: [
          `#${id}`,
          block.blockType ?? '—',
          typeof block.sortOrder === 'number' ? `第 ${block.sortOrder} 项` : null,
          preview === '' ? null : preview,
        ]
          .filter((part): part is string => part !== null)
          .join(' · '),
      });
    }
  }

  const invalidateLinks = () => {
    void queryClient.invalidateQueries({ queryKey: linksKey });
  };

  const add = useMutation({
    mutationFn: (contentBlockId: number) =>
      api.addNoteSourceBlocks(spaceId, noteId, [contentBlockId]).then(unwrap),
    onSuccess: () => {
      setBlockValue('');
      invalidateLinks();
    },
  });

  const unlink = useMutation({
    mutationFn: (contentBlockId: number) =>
      api.removeNoteSourceBlock(spaceId, noteId, contentBlockId).then(unwrap),
    onSuccess: () => invalidateLinks(),
  });

  const sourceOptions: Array<{ id: number; title: string }> = [];
  for (const source of sourcesQuery.data ?? []) {
    if (isPositiveId(source.id)) {
      sourceOptions.push({ id: source.id, title: source.title ?? `来源 #${source.id}` });
    }
  }

  return (
    <section className="workbench-section" aria-label="关联正文片段">
      <h2 className="section-title">关联正文片段</h2>

      {linksQuery.isPending && <p className="muted">加载片段关联…</p>}
      {linksQuery.isError && (
        <>
          <p className="form__error" role="alert">
            {normalizeApiError(linksQuery.error).message}
          </p>
          <Button type="button" onClick={() => void linksQuery.refetch()}>
            重试
          </Button>
        </>
      )}
      {linksQuery.isSuccess && linked.length === 0 && (
        <p className="muted">尚未关联任何正文片段。</p>
      )}
      {linksQuery.isSuccess && linked.length > 0 && (
        <ul className="point-list" data-testid="note-source-block-links">
          {linked.map((link, index) => {
            const href = sourceReaderHref(spaceId, link);
            const blockId = link.contentBlockId;
            const blockLabel = isPositiveId(blockId)
              ? `正文片段 #${blockId}`
              : '正文片段 #—';
            const pageLabel = isPositiveId(link.sourcePageId)
              ? ` · 来源页 #${link.sourcePageId}`
              : '';
            const locator = link.locator ? ` · ${link.locator}` : '';
            return (
              <li key={link.contentBlockId ?? `link-${index}`}>
                {href !== null ? (
                  <Link to={href} className="nav-link">
                    {(link.sourceTitle ?? '').trim() === ''
                      ? blockLabel
                      : `${link.sourceTitle} · ${blockLabel}`}
                  </Link>
                ) : (
                  /* A reference without a source id has no reader route. */
                  <span className="muted">{blockLabel}</span>
                )}
                <span className="muted">
                  {pageLabel}
                  {locator}
                </span>
                {isPositiveId(blockId) && (
                  <Button
                    type="button"
                    onClick={() => unlink.mutate(blockId)}
                    disabled={unlink.isPending}
                  >
                    取消关联
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {sourcesQuery.isPending && <p className="muted">加载学习来源…</p>}
      {sourcesQuery.isError && (
        <>
          <p className="form__error" role="alert">
            {normalizeApiError(sourcesQuery.error).message}
          </p>
          <Button type="button" onClick={() => void sourcesQuery.refetch()}>
            重试
          </Button>
        </>
      )}
      {sourcesQuery.isSuccess && sourceOptions.length > 0 && (
        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            const contentBlockId = parseOptionalPositiveId(blockValue);
            if (contentBlockId === undefined || add.isPending) {
              return;
            }
            add.mutate(contentBlockId);
          }}
        >
          <Select
            label="选择学习来源"
            hint="片段的 ID 由服务端校验归属；陌生 ID 只会返回 201 而不写入，所以这里逐级选择而不是手填。"
            value={sourceValue}
            onChange={(event) => {
              setSourceValue(event.target.value);
              setBlockValue('');
            }}
          >
            <option value="">请选择…</option>
            {sourceOptions.map((source) => (
              <option key={source.id} value={String(source.id)}>
                {source.title}（#{source.id}）
              </option>
            ))}
          </Select>

          {sourceId !== undefined && blocksQuery.isPending && (
            <p className="muted">加载正文片段…</p>
          )}
          {sourceId !== undefined && blocksQuery.isError && (
            <>
              <p className="form__error" role="alert">
                {normalizeApiError(blocksQuery.error).message}
              </p>
              <Button type="button" onClick={() => void blocksQuery.refetch()}>
                重试
              </Button>
            </>
          )}
          {sourceId !== undefined &&
            blocksQuery.isSuccess &&
            candidates.length === 0 && (
              <p className="muted">这个来源没有可关联的正文片段（尚未抽取或都已关联）。</p>
            )}
          {sourceId !== undefined && candidates.length > 0 && (
            <Select
              label="选择要关联的正文片段"
              value={blockValue}
              onChange={(event) => setBlockValue(event.target.value)}
            >
              <option value="">请选择…</option>
              {candidates.map((block) => (
                <option key={block.id} value={String(block.id)}>
                  {block.label}
                </option>
              ))}
            </Select>
          )}

          {add.isError && (
            <p className="form__error" role="alert">
              {normalizeApiError(add.error).message}
            </p>
          )}
          <div className="form__actions">
            <Button
              type="submit"
              variant="primary"
              disabled={
                parseOptionalPositiveId(blockValue) === undefined || add.isPending
              }
            >
              {add.isPending ? '关联中…' : '关联正文片段'}
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

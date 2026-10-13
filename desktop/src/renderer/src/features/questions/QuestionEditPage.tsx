/**
 * Question Edit (G04) — the `PUT /questions/{id}` subset, and nothing else.
 *
 * /spaces/:spaceId/questions/:questionId/edit
 *
 * `UpdateQuestionRequest` is exactly `stem`, `explanation`, `difficulty`
 * (verified in openapi.json and in `UpdateQuestionRequest.java:13-21`), and
 * `QuestionMapper.updateDetailsByIdAndSpace` (QuestionMapper.java:107-115)
 * writes those three columns and no others. Options and the correct answer have
 * no update verb at all — `QuestionOptionMapper` has no update or delete
 * statement in V1 — so this form shows them read-only and names the reason
 * instead of accepting edits it could not send.
 *
 * Two server behaviors shape the form:
 *  - `QuestionService.update()` (QuestionService.java:122-142) treats a null
 *    field as "keep what is stored", so all three fields are always sent; a
 *    blank `explanation` therefore really does clear it.
 *  - the same method returns null for an ARCHIVED question, which the
 *    controller turns into a 404 (QuestionController.java:72-76), so an
 *    archived question has no editable form to open.
 *
 * The update DTO is also NARROWER than the create DTO on `explanation`
 * (2000 vs 4000 characters), which is why the caps come from
 * `UPDATE_QUESTION_FIELD_LIMITS` and not from `QUESTION_FIELD_LIMITS`.
 */

import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { entityQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { parsePositiveIdParam } from '../../lib/ids';
import {
  CONTRACT_GAP_NOTICES,
  QUESTION_FIELD_LIMITS,
  difficultyLabel,
  questionTypeLabel,
} from '../../lib/format';
import type { components } from '@aistudy/api-client';
type UpdateQuestionRequest = components['schemas']['UpdateQuestionRequest'];
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import {
  UPDATE_QUESTION_FIELD_LIMITS,
  validateQuestionEdit,
} from './question-form-state';

const STATUS_ARCHIVED = 'ARCHIVED';

export function QuestionEditPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();
  const { questionId: questionIdParam } = useParams();
  const questionId = parsePositiveIdParam(questionIdParam);

  const query = useQuery(
    entityQuery(
      spaceId,
      questionId,
      queryKeys.question(spaceId, questionId),
      (id, qid) => api.getQuestion(id, qid)
    )
  );

  const loaded = useMemo(() => {
    const q = query.data;
    if (!q) {
      return null;
    }
    return {
      stem: q.stem ?? '',
      explanation: q.explanation ?? '',
      difficulty: q.difficulty ?? '',
    };
  }, [query.data]);

  const [stem, setStem] = useState<string | null>(null);
  const [explanation, setExplanation] = useState<string | null>(null);
  const [difficulty, setDifficulty] = useState<string | null>(null);
  const [showErrors, setShowErrors] = useState(false);

  const save = useMutation({
    mutationFn: () => {
      // Every field is always sent: `update()` keeps the stored value when a
      // field is null, so omitting one would make 清空 impossible to express.
      const body: UpdateQuestionRequest = {
        stem: draft.stem.trim(),
        explanation: draft.explanation,
        difficulty: draft.difficulty,
      };
      return api
        .updateQuestion(
          requireId(spaceId, 'space id'),
          requireId(questionId, 'question id'),
          body
        )
        .then(unwrap);
    },
    onSuccess: () => {
      // The detail page renders stem/explanation/difficulty straight out of
      // this key, and the list badge renders the stem, so both are stale.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.question(spaceId, questionId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.questions(spaceId),
      });
      if (spaceId !== null && questionId !== null) {
        navigate(`/spaces/${spaceId}/questions/${questionId}`);
      }
    },
  });

  if (spaceId === null || questionId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }
  if (query.isPending) {
    return <LoadingState text="加载题目…" />;
  }
  if (query.isError) {
    return (
      <ErrorState
        message={normalizeApiError(query.error).message}
        onRetry={() => void query.refetch()}
      />
    );
  }
  const q = query.data;
  if (!q || !loaded) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  // Until the user types, the draft is the stored document. Keeping the raw
  // loaded value separate from the edited value is what makes `dirty` and
  // 丢弃修改 both meaningful without a form library.
  const draft = {
    stem: stem ?? loaded.stem,
    explanation: explanation ?? loaded.explanation,
    difficulty: difficulty ?? loaded.difficulty,
  };

  const status = (q.status ?? '').trim().toUpperCase();
  const isArchived = status === STATUS_ARCHIVED;
  const errors = validateQuestionEdit(draft);
  const dirty =
    draft.stem !== loaded.stem ||
    draft.explanation !== loaded.explanation ||
    draft.difficulty !== loaded.difficulty;
  const visibleErrors = showErrors ? errors : [];
  const busy = save.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setShowErrors(true);
    if (busy || errors.length > 0) {
      return;
    }
    save.mutate();
  }

  return (
    <div className="page">
      <PageHeader
        title={`编辑题目 #${q.id}`}
        actions={
          <>
            <StatusBadge status={q.status} />
            <Link
              to={`/spaces/${spaceId}/questions/${questionId}`}
              className="nav-link"
              data-testid="question-detail-link"
            >
              返回详情
            </Link>
          </>
        }
      />

      <p className="page__note" role="note" data-testid="update-gap-notice">
        {CONTRACT_GAP_NOTICES.QUESTION_UPDATE_FIELDS}
      </p>

      {isArchived && (
        <p className="form__error" role="alert" data-testid="archived-edit-blocked">
          本题已归档，PUT /questions 对 ARCHIVED 题目直接返回 404
          （QuestionService.java:124），所以本页不可保存。请先在详情页恢复为草稿。
        </p>
      )}

      <form className="form" onSubmit={handleSubmit} noValidate>
        <div className="form__field">
          <label htmlFor="edit-type" className="form__label">题型</label>
          <input
            id="edit-type"
            className="input"
            value={questionTypeLabel(q.questionType)}
            readOnly
            disabled
          />
          <p className="field__hint">
            题型不在 UpdateQuestionRequest 里，改题型等于换一道题。
          </p>
        </div>

        <div className="form__field">
          <label htmlFor="edit-stem" className="form__label">题干</label>
          <textarea
            id="edit-stem"
            className="textarea"
            value={draft.stem}
            onChange={(e) => setStem(e.target.value)}
            rows={3}
            required
            maxLength={UPDATE_QUESTION_FIELD_LIMITS.stem}
            disabled={busy || isArchived}
          />
        </div>

        <div className="form__field">
          <label htmlFor="edit-explanation" className="form__label">解析</label>
          <textarea
            id="edit-explanation"
            className="textarea"
            value={draft.explanation}
            onChange={(e) => setExplanation(e.target.value)}
            rows={2}
            maxLength={UPDATE_QUESTION_FIELD_LIMITS.explanation}
            disabled={busy || isArchived}
          />
          <p className="field__hint">
            编辑上限是 {UPDATE_QUESTION_FIELD_LIMITS.explanation} 个字符，
            比创建时的 {QUESTION_FIELD_LIMITS.explanation} 个更严
            （UpdateQuestionRequest.explanation 的 maxLength），清空解析会被保存。
          </p>
        </div>

        <div className="form__field">
          <label htmlFor="edit-difficulty" className="form__label">难度</label>
          <select
            id="edit-difficulty"
            className="select"
            value={draft.difficulty}
            onChange={(e) => setDifficulty(e.target.value)}
            disabled={busy || isArchived}
          >
            <option value="">—</option>
            <option value="EASY">简单</option>
            <option value="MEDIUM">中等</option>
            <option value="HARD">困难</option>
          </select>
        </div>

        <div className="form__field" data-testid="locked-fields">
          <span className="form__label">不可修改的部分</span>
          <p className="form__note" role="note">
            当前难度：{difficultyLabel(q.difficulty)} · 选项与正确答案无法修改
            （UpdateQuestionRequest 只有 stem / explanation / difficulty，
            QuestionOptionMapper 在 V1 没有 update / delete 语句）。
            要改答案只能新建一道题。
          </p>
          {q.options && q.options.length > 0 && (
            <ul className="point-list" data-testid="locked-options">
              {q.options.map((o, idx) => (
                <li key={o.optionKey ?? `opt-${idx}`} data-testid="locked-option">
                  {o.optionKey}. {o.content}
                  {o.isCorrect ? '（正确）' : ''}
                </li>
              ))}
            </ul>
          )}
        </div>

        {visibleErrors.length > 0 && (
          <ul className="form__error" role="alert" data-testid="edit-form-errors">
            {visibleErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        )}

        {save.isError && (
          <p className="form__error" role="alert" data-testid="edit-mutation-error">
            {normalizeApiError(save.error).message}
          </p>
        )}

        <div className="form__actions">
          {/* Not disabled for a validation error: the click prints the list
              above, so 保存 is never a dead button. It IS disabled while
              archived, and the reason is the alert above it. */}
          <Button
            type="submit"
            variant="primary"
            disabled={busy || isArchived}
            data-testid="edit-save-button"
          >
            {busy ? '保存中…' : '保存'}
          </Button>
          {dirty && !busy && (
            <span className="muted" role="status" data-testid="edit-dirty-flag">
              有未保存的修改
            </span>
          )}
          <Button
            type="button"
            variant="secondary"
            onClick={() => navigate(`/spaces/${spaceId}/questions/${questionId}`)}
          >
            取消并返回
          </Button>
        </div>
      </form>
    </div>
  );
}

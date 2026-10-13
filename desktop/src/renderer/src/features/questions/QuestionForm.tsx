/**
 * Question Authoring (A4.4, G02, W09) — create only.
 *
 * There is deliberately no edit mode: the contract exposes
 * `POST /questions` and `POST /questions/{id}/publish` but no update
 * endpoint, so a "save" could only ever create a duplicate. See
 * BACKEND-CONTRACT-REQUESTS.md (G04 / G09) for the requested verbs.
 *
 * Which types may be created is not a designer's guess — it is whatever
 * `lib/format.ts` can prove the contract supports end to end. ORDERING and
 * MATCHING stay listed (a backend that already has them must not be hidden)
 * but disabled, each with the exact missing field named.
 *
 * Every limit below comes from openapi.json (`maxLength` / `maxItems`); the
 * per-type required answers mirror what the create endpoint validates, so a
 * form that passes here cannot 400 for shape.
 */

import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { NOT_FOUND_MESSAGE, normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { isPositiveId } from '../../lib/ids';
import { Button } from '../../components/Button';
import type { components } from '@aistudy/api-client';
type CreateQuestionRequest = components['schemas']['CreateQuestionRequest'];
import { ErrorState } from '../../components/ErrorState';
import { PageHeader } from '../../components/PageHeader';
import {
  AUTHORABLE_QUESTION_TYPES,
  QUESTION_FIELD_LIMITS,
  QUESTION_TYPES,
  isAuthorableQuestionType,
  questionTypeGap,
  questionTypeLabel,
  type AuthorableQuestionType,
} from '../../lib/format';
import {
  validateQuestionForm,
  type OptionInput,
} from './question-form-rules';

/** The types this form shows but refuses, with the contract gap for each. */
const BLOCKED_TYPES = QUESTION_TYPES.filter((t) => !isAuthorableQuestionType(t));

export function QuestionForm() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();

  const [questionType, setQuestionType] = useState<AuthorableQuestionType>('SINGLE_CHOICE');
  const [stem, setStem] = useState('');
  const [explanation, setExplanation] = useState('');
  const [difficulty, setDifficulty] = useState('');
  const [options, setOptions] = useState<OptionInput[]>([
    { optionKey: 'A', content: '' },
    { optionKey: 'B', content: '' },
  ]);
  const [correctSingle, setCorrectSingle] = useState('');
  const [correctMultiple, setCorrectMultiple] = useState<string[]>([]);
  const [correctBoolean, setCorrectBoolean] = useState<boolean | null>(null);
  const [referenceAnswer, setReferenceAnswer] = useState('');
  const [knowledgePointIds, setKnowledgePointIds] = useState<number[]>([]);
  const [showValidation, setShowValidation] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const knowledgePointsQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.knowledgePoints(spaceId),
      (id) => api.listKnowledgePoints(id)
    )
  );

  const create = useMutation({
    mutationFn: () => {
      // Only fields the user filled are sent: a key set to `undefined` would
      // be an explicit "no value" on the wire instead of "not provided".
      const body: CreateQuestionRequest = {
        questionType,
        stem: stem.trim(),
      };
      if (explanation.trim() !== '') {
        body.explanation = explanation.trim();
      }
      if (difficulty !== '') {
        body.difficulty = difficulty;
      }
      if (knowledgePointIds.length > 0) {
        body.knowledgePointIds = knowledgePointIds;
      }
      if (questionType === 'SINGLE_CHOICE') {
        body.correctOptionKey = correctSingle.trim();
        body.options = options
          .map((o) => ({ optionKey: o.optionKey.trim(), content: o.content.trim() }));
      } else if (questionType === 'MULTIPLE_CHOICE') {
        body.correctOptionKeys = correctMultiple.map((k) => k.trim());
        body.options = options
          .map((o) => ({ optionKey: o.optionKey.trim(), content: o.content.trim() }));
      } else if (questionType === 'TRUE_FALSE') {
        // The create endpoint rejects TRUE_FALSE that carries options, so
        // none are sent.
        body.correctBoolean = correctBoolean ?? undefined;
      } else if (questionType === 'SHORT_ANSWER' || questionType === 'FILL_BLANK') {
        // Same rule for both text types: no options, reference answer only.
        body.referenceAnswer = referenceAnswer.trim();
      }
      return api
        .createQuestion(requireId(spaceId, 'space id'), body)
        .then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.questions(spaceId),
      });
      if (spaceId !== null) {
        navigate(`/spaces/${spaceId}/questions`);
      }
    },
    onError: (error) => {
      setServerError(normalizeApiError(error).message);
    },
  });

  const errors = useMemo(
    () =>
      validateQuestionForm({
        questionType,
        stem,
        options,
        correctSingle,
        correctMultiple,
        correctBoolean,
        referenceAnswer,
        explanation,
      }),
    [
      questionType,
      stem,
      options,
      correctSingle,
      correctMultiple,
      correctBoolean,
      referenceAnswer,
      explanation,
    ]
  );

  if (spaceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const busy = create.isPending;

  function addOption() {
    const nextKey = String.fromCharCode(65 + options.length);
    setOptions([...options, { optionKey: nextKey, content: '' }]);
  }

  function updateOptionKey(index: number, key: string) {
    const next = [...options];
    next[index] = { ...next[index], optionKey: key };
    setOptions(next);
  }

  function updateOptionContent(index: number, content: string) {
    const next = [...options];
    next[index] = { ...next[index], content };
    setOptions(next);
  }

  function removeOption(index: number) {
    setOptions(options.filter((_, i) => i !== index));
  }

  function toggleKnowledgePoint(id: number, checked: boolean) {
    setKnowledgePointIds((prev) =>
      checked ? [...new Set([...prev, id])] : prev.filter((k) => k !== id)
    );
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Duplicate-submit guard: a second click while the POST is in flight
    // would create a second question, and there is no way to undo it.
    if (busy) {
      return;
    }
    setServerError(null);
    if (errors.length > 0) {
      setShowValidation(true);
      return;
    }
    setShowValidation(false);
    create.mutate();
  }

  const visibleErrors = showValidation ? errors : [];
  // Only a positive integer id is selectable — a point without one cannot be
  // sent in `knowledgePointIds` (int64) and would silently corrupt the body.
  const points: Array<{ id: number; title: string }> = [];
  for (const kp of knowledgePointsQuery.data ?? []) {
    const kpId = kp.id;
    if (isPositiveId(kpId)) {
      points.push({ id: kpId, title: kp.title ?? `知识点 #${kpId}` });
    }
  }

  return (
    <div className="page">
      <PageHeader title="新建题目" />
      {/* noValidate: `required` stays for the a11y semantics, but the rules
          below (per type, per contract field) are the only ones that decide
          whether a submit goes out — two parallel validation systems would
          disagree about FILL_BLANK vs SHORT_ANSWER. */}
      <form className="form" onSubmit={handleSubmit} noValidate>
        <div className="form__field">
          <label htmlFor="q-type" className="form__label">题型</label>
          <select
            id="q-type"
            className="select"
            value={questionType}
            disabled={busy}
            onChange={(e) => setQuestionType(e.target.value as AuthorableQuestionType)}
          >
            {AUTHORABLE_QUESTION_TYPES.map((t) => (
              <option key={t} value={t}>{questionTypeLabel(t)}</option>
            ))}
            {BLOCKED_TYPES.map((t) => (
              <option key={t} value={t} disabled>
                {questionTypeLabel(t)}（暂不可创建）
              </option>
            ))}
          </select>
        </div>

        {BLOCKED_TYPES.length > 0 && (
          <div className="form__field" data-testid="authoring-gap-notes">
            {BLOCKED_TYPES.map((t) => (
              <p className="form__note" key={t} role="note">
                {questionTypeLabel(t)}：{questionTypeGap(t)}
              </p>
            ))}
          </div>
        )}

        <div className="form__field">
          <label htmlFor="q-stem" className="form__label">题干</label>
          <textarea
            id="q-stem"
            className="textarea"
            value={stem}
            onChange={(e) => setStem(e.target.value)}
            rows={3}
            required
            disabled={busy}
          />
        </div>

        {(questionType === 'SINGLE_CHOICE' || questionType === 'MULTIPLE_CHOICE') && (
          <div className="form__field">
            <label className="form__label">选项</label>
            {options.map((opt, idx) => (
              <div key={idx} className="form__row">
                <input
                  className="input form__row-key"
                  aria-label={`选项 ${idx + 1} 标识`}
                  data-testid={`option-key-${idx}`}
                  value={opt.optionKey}
                  onChange={(e) => updateOptionKey(idx, e.target.value)}
                  maxLength={QUESTION_FIELD_LIMITS.optionKey}
                  disabled={busy}
                />
                <input
                  className="input form__row-main"
                  aria-label={`选项 ${idx + 1} 内容`}
                  data-testid={`option-content-${idx}`}
                  value={opt.content}
                  onChange={(e) => updateOptionContent(idx, e.target.value)}
                  placeholder={`选项 ${opt.optionKey}`}
                  maxLength={QUESTION_FIELD_LIMITS.optionContent}
                  disabled={busy}
                />
                <Button
                  type="button"
                  variant="danger"
                  onClick={() => removeOption(idx)}
                  disabled={options.length <= 2 || busy}
                >
                  删除
                </Button>
              </div>
            ))}
            <Button
              type="button"
              variant="secondary"
              onClick={addOption}
              disabled={busy}
            >
              添加选项
            </Button>
          </div>
        )}

        {questionType === 'SINGLE_CHOICE' && (
          <div className="form__field">
            <label htmlFor="q-correct" className="form__label">正确答案</label>
            <select
              id="q-correct"
              className="select"
              value={correctSingle}
              onChange={(e) => setCorrectSingle(e.target.value)}
              disabled={busy}
            >
              <option value="">—</option>
              {options.map((o) => (
                <option key={o.optionKey} value={o.optionKey}>{o.optionKey}: {o.content || '（空）'}</option>
              ))}
            </select>
          </div>
        )}

        {questionType === 'MULTIPLE_CHOICE' && (
          <div className="form__field">
            <label className="form__label">正确选项（可多选）</label>
            <div className="form__checkboxes">
              {options.map((o) => (
                <label key={o.optionKey} className="form__checkbox">
                  <input
                    type="checkbox"
                    checked={correctMultiple.includes(o.optionKey)}
                    onChange={(e) => {
                      if (e.target.checked) {
                        setCorrectMultiple((prev) =>
                          prev.includes(o.optionKey) ? prev : [...prev, o.optionKey]
                        );
                      } else {
                        setCorrectMultiple(correctMultiple.filter((k) => k !== o.optionKey));
                      }
                    }}
                    disabled={busy}
                  />
                  {o.optionKey}: {o.content || '（空）'}
                </label>
              ))}
            </div>
          </div>
        )}

        {questionType === 'TRUE_FALSE' && (
          <div className="form__field">
            <span className="form__label">正确答案</span>
            <div className="form__radios">
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === true}
                  onChange={() => setCorrectBoolean(true)}
                  disabled={busy}
                />
                正确
              </label>
              <label className="form__radio">
                <input
                  type="radio"
                  name="correct-bool"
                  checked={correctBoolean === false}
                  onChange={() => setCorrectBoolean(false)}
                  disabled={busy}
                />
                错误
              </label>
            </div>
          </div>
        )}

        {(questionType === 'SHORT_ANSWER' || questionType === 'FILL_BLANK') && (
          <div className="form__field">
            <label htmlFor="q-ref" className="form__label">参考答案</label>
            <textarea
              id="q-ref"
              className="textarea"
              value={referenceAnswer}
              onChange={(e) => setReferenceAnswer(e.target.value)}
              rows={2}
              required
              maxLength={QUESTION_FIELD_LIMITS.referenceAnswer}
              disabled={busy}
              placeholder={
                questionType === 'FILL_BLANK'
                  ? '按空依次填写，多个空用英文逗号分隔'
                  : undefined
              }
            />
            {questionType === 'FILL_BLANK' && (
              <p className="field__hint">
                填空答案走 AnswerPayloadView.textAnswer，参考答案走
                CreateQuestionRequest.referenceAnswer。判分是<strong>整串精确比对</strong>
                （只忽略首尾空白）：学员答案的分隔符、顺序、空格必须与参考答案一致，
                答对其中一部分不会得部分分。所以多个空请统一用「英文逗号+无空格」写，
                例如 <code>北京,上海</code>；用中文逗号或加空格都会被判错。
              </p>
            )}
          </div>
        )}

        <div className="form__field">
          <label htmlFor="q-explanation" className="form__label">解析</label>
          <textarea
            id="q-explanation"
            className="textarea"
            value={explanation}
            onChange={(e) => setExplanation(e.target.value)}
            rows={2}
            maxLength={QUESTION_FIELD_LIMITS.explanation}
            disabled={busy}
          />
        </div>

        <div className="form__field">
          <label htmlFor="q-difficulty" className="form__label">难度</label>
          <select
            id="q-difficulty"
            className="select"
            value={difficulty}
            onChange={(e) => setDifficulty(e.target.value)}
            disabled={busy}
          >
            <option value="">—</option>
            <option value="EASY">简单</option>
            <option value="MEDIUM">中等</option>
            <option value="HARD">困难</option>
          </select>
        </div>

        <div className="form__field">
          <span className="form__label" id="q-kp-label">关联知识点</span>
          {knowledgePointsQuery.isPending && (
            <p className="field__hint" data-testid="kp-loading">加载知识点…</p>
          )}
          {knowledgePointsQuery.isError && (
            <p className="field__hint" role="alert">
              知识点列表加载失败，可先不关联，稍后重试。
            </p>
          )}
          {knowledgePointsQuery.isSuccess && points.length === 0 && (
            <p className="field__hint" data-testid="kp-empty">
              这个空间还没有知识点，可先跳过关联。
            </p>
          )}
          <div
            className="form__checkboxes"
            role="group"
            aria-labelledby="q-kp-label"
            data-testid="kp-picker"
          >
            {points.map((point) => (
              <label key={point.id} className="form__checkbox">
                <input
                  type="checkbox"
                  data-testid={`kp-option-${point.id}`}
                  checked={knowledgePointIds.includes(point.id)}
                  onChange={(e) => toggleKnowledgePoint(point.id, e.target.checked)}
                  disabled={busy}
                />
                {point.title}
              </label>
            ))}
          </div>
          {knowledgePointIds.length > 0 && (
            <p className="field__hint">
              已选 {knowledgePointIds.length} 个知识点：
              {knowledgePointIds.map((id) => `#${id}`).join('、')}
            </p>
          )}
        </div>

        {visibleErrors.length > 0 && (
          <div className="form__error" role="alert" data-testid="validation-errors">
            <ul>
              {visibleErrors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          </div>
        )}

        {serverError && (
          <p className="form__error" role="alert">{serverError}</p>
        )}

        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? '创建中…' : '创建'}
          </Button>
          <Button type="button" variant="secondary" onClick={() => navigate(`/spaces/${spaceId}/questions`)}>
            取消
          </Button>
        </div>
      </form>
    </div>
  );
}

/**
 * Question Authoring (A4.4, G02, G09, W09) — create only.
 *
 * The edit verb is a separate page (`QuestionEditPage.tsx`, G04): this form
 * only creates, because a create form that "saved" twice would create twice
 * and there is no way to undo the first one.
 *
 * Which types may be created is not a designer's guess — it is whatever
 * `lib/format.ts` can prove the contract supports, and the contract now carries
 * `correctOrder` / `matches` on the create side and `orderingAnswer` /
 * `matchingAnswer` on the answer side, so all seven types are authorable.
 *
 * What is still one-way is the stored answer for the two new types:
 * `QuestionAnswerView` has no `correctOrder` / `matchesJson`, so nothing here
 * pretends to read them back — the notice under each widget is
 * `QUESTION_TYPE_DISPLAY_GAP`, the same copy the review surface uses.
 *
 * Every limit below comes from openapi.json (`maxLength` / `maxItems`); the
 * per-type required answers mirror what `QuestionService.validateStructure()`
 * enforces, so a form that passes here cannot 400 for shape.
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
  QUESTION_TYPE_DISPLAY_GAP,
  isAuthorableQuestionType,
  questionTypeGap,
  questionTypeLabel,
  type AuthorableQuestionType,
  type QuestionType,
} from '../../lib/format';
import {
  validateQuestionForm,
  type OptionInput,
} from './question-form-rules';
import {
  initialOrderingSequence,
  moveInSequence,
  placeAtEnd,
  serializeMatches,
  sequenceAfterAdd,
  sequenceAfterRemove,
  toCorrectOrder,
  unplacedItemIndexes,
  unplaceAtPosition,
  validateMatchingAuthoring,
  validateOrderingAuthoring,
  type MatchPair,
} from './question-form-state';

/** The types this form shows but refuses, with the contract gap for each. */
const BLOCKED_TYPES = QUESTION_TYPES.filter((t) => !isAuthorableQuestionType(t));

/** Types whose items are entered as options (`QuestionOptionInput` rows). */
const ITEM_TYPES: readonly QuestionType[] = [
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'ORDERING',
];

const EMPTY_PAIR: MatchPair = { left: '', right: '' };

/** The copy that names the read-side gap of the two new types. */
function oneWayNotice(type: QuestionType): string | null {
  return QUESTION_TYPE_DISPLAY_GAP[type]?.review ?? questionTypeGap(type);
}

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
  /** 0-based indexes into `options`, in the standard order. See question-form-state.ts. */
  const [ordering, setOrdering] = useState<number[]>(() => initialOrderingSequence(2));
  const [pairs, setPairs] = useState<MatchPair[]>([{ ...EMPTY_PAIR }, { ...EMPTY_PAIR }]);
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
      } else if (questionType === 'ORDERING') {
        // ORDERING IS the option list plus the 1-based standard order of it:
        // `insertOptions()` stores sortOrder as the array index, so the
        // position `toCorrectOrder()` emits is exactly what the answer side
        // will read back from `QuestionOptionResponse.sortOrder` (+1).
        body.options = options
          .map((o) => ({ optionKey: o.optionKey.trim(), content: o.content.trim() }));
        body.correctOrder = toCorrectOrder(ordering);
      } else if (questionType === 'MATCHING') {
        // `matches` is a string the server stores verbatim and grades by
        // whole-map equality, so `serializeMatches` is the only shape allowed.
        body.matches = serializeMatches(pairs);
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

  const errors = useMemo(() => {
    const common = validateQuestionForm({
      questionType,
      stem,
      options,
      correctSingle,
      correctMultiple,
      correctBoolean,
      referenceAnswer,
      explanation,
    });
    if (questionType === 'ORDERING') {
      return [...common, ...validateOrderingAuthoring(options, ordering)];
    }
    if (questionType === 'MATCHING') {
      return [...common, ...validateMatchingAuthoring(pairs)];
    }
    return common;
  }, [
    questionType,
    stem,
    options,
    correctSingle,
    correctMultiple,
    correctBoolean,
    referenceAnswer,
    explanation,
    ordering,
    pairs,
  ]);

  if (spaceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const busy = create.isPending;
  const itemNoun = questionType === 'ORDERING' ? '条目' : '选项';
  /**
   * ORDERING items are the position space of `correctOrder`, so the contract's
   * `maxItems: 100` on that array is also the ceiling on the item list.
   * `CreateQuestionRequest.options` declares no `maxItems` — the only limit the
   * choice types have is `QuestionService.MAX_OPTIONS` (Java:49,214), which is
   * not in the contract and so is not invented here.
   */
  const itemLimit =
    questionType === 'ORDERING' ? QUESTION_FIELD_LIMITS.correctOrder : undefined;

  function addOption() {
    const nextKey = String.fromCharCode(65 + options.length);
    const nextIndex = options.length;
    setOptions([...options, { optionKey: nextKey, content: '' }]);
    setOrdering((prev) => sequenceAfterAdd(prev, nextIndex));
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
    // The list shifted, so every position that pointed past it moved down.
    setOrdering((prev) => sequenceAfterRemove(prev, index));
  }

  function movePlaced(position: number, delta: -1 | 1) {
    setOrdering((prev) => moveInSequence(prev, position, delta));
  }

  function unplace(position: number) {
    setOrdering((prev) => unplaceAtPosition(prev, position));
  }

  function place(itemIndex: number) {
    setOrdering((prev) => placeAtEnd(prev, itemIndex));
  }

  function addPair() {
    setPairs([...pairs, { ...EMPTY_PAIR }]);
  }

  function updatePair(index: number, side: keyof MatchPair, value: string) {
    const next = [...pairs];
    next[index] = { ...next[index], [side]: value };
    setPairs(next);
  }

  function removePair(index: number) {
    setPairs(pairs.filter((_, i) => i !== index));
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

  /** What an ordering row is called in the sequence widget, once trimmed. */
  function itemLabel(index: number): string {
    const item = options[index];
    if (!item) {
      return '（已删除的条目）';
    }
    const content = item.content.trim();
    const key = item.optionKey.trim();
    if (content !== '') {
      return content.length > 40 ? `${content.slice(0, 40)}…` : content;
    }
    return key !== '' ? `标识 ${key}` : '（空条目）';
  }

  const unplaced = unplacedItemIndexes(ordering, options.length);
  const correctOrderPreview = toCorrectOrder(ordering);
  const matchesPreview = serializeMatches(pairs);
  const oneWay = questionType === 'ORDERING' || questionType === 'MATCHING'
    ? oneWayNotice(questionType)
    : null;

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

        {ITEM_TYPES.includes(questionType) && (
          <div className="form__field">
            <label className="form__label">{itemNoun}</label>
            {options.map((opt, idx) => (
              <div key={idx} className="form__row">
                <input
                  className="input form__row-key"
                  aria-label={`${itemNoun} ${idx + 1} 标识`}
                  data-testid={`option-key-${idx}`}
                  value={opt.optionKey}
                  onChange={(e) => updateOptionKey(idx, e.target.value)}
                  maxLength={QUESTION_FIELD_LIMITS.optionKey}
                  disabled={busy}
                />
                <input
                  className="input form__row-main"
                  aria-label={`${itemNoun} ${idx + 1} 内容`}
                  data-testid={`option-content-${idx}`}
                  value={opt.content}
                  onChange={(e) => updateOptionContent(idx, e.target.value)}
                  placeholder={`${itemNoun} ${opt.optionKey}`}
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
              disabled={busy || (itemLimit !== undefined && options.length >= itemLimit)}
            >
              添加{itemNoun}
            </Button>
            {itemLimit !== undefined && options.length >= itemLimit && (
              <p className="field__hint" role="note" data-testid="item-limit-reached">
                已达 CreateQuestionRequest.correctOrder 的上限 {itemLimit} 项，无法继续添加。
              </p>
            )}
          </div>
        )}

        {questionType === 'ORDERING' && (
          <div className="form__field" data-testid="ordering-editor">
            <span className="form__label" id="q-ordering-label">标准顺序</span>
            <p className="field__hint">
              顺序里的编号从 <strong>1</strong> 开始（服务端
              AnswerPayloadView.orderingAnswer 的 items.minimum 是 1），
              第 1 个提交的条目就是 1。用「上移 / 下移」调整，或先「移出顺序」再重新加入。
            </p>
            <ol className="question-renderer__ordering" data-testid="ordering-sequence">
              {ordering.map((itemIndex, position) => (
                <li key={itemIndex} className="question-renderer__ordering-item" data-testid="ordering-sequence-item">
                  <span className="question-renderer__ordering-rank">{position + 1}</span>
                  <span className="question-renderer__ordering-text">{itemLabel(itemIndex)}</span>
                  <span className="question-renderer__ordering-actions">
                    <Button
                      type="button"
                      variant="secondary"
                      aria-label={`上移标准顺序第 ${position + 1} 位`}
                      disabled={busy || position === 0}
                      onClick={() => movePlaced(position, -1)}
                    >
                      上移
                    </Button>
                    <Button
                      type="button"
                      variant="secondary"
                      aria-label={`下移标准顺序第 ${position + 1} 位`}
                      disabled={busy || position === ordering.length - 1}
                      onClick={() => movePlaced(position, 1)}
                    >
                      下移
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      aria-label={`将标准顺序第 ${position + 1} 位移出`}
                      disabled={busy}
                      onClick={() => unplace(position)}
                    >
                      移出顺序
                    </Button>
                  </span>
                </li>
              ))}
            </ol>
            {unplaced.length > 0 && (
              <div
                className="form__checkboxes"
                role="group"
                aria-labelledby="q-ordering-label"
                data-testid="ordering-unplaced"
              >
                <span className="field__hint">尚未排入标准顺序：</span>
                {unplaced.map((itemIndex) => (
                  <Button
                    key={itemIndex}
                    type="button"
                    variant="secondary"
                    data-testid={`ordering-place-${itemIndex}`}
                    disabled={busy}
                    onClick={() => place(itemIndex)}
                  >
                    把「{itemLabel(itemIndex)}」加入顺序
                  </Button>
                ))}
              </div>
            )}
            <p className="field__hint" data-testid="correct-order-preview">
              correctOrder = [{correctOrderPreview.join(', ')}]
            </p>
            {oneWay && (
              <p className="form__note" role="note" data-testid="ordering-oneway-notice">
                {oneWay}
              </p>
            )}
          </div>
        )}

        {questionType === 'MATCHING' && (
          <div className="form__field" data-testid="matching-editor">
            <span className="form__label" id="q-matching-label">配对条目</span>
            <p className="field__hint">
              每组只有 <code>left</code> 与 <code>right</code> 两个字段：服务端把这段
              JSON 原样存成 matchesJson，并按整组比对判分，多一个键都会让正确答案判不出对。
              这里填的是配对文本本身，不是选项标识。
            </p>
            {pairs.map((pair, idx) => (
              <div key={idx} className="form__row">
                <input
                  className="input form__row-main"
                  aria-label={`配对 ${idx + 1} 左侧`}
                  data-testid={`match-left-${idx}`}
                  value={pair.left}
                  onChange={(e) => updatePair(idx, 'left', e.target.value)}
                  placeholder={`左侧 ${idx + 1}`}
                  disabled={busy}
                />
                <span className="muted" aria-hidden="true">↔</span>
                <input
                  className="input form__row-main"
                  aria-label={`配对 ${idx + 1} 右侧`}
                  data-testid={`match-right-${idx}`}
                  value={pair.right}
                  onChange={(e) => updatePair(idx, 'right', e.target.value)}
                  placeholder={`右侧 ${idx + 1}`}
                  disabled={busy}
                />
                <Button
                  type="button"
                  variant="danger"
                  aria-label={`删除配对 ${idx + 1}`}
                  onClick={() => removePair(idx)}
                  disabled={busy}
                >
                  删除
                </Button>
              </div>
            ))}
            <Button type="button" variant="secondary" onClick={addPair} disabled={busy}>
              添加配对
            </Button>
            <ul className="point-list" data-testid="matches-preview">
              {pairs.map((pair, idx) => (
                <li key={idx} data-testid="matches-preview-item">
                  {(pair.left.trim() === '' ? '（空）' : pair.left.trim())}
                  {' → '}
                  {(pair.right.trim() === '' ? '（空）' : pair.right.trim())}
                </li>
              ))}
            </ul>
            <p className="field__hint" data-testid="matches-length">
              提交文本 {matchesPreview.length} / {QUESTION_FIELD_LIMITS.matches} 个字符
            </p>
            {oneWay && (
              <p className="form__note" role="note" data-testid="matching-oneway-notice">
                {oneWay}
              </p>
            )}
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
          {/* Deliberately never disabled for a validation error: the click
              prints the list above and never reaches the server, so 创建 is
              always the way to find out why it is refused. */}
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

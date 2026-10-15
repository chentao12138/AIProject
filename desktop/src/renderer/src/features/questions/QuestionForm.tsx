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
 * Both new types now read back too: `QuestionAnswerView` carries `correctOrder`
 * and `matches` (C09b), so the stored answer of an ORDERING / MATCHING question
 * is visible on the detail page and nothing has to be promised one-way.
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
import { SelectMenu } from '../../components/SelectMenu';
import {
  AUTHORABLE_QUESTION_TYPES,
  QUESTION_FIELD_LIMITS,
  QUESTION_TYPES,
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
  emptyMatchingDraft,
  initialOrderingSequence,
  matchingLeftKey,
  matchingOptions,
  matchingPreviewRows,
  matchingRightKey,
  moveInSequence,
  pairingAfterLeftAdd,
  pairingAfterLeftRemove,
  pairingAfterRightRemove,
  placeAtEnd,
  serializeMatches,
  sequenceAfterAdd,
  sequenceAfterRemove,
  toCorrectOrder,
  unplacedItemIndexes,
  unplaceAtPosition,
  validateMatchingAuthoring,
  validateOrderingAuthoring,
  type MatchingDraft,
} from './question-form-state';

/** The types this form shows but refuses, with the contract gap for each. */
const BLOCKED_TYPES = QUESTION_TYPES.filter((t) => !isAuthorableQuestionType(t));

/** Types whose items are entered as options (`QuestionOptionInput` rows). */
const ITEM_TYPES: readonly QuestionType[] = [
  'SINGLE_CHOICE',
  'MULTIPLE_CHOICE',
  'ORDERING',
];

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
  /** Two columns plus the standard pairing — see `MatchingDraft` in question-form-state.ts. */
  const [matching, setMatching] = useState<MatchingDraft>(() => emptyMatchingDraft());
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
        // A matching question IS its two columns plus the key between them:
        // `QuestionRenderer` splits `options` at ceil(n/2) into the left and
        // right columns and answers with their OPTION KEYS
        // (answer-grammar.ts:73-78, QuestionRenderer.tsx:193-221), and
        // `gradeMatching` compares that set of maps with the stored
        // `matchesJson`. So the columns go up as options AND the pairs go up as
        // `matches`, with the same generated keys in both — a `matches` alone
        // would be a key nobody can answer against, and free text in it could
        // never equal an option key.
        body.options = matchingOptions(matching);
        body.matches = serializeMatches(matching);
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
      return [...common, ...validateMatchingAuthoring(matching)];
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
    matching,
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

  function updateLeft(index: number, content: string) {
    setMatching((prev) => {
      const left = [...prev.left];
      left[index] = content;
      return { ...prev, left };
    });
  }

  function addLeft() {
    setMatching((prev) => ({
      ...prev,
      left: [...prev.left, ''],
      pairs: pairingAfterLeftAdd(prev.pairs),
    }));
  }

  function removeLeft(index: number) {
    setMatching((prev) => ({
      ...prev,
      left: prev.left.filter((_, i) => i !== index),
      pairs: pairingAfterLeftRemove(prev.pairs, index),
    }));
  }

  function updateRight(index: number, content: string) {
    setMatching((prev) => {
      const right = [...prev.right];
      right[index] = content;
      return { ...prev, right };
    });
  }

  function addRight() {
    setMatching((prev) => ({ ...prev, right: [...prev.right, ''] }));
  }

  /**
   * Deleting a right item also un-pairs every left that pointed at it —
   * renumbering silently instead would retarget those pairs onto the neighbour.
   */
  function removeRight(index: number) {
    setMatching((prev) => ({
      ...prev,
      right: prev.right.filter((_, i) => i !== index),
      pairs: pairingAfterRightRemove(prev.pairs, index),
    }));
  }

  function setPair(leftIndex: number, rightIndex: number) {
    setMatching((prev) => {
      const pairs = [...prev.pairs];
      while (pairs.length <= leftIndex) {
        pairs.push(-1);
      }
      pairs[leftIndex] = rightIndex;
      return { ...prev, pairs };
    });
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
  const matchesPreviewText = serializeMatches(matching);
  const matchingPreview = matchingPreviewRows(matching);

  return (
    <div className="page">
      <PageHeader title="新建题目" />
      {/* noValidate: `required` stays for the a11y semantics, but the rules
          below (per type, per contract field) are the only ones that decide
          whether a submit goes out — two parallel validation systems would
          disagree about FILL_BLANK vs SHORT_ANSWER. */}
      <form className="form" onSubmit={handleSubmit} noValidate>
        <SelectMenu
          label="题型"
          value={questionType}
          disabled={busy}
          onValueChange={(value) => {
            // A type this form shows but cannot create must never become the
            // value. It used to be refused by `<option disabled>`; SelectMenu's
            // option model is `{value, label}` with no per-option `disabled`,
            // so the guard lives on the commit instead, and the
            // `authoring-gap-notes` block below still names the gap.
            if (isAuthorableQuestionType(value)) {
              setQuestionType(value as AuthorableQuestionType);
            }
          }}
          options={[
            ...AUTHORABLE_QUESTION_TYPES.map((t) => ({
              value: t,
              label: questionTypeLabel(t),
            })),
            ...BLOCKED_TYPES.map((t) => ({
              value: t,
              label: `${questionTypeLabel(t)}（暂不可创建）`,
            })),
          ]}
        />

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
          </div>
        )}

        {questionType === 'MATCHING' && (
          <div className="form__field" data-testid="matching-editor">
            <span className="form__label" id="q-matching-label">配对条目</span>
            <p className="field__hint">
              左右两列就是这道题的选项（依次存成 L1…、R1…），
              标准配对提交为 CreateQuestionRequest.matches 一个字符串，形如{' '}
              <code>{'[{"left":"L1","right":"R2"}]'}</code>
              ：服务端原样存成 matchesJson，判分时按整组 left / right
              比对，多一个键或值对不上就永远判不出对。
              答题控件读的是选项标识，所以这里排的是「哪个左项配哪个右项」，
              而不是把文本再抄一遍。
            </p>

            <div className="form__row">
              <div className="form__row-main">
                <span className="form__label">左列</span>
                {matching.left.map((content, idx) => (
                  <div key={idx} className="form__row">
                    <span className="muted" aria-hidden="true">
                      {matchingLeftKey(idx)}
                    </span>
                    <input
                      className="input form__row-main"
                      aria-label={`左侧条目 ${idx + 1}`}
                      data-testid={`match-left-${idx}`}
                      value={content}
                      onChange={(e) => updateLeft(idx, e.target.value)}
                      placeholder={`左侧 ${idx + 1}`}
                      maxLength={QUESTION_FIELD_LIMITS.optionContent}
                      disabled={busy}
                    />
                    <Button
                      type="button"
                      variant="danger"
                      aria-label={`删除左侧条目 ${idx + 1}`}
                      onClick={() => removeLeft(idx)}
                      disabled={busy}
                    >
                      删除
                    </Button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="secondary"
                  onClick={addLeft}
                  disabled={busy}
                  data-testid="match-add-left"
                >
                  添加左列
                </Button>
              </div>

              <div className="form__row-main">
                <span className="form__label">右列</span>
                {matching.right.map((content, idx) => (
                  <div key={idx} className="form__row">
                    <span className="muted" aria-hidden="true">
                      {matchingRightKey(idx)}
                    </span>
                    <input
                      className="input form__row-main"
                      aria-label={`右侧条目 ${idx + 1}`}
                      data-testid={`match-right-${idx}`}
                      value={content}
                      onChange={(e) => updateRight(idx, e.target.value)}
                      placeholder={`右侧 ${idx + 1}`}
                      maxLength={QUESTION_FIELD_LIMITS.optionContent}
                      disabled={busy}
                    />
                    <Button
                      type="button"
                      variant="danger"
                      aria-label={`删除右侧条目 ${idx + 1}`}
                      onClick={() => removeRight(idx)}
                      disabled={busy}
                    >
                      删除
                    </Button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="secondary"
                  onClick={addRight}
                  disabled={busy}
                  data-testid="match-add-right"
                >
                  添加右列
                </Button>
              </div>
            </div>

            <div className="form__field" data-testid="matching-pairing">
              <span className="form__label">标准配对</span>
              {matching.left.map((content, leftIndex) => (
                <div key={leftIndex} className="form__row">
                  <span className="input form__row-main" aria-hidden="true">
                    {content.trim() === '' ? `左侧 ${leftIndex + 1}` : content.trim()}
                  </span>
                  <SelectMenu
                    label={`左侧 ${matchingLeftKey(leftIndex)} 的标准配对`}
                    testId={`match-pair-${leftIndex}`}
                    value={
                      matching.pairs[leftIndex] >= 0 &&
                      matching.pairs[leftIndex] < matching.right.length
                        ? String(matching.pairs[leftIndex])
                        : ''
                    }
                    onValueChange={(value) => setPair(leftIndex, Number(value))}
                    disabled={busy}
                    options={[
                      { value: '', label: '—' },
                      ...matching.right.map((right, rightIndex) => ({
                        value: String(rightIndex),
                        label: `${matchingRightKey(rightIndex)}: ${
                          right.trim() === '' ? `右侧 ${rightIndex + 1}` : right.trim()
                        }`,
                      })),
                    ]}
                  />
                </div>
              ))}
            </div>

            <ul className="point-list" data-testid="matches-preview">
              {matchingPreview.map((row) => (
                <li key={row.left} data-testid="matches-preview-item">
                  {row.left}
                  {' → '}
                  {row.right}
                </li>
              ))}
            </ul>
            <p className="field__hint" data-testid="matches-wire">
              matches = {matchesPreviewText}（{matchesPreviewText.length} /{' '}
              {QUESTION_FIELD_LIMITS.matches} 字符）
            </p>
          </div>
        )}

        {questionType === 'SINGLE_CHOICE' && (
          <SelectMenu
            label="正确答案"
            value={correctSingle}
            onValueChange={setCorrectSingle}
            disabled={busy}
            options={[
              { value: '', label: '—' },
              ...options.map((o) => ({
                value: o.optionKey,
                label: `${o.optionKey}: ${o.content || '（空）'}`,
              })),
            ]}
          />
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

        <SelectMenu
          label="难度"
          value={difficulty}
          onValueChange={setDifficulty}
          disabled={busy}
          options={[
            { value: '', label: '—' },
            { value: 'EASY', label: '简单' },
            { value: 'MEDIUM', label: '中等' },
            { value: 'HARD', label: '困难' },
          ]}
        />

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

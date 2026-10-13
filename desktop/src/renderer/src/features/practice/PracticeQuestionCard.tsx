/**
 * One practice question: answer controls + the server's verdict.
 *
 * WHY THIS WRAPS `QuestionRenderer` INSTEAD OF REPLACING IT
 * `QuestionRenderer` renders an answer control only for SHORT_ANSWER,
 * TRUE_FALSE and option-bearing types. A FILL_BLANK question therefore
 * showed its stem and nothing else, beside a submit button that stayed
 * disabled forever (its gate reads `selectedOptionKeys`). FILL_BLANK is
 * answered with `textAnswer` exactly like SHORT_ANSWER, so this card draws
 * that one control itself. The clean fix is a shared text branch inside
 * QuestionRenderer (the exam answer flow needs it too) — that file is owned
 * outside the practice feature, so it is reported rather than edited here.
 *
 * ORDERING / MATCHING are answered with `orderingAnswer` / `matchingAnswer`,
 * fields `AnswerPayloadView` does not declare (audit C09). Any other payload
 * for them is a guaranteed 400, so those two types state the contract gap
 * instead of offering a control that cannot work.
 *
 * The submit button belongs to this card (no `onSubmit` passed to
 * QuestionRenderer) because enabled/label state is PER QUESTION: a shared
 * mutation flag is what made an exam button stick at "已保存" (audit G03).
 */

import { type KeyboardEvent } from 'react';
import { Button } from '../../components/Button';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';
import { formatDateTime, questionTypeLabel } from '../../lib/format';
import {
  buildAnswerPayload,
  describeServerVerdict,
  describeSubmittedAnswer,
  normalizeCode,
  PRACTICE_UNANSWERABLE_TYPES,
  type PracticeActionError,
  type PracticeAnswerDraft,
  type PracticeQuestionSlot,
  type PracticeSubmittedAnswer,
} from './practice-session-state';

export interface PracticeQuestionCardProps {
  slot: PracticeQuestionSlot;
  /** 1-based position in the paper, used for the label only. */
  position: number;
  draft?: PracticeAnswerDraft;
  /** Present only when the server answered this question. */
  submitted?: PracticeSubmittedAnswer;
  /** Mutation state for THIS question, never for the whole session. */
  pending: boolean;
  error: PracticeActionError | null;
  /** False once the session leaves IN_PROGRESS. */
  canAnswer: boolean;
  onDraftChange: (draft: PracticeAnswerDraft) => void;
  onSubmit: () => void;
}

export function PracticeQuestionCard({
  slot,
  position,
  draft,
  submitted,
  pending,
  error,
  canAnswer,
  onDraftChange,
  onSubmit,
}: PracticeQuestionCardProps) {
  const type = normalizeCode(slot.questionType);
  const unanswerable = PRACTICE_UNANSWERABLE_TYPES[type];
  const payload = buildAnswerPayload(slot.questionType, draft);
  const editable = canAnswer && !submitted && !pending;
  const question: QuestionPayload = {
    questionType: slot.questionType,
    stem: slot.stem ?? '',
    options: slot.options,
  };

  const label = submitted
    ? '已提交'
    : pending
      ? '提交中…'
      : error
        ? '重新提交'
        : '提交';
  const submitDisabled = !canAnswer || Boolean(submitted) || pending || !payload.ok;

  // QuestionRenderer's own textarea submits on Enter; the FILL_BLANK control
  // this card owns keeps the same keyboard behaviour.
  function handleTextKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !submitDisabled) {
      event.preventDefault();
      onSubmit();
    }
  }

  return (
    <div className="practice-question-card">
      <p className="practice-question-card__position">
        第 {position} 题 · {questionTypeLabel(slot.questionType)}
      </p>

      <QuestionRenderer
        question={question}
        mode="answer"
        selectedOptionKeys={draft?.selectedOptionKeys}
        booleanAnswer={draft?.booleanAnswer}
        textAnswer={draft?.textAnswer}
        onSelectedOptionKeysChange={
          editable ? (keys) => onDraftChange({ selectedOptionKeys: keys }) : undefined
        }
        onBooleanAnswerChange={
          editable ? (value) => onDraftChange({ booleanAnswer: value }) : undefined
        }
        // FILL_BLANK has no renderer control, so this card renders its own
        // textarea and must not wire the same callback twice.
        onTextAnswerChange={
          type === 'SHORT_ANSWER' && editable
            ? (value) => onDraftChange({ textAnswer: value })
            : undefined
        }
        disabled={!editable}
      />

      {type === 'FILL_BLANK' && (
        <div className="practice-question-card__fill">
          <label
            className="form__label"
            htmlFor={`practice-fill-${slot.practiceSessionQuestionId}`}
          >
            填空作答
          </label>
          <textarea
            id={`practice-fill-${slot.practiceSessionQuestionId}`}
            className="textarea"
            rows={2}
            aria-label="填空作答"
            placeholder="输入填空内容，多个空用英文逗号分隔…"
            value={draft?.textAnswer ?? ''}
            disabled={!editable}
            onChange={(event) => onDraftChange({ textAnswer: event.target.value })}
            onKeyDown={handleTextKeyDown}
          />
        </div>
      )}

      {unanswerable && (
        <p className="page__note" data-testid="unsupported-question-type">
          {unanswerable}
        </p>
      )}

      {!submitted && !unanswerable && (
        <div className="question-renderer__actions">
          <Button
            type="button"
            variant="primary"
            onClick={onSubmit}
            disabled={submitDisabled}
          >
            {label}
          </Button>
          {!payload.ok && !pending && !error && canAnswer && (
            <p className="form__note" data-testid="submit-hint">
              {payload.message}
            </p>
          )}
        </div>
      )}

      {error && (
        <p className="form__error" role="alert" data-testid="answer-error">
          {error.message} {error.detail}
        </p>
      )}

      {submitted && <PracticeAnswerFeedback slot={slot} submitted={submitted} />}
    </div>
  );
}

/**
 * Immediate feedback (ADR-041) built ONLY from `PracticeAnswerView`. The score
 * is the number the server sent, never recomputed here (ADR-031).
 */
export function PracticeAnswerFeedback({
  slot,
  submitted,
}: {
  slot: PracticeQuestionSlot;
  submitted: PracticeSubmittedAnswer;
}) {
  const verdict = describeServerVerdict(submitted.view);
  return (
    <div
      className="practice-answer-feedback"
      data-tone={verdict.tone}
      data-testid="answer-feedback"
    >
      <p className="practice-answer-feedback__verdict">
        <strong>{verdict.label}</strong>
        {typeof submitted.view.score === 'number' && (
          <span> · 后端得分 {submitted.view.score}</span>
        )}
        {submitted.view.answeredAt && (
          <span className="muted"> · {formatDateTime(submitted.view.answeredAt)}</span>
        )}
      </p>
      <p className="muted">
        我的作答：{describeSubmittedAnswer(slot.questionType, submitted.answer)}
      </p>
      {submitted.view.correctAnswer && (
        <p>正确答案：{submitted.view.correctAnswer}</p>
      )}
      {submitted.view.explanation ? (
        <p data-testid="answer-explanation">解析：{submitted.view.explanation}</p>
      ) : (
        <p className="muted" data-testid="answer-explanation-missing">
          后端未返回该题解析。
        </p>
      )}
    </div>
  );
}

/**
 * One practice question: answer controls + the server's verdict.
 *
 * WHY THIS WRAPS `QuestionRenderer` INSTEAD OF REPLACING IT
 * `QuestionRenderer` owns every answer control — options, the true/false pair,
 * the shared text box (SHORT_ANSWER and FILL_BLANK), the ORDERING sequence and
 * the MATCHING pair editor. A card that kept its own copy of one of them is how
 * FILL_BLANK once rendered a control the renderer did not know about, so this
 * component wires the draft to the renderer's callbacks and adds nothing.
 *
 * ORDERING / MATCHING became answerable when the contract grew
 * `AnswerPayloadView.orderingAnswer` / `.matchingAnswer`; the card forwards
 * those fields verbatim, so what the renderer edited IS what gets sent.
 *
 * The submit button belongs to this card (no `onSubmit` passed to
 * QuestionRenderer) because enabled/label state is PER QUESTION: a shared
 * mutation flag is what made an exam button stick at "已保存" (audit G03).
 */

import { Button } from '../../components/Button';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';
import { formatDateTime, questionTypeLabel } from '../../lib/format';
import {
  buildAnswerPayload,
  describeServerVerdict,
  describeSubmittedAnswer,
  practicePayloadContext,
  unanswerableTypeNotice,
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
  const unanswerable = unanswerableTypeNotice(slot.questionType);
  // ORDERING has to place every option and MATCHING has to pair every left
  // item, so the gate is checked against the paper, not just the drafted array.
  const payload = buildAnswerPayload(
    slot.questionType,
    draft,
    practicePayloadContext(slot)
  );
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
        orderingAnswer={draft?.orderingAnswer}
        matchingAnswer={draft?.matchingAnswer}
        onSelectedOptionKeysChange={
          editable ? (keys) => onDraftChange({ selectedOptionKeys: keys }) : undefined
        }
        onBooleanAnswerChange={
          editable ? (value) => onDraftChange({ booleanAnswer: value }) : undefined
        }
        // SHORT_ANSWER and FILL_BLANK are both the shared text control: the
        // renderer owns the placeholder, the length limit and the Enter-to-send
        // behaviour, so this card must not keep a second copy of it.
        onTextAnswerChange={
          editable ? (value) => onDraftChange({ textAnswer: value }) : undefined
        }
        // The renderer emits the wire shapes ({orderingAnswer: number[]},
        // {matchingAnswer: JSON}), so the draft IS the payload.
        onOrderingAnswerChange={
          editable ? (value) => onDraftChange({ orderingAnswer: value }) : undefined
        }
        onMatchingAnswerChange={
          editable ? (value) => onDraftChange({ matchingAnswer: value }) : undefined
        }
        onTextEnterSubmit={onSubmit}
        disabled={!editable}
      />

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
      data-source={submitted.source ?? 'submitted'}
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
      {submitted.answer ? (
        <p className="muted">
          我的作答：{describeSubmittedAnswer(slot.questionType, submitted.answer)}
        </p>
      ) : (
        // Read back from GET .../answers: that view has no answer field, so the
        // verdict is all the server tells us. Showing anything else here would
        // be a fabricated copy of an answer this client never sent.
        <p className="muted" data-testid="answer-content-unavailable">
          本题已在服务端作答（由后端的答案记录读取）。接口的 PracticeAnswerView
          只返回判定，不返回作答内容，因此此处不显示当时提交的答案。
        </p>
      )}
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

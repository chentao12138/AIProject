/**
 * Practice per-question review (audit W03 + the reachable part of G05 + A5.2).
 *
 * Data source discipline:
 *  - `GET /practice-sessions/{id}` returns `PracticeSessionDetail` = session
 *    fields + `questions` ONLY — no answers, no score.
 *  - `GET /practice-sessions/{id}/answers` returns the `PracticeAnswerView`
 *    rows the server holds, and `POST .../answers` returns one of the same.
 *    Both are mirrored into the session's cache by practice-session-state, so
 *    this list renders the server's records — including answers this device
 *    never sent — instead of leaving a blank card or scoring locally (ADR-031).
 *  - Neither view echoes the user's own answer text. An entry without a local
 *    echo is reported as 已作答 with the verdict only; nothing about what was
 *    answered is invented here.
 *  - The session SCORE comes from `GET /practice-sessions/{id}/result` (A5.3)
 *    and is the page's business; this list stays the per-question read, so the
 *    two never mix into a locally computed total.
 *  - `explanation` is null on every row of `GET …/answers` (backend-confirmed
 *    defect, not a design choice), so a missing explanation says so plainly
 *    instead of rendering an empty 解析 block.
 */

import { Link } from 'react-router-dom';
import { isPositiveId } from '../../lib/ids';
import { formatDateTime } from '../../lib/format';
import { Button } from '../../components/Button';
import {
  describeServerVerdict,
  describeSubmittedAnswer,
  type PracticeQuestionSlot,
  type PracticeSubmittedAnswer,
} from './practice-session-state';

export interface PracticeReviewListProps {
  spaceId: number;
  slots: PracticeQuestionSlot[];
  submitted: Record<number, PracticeSubmittedAnswer>;
  /** The answer read is still in flight: say so rather than claim nothing. */
  loading?: boolean;
  /** The answer read failed: name it, and offer the one action that helps. */
  error?: string | null;
  onRetry?: () => void;
}

export function PracticeReviewList({
  spaceId,
  slots,
  submitted,
  loading = false,
  error = null,
  onRetry,
}: PracticeReviewListProps) {
  const recorded = slots.filter((slot) => Boolean(submitted[slot.practiceSessionQuestionId]));

  return (
    <section className="workbench-section" aria-label="逐题回顾">
      <h2 className="section-title">逐题回顾</h2>

      {loading && (
        <p className="page__note" role="status" data-testid="review-loading">
          正在读取后端的逐题作答记录…
        </p>
      )}

      {error && (
        <p className="page__note" data-testid="review-error">
          无法读取后端的逐题作答记录：{error}
          {onRetry && (
            <>
              {' '}
              <Button type="button" variant="ghost" onClick={onRetry}>
                重试读取
              </Button>
            </>
          )}
        </p>
      )}

      {recorded.length === 0 && !loading && !error && (
        <p className="page__note" data-testid="review-unavailable">
          后端未返回本会话的逐题作答记录：{'GET /practice-sessions/{id}/answers'}
          {' '}给出空列表，也就是本次练习没有已提交的题目。
        </p>
      )}
      {recorded.length > 0 && (
        <>
          <p className="page__note">
            已记录 {recorded.length} / {slots.length} 题的后端逐题结果。
          </p>
          <ol className="practice-review-list">
            {slots.map((slot, index) => {
              const entry = submitted[slot.practiceSessionQuestionId];
              return (
                <li key={slot.practiceSessionQuestionId} className="result-item">
                  <p>
                    <strong>第 {index + 1} 题：</strong>
                    {slot.stem ?? '（题干未返回）'}
                    {isPositiveId(slot.questionId) && (
                      <>
                        {' · '}
                        <Link
                          className="nav-link"
                          to={`/spaces/${spaceId}/questions/${slot.questionId}`}
                        >
                          题目 #{slot.questionId}
                        </Link>
                      </>
                    )}
                  </p>
                  {entry ? (
                    <PracticeReviewItem slot={slot} entry={entry} />
                  ) : (
                    <p className="muted" data-testid="review-item-missing">
                      本题没有可显示的后端逐题结果：服务端的作答记录里没有它，
                      也就是本会话并未提交过该题。
                    </p>
                  )}
                </li>
              );
            })}
          </ol>
        </>
      )}
    </section>
  );
}

function PracticeReviewItem({
  slot,
  entry,
}: {
  slot: PracticeQuestionSlot;
  entry: PracticeSubmittedAnswer;
}) {
  const verdict = describeServerVerdict(entry.view);
  return (
    <div data-testid="review-item" data-tone={verdict.tone}>
      <p>
        {verdict.label}
        {typeof entry.view.score === 'number' && <> · 后端得分 {entry.view.score}</>}
        {entry.view.answeredAt && (
          <> · {formatDateTime(entry.view.answeredAt)}</>
        )}
      </p>
      {entry.answer ? (
        <p className="muted">
          我的作答：{describeSubmittedAnswer(slot.questionType, entry.answer)}
        </p>
      ) : (
        // Straight off GET .../answers: the view has no answer field, so only
        // the verdict exists. Inventing the submitted text here would be the
        // same fabrication the local cache used to be.
        <p className="muted" data-testid="review-answer-unreadable">
          作答内容不回传（PracticeAnswerView 无答案字段），此处只显示服务端判定。
        </p>
      )}
      {entry.view.correctAnswer && (
        <p>正确答案：{entry.view.correctAnswer}</p>
      )}
      {entry.view.explanation ? (
        <p data-testid="review-explanation">
          <span className="muted">解析：</span>
          {entry.view.explanation}
        </p>
      ) : (
        // `GET …/answers` sends `explanation: null` for every row today (a
        // backend defect they confirmed), so this line — never an empty 解析
        // block — is what a reopened session shows.
        <p className="muted" data-testid="review-explanation-missing">
          后端未返回该题解析。
        </p>
      )}
    </div>
  );
}

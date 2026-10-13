/**
 * Practice per-question review (audit W03 + the reachable part of G05).
 *
 * Data source discipline: `GET /practice-sessions/{id}` returns
 * `PracticeSessionDetail` = session fields + `questions` ONLY — no answers,
 * no score (verified in openapi.json / generated api.d.ts). The only
 * server-issued per-question truth is the `PracticeAnswerView` returned by
 * `POST /practice-sessions/{id}/answers`, and the contract declares no GET
 * for it (`/answers` is `post`; `get?: never`). So this list renders those
 * responses and says plainly which questions have no readable result,
 * instead of leaving a blank card or scoring locally (ADR-031).
 */

import { Link } from 'react-router-dom';
import { isPositiveId } from '../../lib/ids';
import { formatDateTime } from '../../lib/format';
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
}

export function PracticeReviewList({
  spaceId,
  slots,
  submitted,
}: PracticeReviewListProps) {
  const recorded = slots.filter((slot) => Boolean(submitted[slot.practiceSessionQuestionId]));

  return (
    <section className="workbench-section" aria-label="逐题回顾">
      <h2 className="section-title">逐题回顾</h2>
      {recorded.length === 0 ? (
        <p className="page__note" data-testid="review-unavailable">
          本次会话没有可读的逐题作答结果：后端仅在提交单题答案的响应里返回
          PracticeAnswerView，GET /practice-sessions/{'{id}'} 的合同（
          PracticeSessionDetail）不含答案字段，也没有逐题答案查询端点。历史会话的错题回顾需要
          GET /api/v1/spaces/{'{spaceId}'}/practice-sessions/{'{sessionId}'}/result（合同缺口 C06）。
        </p>
      ) : (
        <>
          <p className="page__note">
            已记录 {recorded.length} / {slots.length} 题的后端逐题结果（来自提交响应）。
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
                      本题没有可显示的后端逐题结果：本端未记录到它的提交响应，
                      而合同不提供逐题答案读取端点。
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
      <p className="muted">
        我的作答：{describeSubmittedAnswer(slot.questionType, entry.answer)}
      </p>
      {entry.view.correctAnswer && (
        <p>正确答案：{entry.view.correctAnswer}</p>
      )}
      {entry.view.explanation ? (
        <p>
          <span className="muted">解析：</span>
          {entry.view.explanation}
        </p>
      ) : (
        <p className="muted">后端未返回该题解析。</p>
      )}
    </div>
  );
}

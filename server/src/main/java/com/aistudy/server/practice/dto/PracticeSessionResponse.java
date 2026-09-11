package com.aistudy.server.practice.dto;

import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BUSINESS-009 — typed practice session responses.
 *
 * <p>The DETAIL view carries the session's fixed questions as SAFE
 * views only: questionType / stem / options WITHOUT any answer data
 * (correct keys, correctBoolean, referenceAnswer) — the snapshot's
 * answerData stays server-internal (api-guidelines.md §9).
 */
public final class PracticeSessionResponse {

    private PracticeSessionResponse() {
    }

    /** List/detail summary view. */
    public record PracticeSessionSummary(
            Long id,
            Long spaceId,
            String status,
            Integer questionCount,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static PracticeSessionSummary from(PracticeSession session, int questionCount) {
            return new PracticeSessionSummary(
                    session.getId(),
                    session.getSpaceId(),
                    session.getStatus(),
                    questionCount,
                    session.getStartedAt(),
                    session.getFinishedAt(),
                    session.getCreatedAt(),
                    session.getUpdatedAt());
        }
    }

    /** Detail view = summary + fixed question composition (safe view). */
    public record PracticeSessionDetail(
            Long id,
            Long spaceId,
            String status,
            Integer questionCount,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<PracticeQuestionView> questions
    ) {
        public static PracticeSessionDetail from(PracticeSession session,
                                                 List<PracticeSessionQuestion> slots) {
            return new PracticeSessionDetail(
                    session.getId(),
                    session.getSpaceId(),
                    session.getStatus(),
                    slots.size(),
                    session.getStartedAt(),
                    session.getFinishedAt(),
                    session.getCreatedAt(),
                    session.getUpdatedAt(),
                    slots.stream().map(PracticeQuestionView::from).toList());
        }
    }

    /**
     * One question of the session as the client sees it. Built from
     * the SNAPSHOT (not the live question); deliberately contains no
     * answerData / explanation.
     */
    public record PracticeQuestionView(
            Long practiceSessionQuestionId,
            Long questionId,
            Integer sortOrder,
            String questionType,
            String stem,
            List<OptionView> options
    ) {
        static PracticeQuestionView from(PracticeSessionQuestion slot) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            return new PracticeQuestionView(
                    slot.getId(),
                    slot.getQuestionId(),
                    slot.getSortOrder(),
                    snapshot.questionType(),
                    snapshot.stem(),
                    snapshot.options().stream()
                            .map(o -> new OptionView(
                                    String.valueOf(o.get("optionKey")),
                                    String.valueOf(o.get("content")),
                                    o.get("sortOrder") == null ? null
                                            : ((Number) o.get("sortOrder")).intValue()))
                            .toList());
        }
    }

    /** Option without any correctness flag. */
    public record OptionView(String optionKey, String content, Integer sortOrder) {
    }
}

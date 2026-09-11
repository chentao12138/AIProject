package com.aistudy.server.mastery.service;

import com.aistudy.server.mastery.entity.Mastery;
import com.aistudy.server.mastery.mapper.MasteryMapper;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * BUSINESS-014 — Mastery application service.
 *
 * <p>Recomputes a knowledge point's current state from ALL evidence
 * (practice + exam graded objective answers via CURRENT question
 * links; KNOWLEDGE_POINT-targeted review records). Called by practice
 * finish (010) and exam submit (013) inside their own transactions;
 * correctness over micro-optimization — full recompute per affected
 * point.
 *
 * <p>Formula (MasteryScoringPolicy): score = correct/graded (0..1),
 * confidence = min(1, graded/5). Questions without knowledge points
 * contribute no evidence.
 */
@Service
public class MasteryService {

    private final MasteryMapper masteryMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final LearningSpaceService learningSpaceService;

    public MasteryService(MasteryMapper masteryMapper,
                          QuestionKnowledgePointMapper questionKnowledgePointMapper,
                          LearningSpaceService learningSpaceService) {
        this.masteryMapper = masteryMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Recomputes the mastery rows of every knowledge point linked to
     * the given questions. No-op for questions without links.
     */
    @Transactional
    public void recomputeForQuestions(String ownerSubject, Long spaceId,
                                      List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<Long> kpIds = new java.util.LinkedHashSet<>();
        for (Long questionId : questionIds) {
            kpIds.addAll(questionKnowledgePointMapper
                    .selectKnowledgePointIdsByQuestionId(spaceId, questionId));
        }
        for (Long kpId : kpIds) {
            recompute(ownerSubject, spaceId, kpId);
        }
    }

    /** Recomputes ONE knowledge point's mastery row (upsert). */
    @Transactional
    public void recompute(String ownerSubject, Long spaceId, Long kpId) {
        Map<String, Object> practice = masteryMapper.selectPracticeEvidence(
                spaceId, ownerSubject, kpId);
        Map<String, Object> exam = masteryMapper.selectExamEvidence(
                spaceId, ownerSubject, kpId);
        Map<String, Object> review = masteryMapper.selectReviewEvidence(
                spaceId, ownerSubject, kpId);

        int practiceCount = ((Number) practice.get("cnt")).intValue();
        int practiceCorrect = ((Number) practice.get("correct")).intValue();
        int examCount = ((Number) exam.get("cnt")).intValue();
        int examCorrect = ((Number) exam.get("correct")).intValue();
        int reviewCount = ((Number) review.get("cnt")).intValue();

        int gradedCount = practiceCount + examCount;
        int correctCount = practiceCorrect + examCorrect;
        double score = MasteryScoringPolicy.score(correctCount, gradedCount);
        double confidence = MasteryScoringPolicy.confidence(gradedCount);

        LocalDateTime lastPractice = asLocalDateTime(practice.get("last_at"));
        LocalDateTime lastExam = asLocalDateTime(exam.get("last_at"));
        LocalDateTime lastReview = asLocalDateTime(review.get("last_at"));
        // lastEvidenceAt = max(practice, exam, review): review
        // completion participates in the evidence timestamp even
        // though review COUNT is explanatory and does NOT enter the
        // score/confidence formula (docs/data-model.md §15 + V019).
        LocalDateTime lastEvidenceAt = latestEvidenceAt(lastPractice, lastExam, lastReview);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Mastery existing = masteryMapper.selectByUserSpaceKp(ownerSubject, spaceId, kpId);
        if (existing == null) {
            Mastery mastery = new Mastery();
            mastery.setUserSubject(ownerSubject);
            mastery.setSpaceId(spaceId);
            mastery.setKnowledgePointId(kpId);
            mastery.setMasteryScore(score);
            mastery.setConfidence(confidence);
            mastery.setPracticeEvidenceCount(practiceCount);
            mastery.setExamEvidenceCount(examCount);
            mastery.setReviewEvidenceCount(reviewCount);
            mastery.setLastEvidenceAt(lastEvidenceAt);
            mastery.setCreatedAt(now);
            mastery.setUpdatedAt(now);
            masteryMapper.insert(mastery);
        } else {
            masteryMapper.updateByIdAndSpace(
                    existing.getId(), spaceId, ownerSubject,
                    score, confidence, practiceCount, examCount, reviewCount,
                    lastEvidenceAt, now);
        }
    }

    /** All mastery rows of the caller's space (weakest first). */
    public List<Mastery> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return masteryMapper.selectBySpaceOwnerUser(spaceId, ownerSubject, ownerSubject);
    }

    /**
     * ONE knowledge point's mastery row (null → 404 = no evidence
     * yet). Owner-scoped: JOINs learning_space.owner_subject so a
     * foreign subject cannot probe rows of a space they do not own.
     */
    public Mastery getMine(String ownerSubject, Long spaceId, Long kpId) {
        return masteryMapper.selectByUserSpaceKpOwnerScoped(
                ownerSubject, spaceId, kpId, ownerSubject);
    }

    /**
     * lastEvidenceAt = max(practice, exam, review) — review completion
     * participates in the evidence timestamp even though the review
     * COUNT is explanatory and does not enter the score/confidence
     * formula (docs/data-model.md §15 + V019). {@code null} inputs
     * (an evidence source with no rows yet) never win and never
     * blank out the timestamp contributed by the others.
     */
    static LocalDateTime latestEvidenceAt(LocalDateTime practice, LocalDateTime exam,
                                          LocalDateTime review) {
        LocalDateTime latest = null;
        for (LocalDateTime candidate : new LocalDateTime[]{practice, exam, review}) {
            if (candidate != null
                    && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    /**
     * Normalizes an evidence timestamp coming back from MyBatis.
     *
     * <p>The evidence queries ({@code MAX(...) AS last_at} over a
     * {@code DATETIME(6)} column) are returned as {@code LocalDateTime}
     * by the JDBC/MyBatis type handling actually in force, but a
     * {@code java.sql.Timestamp} is equally possible depending on
     * driver/JDBC configuration. Both are accepted so the service
     * never depends on which one the stack happens to hand back;
     * the empty projection row ({@code null}) stays {@code null}.
     * Anything else is a genuine surprise and fails loudly rather
     * than being silently coerced to a string round-trip.
     */
    static LocalDateTime asLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalStateException(
                "Unsupported mastery evidence timestamp type: " + value.getClass().getName());
    }
}

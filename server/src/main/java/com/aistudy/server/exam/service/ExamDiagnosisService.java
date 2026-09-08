package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamDiagnosisDto.ExamDiagnosisView;
import com.aistudy.server.exam.dto.ExamDiagnosisDto.ItemView;
import com.aistudy.server.exam.entity.ExamAnswer;
import com.aistudy.server.exam.entity.ExamAttempt;
import com.aistudy.server.exam.entity.ExamDiagnosis;
import com.aistudy.server.exam.entity.ExamDiagnosisItem;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper;
import com.aistudy.server.exam.mapper.ExamDiagnosisMapper;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BUSINESS-015 — structured ExamDiagnosis generation + read.
 *
 * <p>Diagnosis is generated DETERMINISTICALLY inside the exam submit
 * transaction (after exam_result insert + SUBMITTED transition) from
 * the paper's frozen snapshot and the attempt's graded answers. No
 * AI, no client input, no invented severity/recommendation taxonomy
 * (both stay NULL in V1).
 *
 * <h3>Dimensions V1</h3>
 *
 * <ul>
 *   <li>{@code KNOWLEDGE_POINT}: per linked point, score/maxScore via
 *       CURRENT question→point links at submit time (documented V1
 *       limitation; diagnosis rows persist the historical snapshot).</li>
 *   <li>{@code QUESTION_TYPE}: per objective type, aggregated from
 *       the same graded items.</li>
 * </ul>
 *
 * <p>Multi-KP V1 rule: an item's score/maxScore contributes to EVERY
 * linked knowledge point (full attribution). SHORT_ANSWER (UNGRADED)
 * items are excluded, consistent with exam scoring.
 */
@Service
public class ExamDiagnosisService {

    public static final String DIMENSION_KNOWLEDGE_POINT = "KNOWLEDGE_POINT";
    public static final String DIMENSION_QUESTION_TYPE = "QUESTION_TYPE";

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamDiagnosisMapper examDiagnosisMapper;
    private final ExamDiagnosisItemMapper examDiagnosisItemMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final KnowledgePointMapper knowledgePointMapper;

    public ExamDiagnosisService(ExamAttemptMapper examAttemptMapper,
                                ExamDiagnosisMapper examDiagnosisMapper,
                                ExamDiagnosisItemMapper examDiagnosisItemMapper,
                                QuestionKnowledgePointMapper questionKnowledgePointMapper,
                                KnowledgePointMapper knowledgePointMapper) {
        this.examAttemptMapper = examAttemptMapper;
        this.examDiagnosisMapper = examDiagnosisMapper;
        this.examDiagnosisItemMapper = examDiagnosisItemMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.knowledgePointMapper = knowledgePointMapper;
    }

    /**
     * Generates + persists the diagnosis of a submitted attempt.
     * Called by {@link ExamAttemptService#submit} INSIDE the submit
     * transaction, after the SUBMITTED transition. One diagnosis per
     * attempt (uk_exam_diagnosis_attempt); a second generation would
     * fail the unique key and roll back the transaction.
     *
     * @param resultScore / resultMaxScore from the freshly computed
     *                     exam_result (same numbers as the result view)
     */
    @Transactional
    public void generate(String ownerSubject, Long spaceId, Long attemptId,
                         List<ExamQuestion> slots, Map<Long, ExamAnswer> answersBySlot,
                         int resultScore, int resultMaxScore, LocalDateTime now) {
        LocalDateTime created = now.truncatedTo(ChronoUnit.MICROS);

        ExamDiagnosis diagnosis = new ExamDiagnosis();
        diagnosis.setExamAttemptId(attemptId);
        diagnosis.setUserSubject(ownerSubject);
        diagnosis.setSpaceId(spaceId);
        diagnosis.setSummary("Exam score " + resultScore + "/" + resultMaxScore);
        diagnosis.setCreatedAt(created);
        examDiagnosisMapper.insert(diagnosis);

        List<ExamDiagnosisItem> items = buildItems(
                ownerSubject, spaceId, slots, answersBySlot, diagnosis.getId(), created);
        for (ExamDiagnosisItem item : items) {
            examDiagnosisItemMapper.insert(item);
        }
    }

    /**
     * Owner-scoped diagnosis read.
     *
     * @throws ResponseStatusException 404 (attempt absent / not
     *         owner / wrong space), 409 (attempt not SUBMITTED, or
     *         submitted attempt with missing diagnosis — internal
     *         state inconsistency; no lazy generation)
     */
    public ExamDiagnosisView getMine(String ownerSubject, Long spaceId, Long attemptId) {
        ExamAttempt attempt = examAttemptMapper.selectByIdSpaceOwnerUser(
                attemptId, spaceId, ownerSubject, ownerSubject);
        if (attempt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ExamAttempt not found");
        }
        if (!ExamAttemptService.STATUS_SUBMITTED.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "diagnosis is only available after submit: " + attempt.getStatus());
        }
        ExamDiagnosis diagnosis = examDiagnosisMapper.selectByAttempt(
                attemptId, spaceId, ownerSubject);
        if (diagnosis == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "diagnosis missing for submitted exam attempt");
        }
        return ExamDiagnosisView.from(diagnosis,
                examDiagnosisItemMapper.selectByDiagnosisId(diagnosis.getId()));
    }

    // ==================== generation internals ====================

    private List<ExamDiagnosisItem> buildItems(String ownerSubject, Long spaceId,
                                               List<ExamQuestion> slots,
                                               Map<Long, ExamAnswer> answersBySlot,
                                               Long diagnosisId, LocalDateTime now) {
        // Deterministic aggregates: KP rows ordered by kp id, type
        // rows ordered by label.
        TreeMap<Long, Agg> byKp = new TreeMap<>();
        TreeMap<String, Agg> byType = new TreeMap<>();

        for (ExamQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            if ("SHORT_ANSWER".equals(snapshot.questionType())) {
                continue; // ungraded — excluded, consistent with exam scoring
            }
            ExamAnswer answer = answersBySlot.get(slot.getId());
            boolean correct = answer != null && Boolean.TRUE.equals(answer.getIsCorrect());
            int itemScore = correct ? slot.getScore() : 0;
            int maxScore = slot.getScore();

            byType.computeIfAbsent(snapshot.questionType(), k -> new Agg())
                    .add(itemScore, maxScore);

            // CURRENT question→KP links at submit time (V1 limitation,
            // documented); full attribution per linked point.
            List<Long> kpIds = questionKnowledgePointMapper
                    .selectKnowledgePointIdsByQuestionId(spaceId, slot.getQuestionId());
            for (Long kpId : kpIds) {
                byKp.computeIfAbsent(kpId, k -> new Agg()).add(itemScore, maxScore);
            }
        }

        List<ExamDiagnosisItem> items = new ArrayList<>();
        for (Map.Entry<Long, Agg> e : byKp.entrySet()) {
            items.add(item(diagnosisId, DIMENSION_KNOWLEDGE_POINT, e.getKey(),
                    kpLabel(ownerSubject, spaceId, e.getKey()), e.getValue(), now));
        }
        for (Map.Entry<String, Agg> e : byType.entrySet()) {
            items.add(item(diagnosisId, DIMENSION_QUESTION_TYPE, null,
                    e.getKey(), e.getValue(), now));
        }
        return items;
    }

    private String kpLabel(String ownerSubject, Long spaceId, Long kpId) {
        KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject);
        return kp == null ? "KP-" + kpId : kp.getTitle();
    }

    private ExamDiagnosisItem item(Long diagnosisId, String dimensionType, Long dimensionId,
                                   String label, Agg agg, LocalDateTime now) {
        ExamDiagnosisItem item = new ExamDiagnosisItem();
        item.setExamDiagnosisId(diagnosisId);
        item.setDimensionType(dimensionType);
        item.setDimensionId(dimensionId);
        item.setLabel(label);
        item.setScore(agg.score);
        item.setMaxScore(agg.maxScore);
        item.setAccuracy(agg.maxScore > 0
                ? Math.min(1.0, (double) agg.score / agg.maxScore) : 0.0);
        item.setEvidenceCount(agg.count);
        item.setSeverity(null); // V1: no invented threshold taxonomy
        item.setRecommendation(null);
        item.setCreatedAt(now);
        return item;
    }

    /** Mutable accumulator for one dimension row. */
    private static final class Agg {
        int score;
        int maxScore;
        int count;

        void add(int itemScore, int itemMaxScore) {
            score += itemScore;
            maxScore += itemMaxScore;
            count++;
        }
    }
}

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
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.eval.AnswerDataCodec.SnapshotView;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ExamDiagnosisService {

    public static final String DIMENSION_KNOWLEDGE_POINT = "KNOWLEDGE_POINT";
    public static final String DIMENSION_QUESTION_TYPE = "QUESTION_TYPE";
    public static final String DIMENSION_CATEGORY = "CATEGORY";
    /** Category labels carry their id as {@code CATEGORY-<id>}; uncategorized has none. */
    private static final String CATEGORY_PREFIX = "CATEGORY-";
    public static final String DIMENSION_DIFFICULTY = "DIFFICULTY";

    private final ExamAttemptMapper examAttemptMapper;
    private final ExamDiagnosisMapper examDiagnosisMapper;
    private final ExamDiagnosisItemMapper examDiagnosisItemMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgeCategoryMapper knowledgeCategoryMapper;

    public ExamDiagnosisService(ExamAttemptMapper examAttemptMapper,
                                ExamDiagnosisMapper examDiagnosisMapper,
                                ExamDiagnosisItemMapper examDiagnosisItemMapper,
                                QuestionKnowledgePointMapper questionKnowledgePointMapper,
                                KnowledgePointMapper knowledgePointMapper,
                                QuestionMapper questionMapper,
                                KnowledgeCategoryMapper knowledgeCategoryMapper) {
        this.examAttemptMapper = examAttemptMapper;
        this.examDiagnosisMapper = examDiagnosisMapper;
        this.examDiagnosisItemMapper = examDiagnosisItemMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.questionMapper = questionMapper;
        this.knowledgeCategoryMapper = knowledgeCategoryMapper;
    }

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
        TreeMap<Long, Agg> byKp = new TreeMap<>();
        TreeMap<String, Agg> byType = new TreeMap<>();
        TreeMap<String, Agg> byCategory = new TreeMap<>();
        TreeMap<String, Agg> byDifficulty = new TreeMap<>();

        Map<Long, Question> questionCache = new HashMap<>();
        Map<Long, KnowledgePoint> kpCache = new HashMap<>();

        for (ExamQuestion slot : slots) {
            SnapshotView snapshot = AnswerDataCodec.parseSnapshot(slot.getQuestionSnapshotJson());
            if ("SHORT_ANSWER".equals(snapshot.questionType())) {
                continue;
            }
            ExamAnswer answer = answersBySlot.get(slot.getId());
            boolean correct = answer != null && Boolean.TRUE.equals(answer.getIsCorrect());
            int itemScore = correct ? slot.getScore() : 0;
            int maxScore = slot.getScore();

            byType.computeIfAbsent(snapshot.questionType(), k -> new Agg()).add(itemScore, maxScore);

            String difficulty = "UNKNOWN";
            Question q = questionCache.computeIfAbsent(slot.getQuestionId(), qid ->
                    questionMapper.selectById(qid));
            if (q != null && q.getDifficulty() != null && !q.getDifficulty().isBlank()) {
                difficulty = q.getDifficulty();
            }
            byDifficulty.computeIfAbsent(difficulty, k -> new Agg()).add(itemScore, maxScore);

            List<Long> kpIds = questionKnowledgePointMapper
                    .selectKnowledgePointIdsByQuestionId(spaceId, slot.getQuestionId());
            for (Long kpId : kpIds) {
                byKp.computeIfAbsent(kpId, k -> new Agg()).add(itemScore, maxScore);

                KnowledgePoint kp = kpCache.computeIfAbsent(kpId, id ->
                        knowledgePointMapper.selectByIdSpaceOwner(id, spaceId, ownerSubject));
                String categoryLabel = kp != null && kp.getCategoryId() != null
                        ? CATEGORY_PREFIX + kp.getCategoryId() : "UNCATEGORIZED";
                byCategory.computeIfAbsent(categoryLabel, k -> new Agg()).add(itemScore, maxScore);
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
        for (Map.Entry<String, Agg> e : byCategory.entrySet()) {
            String categoryLabel = e.getKey();
            // "UNCATEGORIZED" is a label, not an id: parsing it threw
            // NumberFormatException, so submitting an exam over a knowledge point
            // without a category answered 400.
            Long categoryId = categoryLabel.startsWith(CATEGORY_PREFIX)
                    ? Long.valueOf(categoryLabel.substring(CATEGORY_PREFIX.length()))
                    : null;
            items.add(item(diagnosisId, DIMENSION_CATEGORY, categoryId,
                    categoryLabel, e.getValue(), now));
        }
        for (Map.Entry<String, Agg> e : byDifficulty.entrySet()) {
            items.add(item(diagnosisId, DIMENSION_DIFFICULTY, null,
                    e.getKey(), e.getValue(), now));
        }
        return items;
    }

    private String kpLabel(String ownerSubject, Long spaceId, Long kpId) {
        KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject);
        return kp == null ? "KP-" + kpId : kp.getTitle();
    }

    /** {@code dimensionId} is typed Long on purpose — every caller has an id or none. */
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
        item.setSeverity(null);
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

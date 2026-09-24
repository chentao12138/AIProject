package com.aistudy.server.exam.service;

import com.aistudy.server.exam.dto.ExamBlueprintDto.BlueprintRules;
import com.aistudy.server.exam.dto.ExamBlueprintDto.CreateExamBlueprintRequest;
import com.aistudy.server.exam.dto.ExamBlueprintDto.ExamBlueprintGenerateResponse;
import com.aistudy.server.exam.dto.ExamBlueprintDto.ExamBlueprintResponse;
import com.aistudy.server.exam.entity.Exam;
import com.aistudy.server.exam.entity.ExamBlueprint;
import com.aistudy.server.exam.entity.ExamPaper;
import com.aistudy.server.exam.entity.ExamQuestion;
import com.aistudy.server.exam.mapper.ExamBlueprintMapper;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.service.QuestionSnapshotService;
import com.aistudy.server.space.service.LearningSpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ExamBlueprintService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_USED = "USED";

    private static final Set<String> ALLOWED_DIFFICULTIES = Set.of(
            "EASY", "MEDIUM", "HARD");

    private final ExamBlueprintMapper examBlueprintMapper;
    private final ExamMapper examMapper;
    private final ExamPaperMapper examPaperMapper;
    private final ExamQuestionMapper examQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final QuestionSnapshotService questionSnapshotService;
    private final KnowledgePointMapper knowledgePointMapper;
    private final KnowledgeCategoryMapper knowledgeCategoryMapper;
    private final LearningSpaceService learningSpaceService;
    private final ObjectMapper objectMapper;

    public ExamBlueprintService(ExamBlueprintMapper examBlueprintMapper,
                                ExamMapper examMapper,
                                ExamPaperMapper examPaperMapper,
                                ExamQuestionMapper examQuestionMapper,
                                QuestionMapper questionMapper,
                                QuestionKnowledgePointMapper questionKnowledgePointMapper,
                                QuestionSnapshotService questionSnapshotService,
                                KnowledgePointMapper knowledgePointMapper,
                                KnowledgeCategoryMapper knowledgeCategoryMapper,
                                LearningSpaceService learningSpaceService) {
        this.examBlueprintMapper = examBlueprintMapper;
        this.examMapper = examMapper;
        this.examPaperMapper = examPaperMapper;
        this.examQuestionMapper = examQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.questionSnapshotService = questionSnapshotService;
        this.knowledgePointMapper = knowledgePointMapper;
        this.knowledgeCategoryMapper = knowledgeCategoryMapper;
        this.learningSpaceService = learningSpaceService;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public ExamBlueprint create(String ownerSubject, Long spaceId, CreateExamBlueprintRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        validateRules(request.rules());

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ExamBlueprint blueprint = new ExamBlueprint();
        blueprint.setSpaceId(spaceId);
        blueprint.setTitle(request.title());
        blueprint.setRulesJson(toJson(request.rules()));
        blueprint.setStatus(STATUS_DRAFT);
        blueprint.setCreatedAt(now);
        blueprint.setUpdatedAt(now);
        examBlueprintMapper.insert(blueprint);
        return blueprint;
    }

    public List<ExamBlueprint> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return examBlueprintMapper.selectBySpaceOwner(spaceId, ownerSubject);
    }

    public ExamBlueprint getMine(String ownerSubject, Long spaceId, Long blueprintId) {
        return examBlueprintMapper.selectByIdSpaceOwner(blueprintId, spaceId, ownerSubject);
    }

    @Transactional
    public ExamBlueprint update(String ownerSubject, Long spaceId, Long blueprintId, CreateExamBlueprintRequest request) {
        ExamBlueprint existing = getMine(ownerSubject, spaceId, blueprintId);
        if (existing == null || !STATUS_DRAFT.equals(existing.getStatus())) {
            return null;
        }
        validateRules(request.rules());
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = examBlueprintMapper.updateByIdAndSpace(
                blueprintId, spaceId, request.title(), toJson(request.rules()), now);
        if (updated == 0) {
            return null;
        }
        existing.setTitle(request.title());
        existing.setRulesJson(toJson(request.rules()));
        existing.setUpdatedAt(now);
        return existing;
    }

    @Transactional
    public ExamBlueprintGenerateResponse generate(String ownerSubject, Long spaceId, Long blueprintId) {
        ExamBlueprint blueprint = getMine(ownerSubject, spaceId, blueprintId);
        if (blueprint == null || !STATUS_DRAFT.equals(blueprint.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "blueprint is not in DRAFT state or not found");
        }

        BlueprintRules rules = parseRules(blueprint.getRulesJson());

        List<Question> candidates = queryCandidates(ownerSubject, spaceId, rules);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no questions match the blueprint rules");
        }

        int totalCount = rules.totalQuestionCount() != null ? rules.totalQuestionCount() : candidates.size();
        if (totalCount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totalQuestionCount must be positive");
        }
        if (totalCount > candidates.size()) {
            totalCount = candidates.size();
        }

        List<Question> selected = candidates.subList(0, totalCount);

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        Exam exam = new Exam();
        exam.setSpaceId(spaceId);
        exam.setTitle(blueprint.getTitle() + " (Blueprint)");
        exam.setDescription("Generated from blueprint: " + blueprint.getTitle());
        exam.setExamType(ExamService.EXAM_TYPE_STANDARD);
        exam.setTimeLimitMinutes(rules.timeLimitMinutes());
        exam.setTotalScore(0);
        exam.setStatus(ExamService.STATUS_PUBLISHED);
        exam.setCreatedByUserId(ownerSubject);
        exam.setCreatedAt(now);
        exam.setUpdatedAt(now);
        exam.setPublishedAt(now);
        examMapper.insert(exam);

        ExamPaper paper = new ExamPaper();
        paper.setSpaceId(spaceId);
        paper.setExamId(exam.getId());
        paper.setPaperVersion(1);
        paper.setStatus(ExamService.PAPER_STATUS_PUBLISHED);
        paper.setCreatedAt(now);
        paper.setPublishedAt(now);
        examPaperMapper.insert(paper);

        int order = 0;
        int totalScore = 0;
        for (Question q : selected) {
            int score = resolveScore(rules, q);
            totalScore += score;
            ExamQuestion slot = new ExamQuestion();
            slot.setExamPaperId(paper.getId());
            slot.setSpaceId(spaceId);
            slot.setQuestionId(q.getId());
            slot.setSortOrder(order);
            slot.setScore(score);
            slot.setQuestionSnapshotJson(questionSnapshotService.buildSnapshot(q));
            slot.setCreatedAt(now);
            examQuestionMapper.insert(slot);
            order++;
        }
        exam.setTotalScore(totalScore);
        examMapper.updateById(exam);

        LocalDateTime updated = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        examBlueprintMapper.updateStatusByIdAndSpace(
                blueprint.getId(), spaceId, STATUS_DRAFT, STATUS_USED, updated);

        return new ExamBlueprintGenerateResponse(
                blueprint.getId(), exam.getId(), paper.getId(), 1, selected.size());
    }

    // ==================== internals ====================

    private List<Question> queryCandidates(String ownerSubject, Long spaceId, BlueprintRules rules) {
        List<Long> includeIds = rules.includeQuestionIds() != null ? new ArrayList<>(rules.includeQuestionIds()) : null;
        List<Long> excludeIds = rules.excludeQuestionIds() != null ? new ArrayList<>(rules.excludeQuestionIds()) : null;

        List<Question> pool;
        if (includeIds != null && !includeIds.isEmpty()) {
            pool = new ArrayList<>();
            for (Long qid : includeIds) {
                Question q = questionMapper.selectByIdSpaceOwner(qid, spaceId, ownerSubject);
                if (q != null && "PUBLISHED".equals(q.getStatus())) {
                    pool.add(q);
                }
            }
        } else {
            pool = questionMapper.selectBySpaceOwnerFilters(spaceId, ownerSubject, null, null, null);
        }

        pool.removeIf(q -> !"PUBLISHED".equals(q.getStatus()));

        if (excludeIds != null && !excludeIds.isEmpty()) {
            Set<Long> excludeSet = new HashSet<>(excludeIds);
            pool.removeIf(q -> excludeSet.contains(q.getId()));
        }

        if (rules.kpScope() != null && !rules.kpScope().isEmpty()) {
            Set<Long> kpSet = new HashSet<>(rules.kpScope());
            pool.removeIf(q -> {
                List<Long> qkp = questionKnowledgePointMapper
                        .selectKnowledgePointIdsByQuestionId(spaceId, q.getId());
                return qkp.stream().noneMatch(kpSet::contains);
            });
        }

        if (rules.categoryScope() != null && !rules.categoryScope().isEmpty()) {
            Set<Long> catSet = new HashSet<>(rules.categoryScope());
            pool.removeIf(q -> {
                var kps = questionKnowledgePointMapper.selectKnowledgePointIdsByQuestionId(spaceId, q.getId());
                for (Long kpId : kps) {
                    var kp = knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject);
                    if (kp != null && kp.getCategoryId() != null && catSet.contains(kp.getCategoryId())) {
                        return false;
                    }
                }
                return true;
            });
        }

        if (rules.questionTypeDistribution() != null && !rules.questionTypeDistribution().isEmpty()) {
            Map<String, Integer> typeCounts = new LinkedHashMap<>(rules.questionTypeDistribution());
            pool.removeIf(q -> {
                Integer maxForType = typeCounts.get(q.getQuestionType());
                return maxForType == null || maxForType <= 0;
            });
        }

        if (rules.difficultyDistribution() != null && !rules.difficultyDistribution().isEmpty()) {
            Map<String, Integer> diffCounts = new LinkedHashMap<>(rules.difficultyDistribution());
            pool.removeIf(q -> {
                String diff = q.getDifficulty();
                if (diff == null) diff = "MEDIUM";
                return !diffCounts.containsKey(diff);
            });
        }

        pool.sort(Comparator.comparingLong(Question::getId));
        return pool;
    }

    private int resolveScore(BlueprintRules rules, Question q) {
        String diff = q.getDifficulty();
        if (diff == null) diff = "MEDIUM";
        return switch (diff) {
            case "EASY" -> 2;
            case "HARD" -> 10;
            default -> 5;
        };
    }

    private void validateRules(BlueprintRules rules) {
        if (rules.questionTypeDistribution() != null) {
            for (String key : rules.questionTypeDistribution().keySet()) {
                if (key == null || key.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "questionTypeDistribution keys must not be blank");
                }
                if (rules.questionTypeDistribution().get(key) <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "questionTypeDistribution values must be positive: " + key);
                }
            }
        }
        if (rules.difficultyDistribution() != null) {
            for (String key : rules.difficultyDistribution().keySet()) {
                if (!ALLOWED_DIFFICULTIES.contains(key)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "difficultyDistribution key must be one of " + ALLOWED_DIFFICULTIES + ": " + key);
                }
                if (rules.difficultyDistribution().get(key) <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "difficultyDistribution values must be positive: " + key);
                }
            }
        }
        if (rules.totalQuestionCount() != null && rules.totalQuestionCount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totalQuestionCount must be positive when provided");
        }
        if (rules.timeLimitMinutes() != null && rules.timeLimitMinutes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "timeLimitMinutes must be positive when provided");
        }
    }

    private String toJson(BlueprintRules rules) {
        try {
            return objectMapper.writeValueAsString(rules);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "failed to serialize blueprint rules: " + e.getMessage());
        }
    }

    private BlueprintRules parseRules(String json) {
        try {
            return objectMapper.readValue(json, BlueprintRules.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid blueprint rules JSON: " + e.getMessage());
        }
    }
}

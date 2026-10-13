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

        // A declared distribution IS the requested paper length when the author
        // left totalQuestionCount out; falling back to the pool size would make
        // the quotas unsatisfiable by construction (F-24).
        int totalCount = rules.totalQuestionCount() != null
                ? rules.totalQuestionCount() : defaultTotalCount(rules, candidates);
        if (totalCount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totalQuestionCount must be positive");
        }

        // F-24: the distributions are quotas, not a filter followed by a
        // subList; a paper that cannot honour them is refused instead of being
        // silently reshaped.
        List<Question> selected = selectByQuotas(candidates, rules, totalCount);

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

    /**
     * Score of one slot: an explicit {@code scoreByDifficulty} rule wins, and
     * the constants below remain only the documented default (F-24).
     *
     * @throws com.aistudy.server.common.problem.ApiException when a declared
     *         score is not positive, since a zero-scored question would quietly
     *         distort diagnosis and mastery
     */
    private int resolveScore(BlueprintRules rules, Question q) {
        String diff = normalizeDifficulty(q.getDifficulty());
        Map<String, Integer> scores = rules.scoreByDifficulty();
        if (scores != null && scores.containsKey(diff)) {
            Integer declared = scores.get(diff);
            if (declared == null || declared <= 0) {
                throw new com.aistudy.server.common.problem.ApiException(
                        HttpStatus.BAD_REQUEST,
                        com.aistudy.server.common.problem.ApiErrorCodes.VALIDATION_ERROR,
                        "scoreByDifficulty " + diff + " must be a positive number");
            }
            return declared;
        }
        return switch (diff) {
            case "EASY" -> 2;
            case "HARD" -> 10;
            default -> 5;
        };
    }

    private static String normalizeDifficulty(String difficulty) {
        return difficulty == null ? "MEDIUM" : difficulty;
    }

    /**
     * Chooses exactly {@code totalCount} questions honouring BOTH declared
     * distributions, or refuses with {@code EXAM_BLUEPRINT_UNSATISFIED}
     * (audit F-24).
     *
     * <p>Before this the distributions only decided which types/difficulties
     * were allowed at all, and the paper was then {@code subList(0, total)} of
     * the id-ordered pool: a blueprint asking for 5 EASY and 5 HARD produced ten
     * questions of whichever type sorted first, and a pool smaller than the
     * total silently yielded a shorter paper that still reported success. The
     * generated exam therefore did not match the blueprint the user reviewed.
     *
     * <h3>How the quotas are met</h3>
     *
     * <p>Candidates are bucketed by (type, difficulty) in their existing
     * deterministic id order, and cells are filled scarcest-first, so a pair
     * with few available questions takes what it can before a richer cell spends
     * the same type or difficulty budget. The selection is then re-checked
     * against every quota and the total, so a mismatch is a refusal naming the
     * shortfall rather than a quiet degradation. Scarcest-first greedy is not a
     * proof of feasibility for every input, which is exactly why the
     * verification step exists.
     *
     * @param candidates same-space pool, already filtered and ordered by id
     * @param rules      the blueprint rules whose quotas must hold
     * @param totalCount questions the paper must contain
     * @return the selected questions, ordered by id
     * @throws com.aistudy.server.common.problem.ApiException 409
     *         {@code EXAM_BLUEPRINT_UNSATISFIED} when the pool cannot meet the
     *         quotas or the total
     * @throws ResponseStatusException 400 when the quotas contradict
     *         {@code totalQuestionCount}, which is an authoring mistake rather
     *         than a shortage of questions
     */
    static List<Question> selectByQuotas(List<Question> candidates, BlueprintRules rules,
                                         int totalCount) {
        Map<String, Integer> typeQuotas = rules.questionTypeDistribution();
        Map<String, Integer> difficultyQuotas = rules.difficultyDistribution();
        boolean typeConstrained = typeQuotas != null && !typeQuotas.isEmpty();
        boolean difficultyConstrained = difficultyQuotas != null && !difficultyQuotas.isEmpty();

        // Re-checked here because generate() reads rules persisted before this
        // validation existed (and rows written straight to the table).
        requireQuotasMatchTotal("questionTypeDistribution", typeQuotas, totalCount);
        requireQuotasMatchTotal("difficultyDistribution", difficultyQuotas, totalCount);

        if (!typeConstrained && !difficultyConstrained) {
            if (totalCount > candidates.size()) {
                throw unsatisfied("totalQuestionCount " + totalCount
                        + " but only " + candidates.size() + " matching questions exist");
            }
            return List.copyOf(candidates.subList(0, totalCount));
        }

        Map<String, Integer> remainingType = new java.util.HashMap<>(
                typeConstrained ? typeQuotas : Map.of());
        Map<String, Integer> remainingDifficulty = new java.util.HashMap<>(
                difficultyConstrained ? difficultyQuotas : Map.of());

        Map<String, Map<String, java.util.ArrayDeque<Question>>> buckets =
                new java.util.LinkedHashMap<>();
        for (Question question : candidates) {
            buckets.computeIfAbsent(question.getQuestionType(), key -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(normalizeDifficulty(question.getDifficulty()),
                            key -> new java.util.ArrayDeque<>())
                    .add(question);
        }

        Set<String> types = new java.util.LinkedHashSet<>();
        if (typeConstrained) {
            types.addAll(typeQuotas.keySet());
        } else {
            types.addAll(buckets.keySet());
        }
        List<String[]> cells = new ArrayList<>();
        for (String type : types) {
            Map<String, java.util.ArrayDeque<Question>> byDifficulty =
                    buckets.getOrDefault(type, Map.of());
            Set<String> difficulties = new java.util.LinkedHashSet<>();
            if (difficultyConstrained) {
                difficulties.addAll(difficultyQuotas.keySet());
            } else {
                difficulties.addAll(byDifficulty.keySet());
            }
            for (String difficulty : difficulties) {
                int available = byDifficulty.getOrDefault(difficulty,
                        new java.util.ArrayDeque<>()).size();
                cells.add(new String[]{type, difficulty, String.valueOf(available)});
            }
        }
        cells.sort(Comparator.comparingInt((String[] cell) -> Integer.parseInt(cell[2]))
                .thenComparing(cell -> cell[0])
                .thenComparing(cell -> cell[1]));

        List<Question> selected = new ArrayList<>();
        for (String[] cell : cells) {
            if (selected.size() >= totalCount) {
                break;
            }
            String type = cell[0];
            String difficulty = cell[1];
            int take = totalCount - selected.size();
            if (typeConstrained) {
                take = Math.min(take, remainingType.getOrDefault(type, 0));
            }
            if (difficultyConstrained) {
                take = Math.min(take, remainingDifficulty.getOrDefault(difficulty, 0));
            }
            java.util.ArrayDeque<Question> pool = buckets.getOrDefault(type, Map.of())
                    .get(difficulty);
            if (pool == null || take <= 0) {
                continue;
            }
            for (int i = 0; i < take && !pool.isEmpty(); i++) {
                selected.add(pool.removeFirst());
                if (typeConstrained) {
                    remainingType.put(type, remainingType.getOrDefault(type, 0) - 1);
                }
                if (difficultyConstrained) {
                    remainingDifficulty.put(difficulty,
                            remainingDifficulty.getOrDefault(difficulty, 0) - 1);
                }
            }
        }

        if (selected.size() != totalCount) {
            // Both quota maps add up to totalCount and every take is bounded by
            // its remaining budget, so a shortfall here is the only way the
            // marginals can be missed: name the unmet quotas in the refusal.
            List<String> remaining = new ArrayList<>();
            if (typeConstrained) {
                for (Map.Entry<String, Integer> entry : remainingType.entrySet()) {
                    if (entry.getValue() != 0) {
                        remaining.add("type " + entry.getKey() + " short by " + entry.getValue());
                    }
                }
            }
            if (difficultyConstrained) {
                for (Map.Entry<String, Integer> entry : remainingDifficulty.entrySet()) {
                    if (entry.getValue() != 0) {
                        remaining.add("difficulty " + entry.getKey() + " short by " + entry.getValue());
                    }
                }
            }
            throw unsatisfied("cannot reach " + totalCount + " questions from "
                    + candidates.size() + " matching questions: " + String.join("; ", remaining));
        }

        selected.sort(Comparator.comparingLong(Question::getId));
        return List.copyOf(selected);
    }

    private static void requireQuotasMatchTotal(String name, Map<String, Integer> quotas, int total) {
        if (quotas == null || quotas.isEmpty()) {
            return;
        }
        int sum = sum(quotas);
        if (sum != total) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must add up to totalQuestionCount: asked " + total
                            + ", distribution sums to " + sum);
        }
    }

    private static int defaultTotalCount(BlueprintRules rules, List<Question> candidates) {
        Map<String, Integer> typeQuotas = rules.questionTypeDistribution();
        if (typeQuotas != null && !typeQuotas.isEmpty()) {
            return sum(typeQuotas);
        }
        Map<String, Integer> difficultyQuotas = rules.difficultyDistribution();
        if (difficultyQuotas != null && !difficultyQuotas.isEmpty()) {
            return sum(difficultyQuotas);
        }
        return candidates.size();
    }

    private static int sum(Map<String, Integer> quotas) {
        int total = 0;
        for (Integer value : quotas.values()) {
            total += value == null ? 0 : value;
        }
        return total;
    }

    private static com.aistudy.server.common.problem.ApiException unsatisfied(String detail) {
        return com.aistudy.server.common.problem.ApiException.conflict(
                com.aistudy.server.common.problem.ApiErrorCodes.EXAM_BLUEPRINT_UNSATISFIED,
                "blueprint cannot be satisfied: " + detail);
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
        // F-24: scores are honoured at generate time, so an unusable entry has
        // to be rejected while the blueprint is still editable.
        if (rules.scoreByDifficulty() != null) {
            for (Map.Entry<String, Integer> entry : rules.scoreByDifficulty().entrySet()) {
                if (!ALLOWED_DIFFICULTIES.contains(entry.getKey())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "scoreByDifficulty key must be one of " + ALLOWED_DIFFICULTIES + ": " + entry.getKey());
                }
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "scoreByDifficulty values must be positive: " + entry.getKey());
                }
            }
        }
        if (rules.totalQuestionCount() != null && rules.totalQuestionCount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totalQuestionCount must be positive when provided");
        }
        // Reject a self-contradictory blueprint while it is still editable,
        // instead of only discovering it when generate() computes the paper.
        if (rules.totalQuestionCount() != null && rules.totalQuestionCount() > 0) {
            requireQuotasMatchTotal("questionTypeDistribution", rules.questionTypeDistribution(),
                    rules.totalQuestionCount());
            requireQuotasMatchTotal("difficultyDistribution", rules.difficultyDistribution(),
                    rules.totalQuestionCount());
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

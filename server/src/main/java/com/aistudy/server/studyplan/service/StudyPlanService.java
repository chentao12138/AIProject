package com.aistudy.server.studyplan.service;

import com.aistudy.server.exam.entity.ExamDiagnosisItem;
import com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.mastery.entity.Mastery;
import com.aistudy.server.mastery.mapper.MasteryMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import com.aistudy.server.studyplan.dto.StudyPlanDto.GenerateStudyPlanRequest;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyPlanView;
import com.aistudy.server.studyplan.dto.StudyPlanDto.StudyTaskView;
import com.aistudy.server.studyplan.entity.StudyPlan;
import com.aistudy.server.studyplan.entity.StudyTask;
import com.aistudy.server.studyplan.mapper.StudyPlanMapper;
import com.aistudy.server.studyplan.mapper.StudyTaskMapper;
import com.aistudy.server.wrong.entity.ReviewTask;
import com.aistudy.server.wrong.mapper.ReviewTaskMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BUSINESS-016 — StudyPlan / StudyTask application service.
 *
 * <p>Conservative V1 lifecycle:
 *
 * <ul>
 *   <li>at most ONE ACTIVE plan per (user_subject, space_id);
 *       generating while an ACTIVE plan exists → 409 (no silent
 *       auto-archive).</li>
 *   <li>plan becomes COMPLETED when every task is DONE/SKIPPED.</li>
 *   <li>generation is DETERMINISTIC (no AI, no randomization) from
 *       current backend facts: pending review tasks first (due ASC),
 *       then weakest mastery points (mastery_score ASC, confidence
 *       ASC, stable id ASC), bounded by {@code dailyItemLimit}.</li>
 *   <li>no duplicate tasks for the same logical target within one
 *       plan; a review task covering a knowledge point suppresses a
 *       mastery-derived task for the same point.</li>
 *   <li>no fabricated EXAM tasks (V1 never invents a concrete exam).</li>
 *   <li>no client-submitted mastery/score values anywhere.</li>
 * </ul>
 */
@Service
public class StudyPlanService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String TASK_STATUS_TODO = "TODO";
    public static final String TASK_STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String TASK_STATUS_DONE = "DONE";
    public static final String TASK_STATUS_SKIPPED = "SKIPPED";

    public static final String TASK_TYPE_LEARN = "LEARN";
    public static final String TASK_TYPE_PRACTICE = "PRACTICE";
    public static final String TASK_TYPE_REVIEW = "REVIEW";
    public static final String TASK_TYPE_EXAM = "EXAM";

    public static final String TARGET_QUESTION = "QUESTION";
    public static final String TARGET_KNOWLEDGE_POINT = "KNOWLEDGE_POINT";
    public static final String TARGET_EXAM = "EXAM";

    public static final int DEFAULT_DAILY_LIMIT = 10;
    public static final int MAX_DAILY_LIMIT = 50;

    /** Priority thresholds on mastery_score for generated tasks. */
    public static final double PRIORITY_HIGH_BELOW = 0.6;
    public static final double PRIORITY_MEDIUM_BELOW = 0.85;

    private final StudyPlanMapper studyPlanMapper;
    private final StudyTaskMapper studyTaskMapper;
    private final LearningSpaceService learningSpaceService;
    private final ReviewTaskMapper reviewTaskMapper;
    private final MasteryMapper masteryMapper;
    private final QuestionMapper questionMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ExamDiagnosisItemMapper examDiagnosisItemMapper;

    public StudyPlanService(StudyPlanMapper studyPlanMapper,
                            StudyTaskMapper studyTaskMapper,
                            LearningSpaceService learningSpaceService,
                            ReviewTaskMapper reviewTaskMapper,
                            MasteryMapper masteryMapper,
                            QuestionMapper questionMapper,
                            KnowledgePointMapper knowledgePointMapper,
                            ExamDiagnosisItemMapper examDiagnosisItemMapper) {
        this.studyPlanMapper = studyPlanMapper;
        this.studyTaskMapper = studyTaskMapper;
        this.learningSpaceService = learningSpaceService;
        this.reviewTaskMapper = reviewTaskMapper;
        this.masteryMapper = masteryMapper;
        this.questionMapper = questionMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.examDiagnosisItemMapper = examDiagnosisItemMapper;
    }

    /**
     * Generates a new ACTIVE plan with deterministic tasks.
     *
     * @return the created plan view
     * @throws ResponseStatusException 404 (space not owned), 400
     *         (date order / limit violation), 409 (ACTIVE plan
     *         exists, or no plan candidates at all)
     */
    @Transactional
    public StudyPlanView generate(String ownerSubject, Long spaceId,
                                  GenerateStudyPlanRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        if (request.startDate() != null && request.endDate() != null
                && request.startDate().isAfter(request.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must not be after endDate");
        }
        int limit = request.dailyItemLimit() == null ? DEFAULT_DAILY_LIMIT : request.dailyItemLimit();
        if (limit < 1 || limit > MAX_DAILY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dailyItemLimit must be between 1 and " + MAX_DAILY_LIMIT);
        }
        if (studyPlanMapper.selectByUserSpaceStatus(
                ownerSubject, spaceId, STATUS_ACTIVE, ownerSubject) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an ACTIVE study plan already exists for this space");
        }

        List<Candidate> candidates = collectCandidates(ownerSubject, spaceId);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no plan candidates: no pending review tasks and no mastery evidence");
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        StudyPlan plan = new StudyPlan();
        plan.setUserSubject(ownerSubject);
        plan.setSpaceId(spaceId);
        plan.setName(request.name());
        plan.setStartDate(request.startDate());
        plan.setEndDate(request.endDate());
        plan.setStatus(STATUS_ACTIVE);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        studyPlanMapper.insert(plan);

        List<StudyTask> tasks = new ArrayList<>();
        int count = Math.min(limit, candidates.size());
        for (int i = 0; i < count; i++) {
            Candidate c = candidates.get(i);
            StudyTask task = new StudyTask();
            task.setStudyPlanId(plan.getId());
            task.setUserSubject(ownerSubject);
            task.setSpaceId(spaceId);
            task.setTaskType(c.taskType);
            task.setTargetType(c.targetType);
            task.setTargetId(c.targetId);
            task.setTitle(c.title);
            task.setReason(c.reason);
            task.setDueAt(c.dueAt);
            task.setPriority(c.priority);
            task.setStatus(TASK_STATUS_TODO);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            studyTaskMapper.insert(task);
            tasks.add(task);
        }
        return StudyPlanView.from(plan, tasks);
    }

    /**
     * The current plan (latest of any status) of the caller's space.
     *
     * @return null → 404 (no plan yet)
     */
    public StudyPlanView getCurrent(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        StudyPlan plan = studyPlanMapper.selectLatestByUserSpace(
                ownerSubject, spaceId, ownerSubject);
        if (plan == null) {
            return null;
        }
        return StudyPlanView.from(plan, studyTaskMapper.selectByPlanId(plan.getId()));
    }

    /**
     * Completes a TODO/IN_PROGRESS task (DONE + completed_at).
     * DONE → idempotent 200 (no timestamp refresh); SKIPPED → 409.
     * When the last open task completes, the plan ACTIVE → COMPLETED.
     *
     * @throws ResponseStatusException 404 / 409
     */
    @Transactional
    public StudyTaskView complete(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = studyTaskMapper.selectByIdSpaceOwnerUser(
                taskId, spaceId, ownerSubject, ownerSubject);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyTask not found");
        }
        if (TASK_STATUS_SKIPPED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a SKIPPED study task cannot be completed: " + task.getStatus());
        }
        if (TASK_STATUS_DONE.equals(task.getStatus())) {
            return StudyTaskView.from(task); // idempotent
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.completeByIdAndSpace(
                taskId, spaceId, ownerSubject,
                TASK_STATUS_TODO, TASK_STATUS_IN_PROGRESS, TASK_STATUS_DONE, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setStatus(TASK_STATUS_DONE);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);

        maybeCompletePlan(ownerSubject, spaceId, task.getStudyPlanId(), now);
        return StudyTaskView.from(task);
    }

    // ==================== generation internals ====================

    /** One generated task candidate (pre-persistence). */
    private record Candidate(String taskType, String targetType, Long targetId,
                             String title, String reason, LocalDateTime dueAt,
                             String priority) {
    }

    /**
     * Deterministic candidate list: pending review tasks (due ASC,
     * id ASC) first, then weakest mastery points (mastery_score ASC,
     * confidence ASC, id ASC). Dedupes by logical target — a review
     * task for a knowledge point suppresses a mastery task for it.
     * EXAM tasks are added from latest diagnosis weak points (accuracy
     * < 0.6) targeting the originating exam.
     */
    private List<Candidate> collectCandidates(String ownerSubject, Long spaceId) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ReviewTask rt : reviewTaskMapper.selectPendingBySpaceOwnerUser(
                spaceId, ownerSubject, ownerSubject)) {
            String key = rt.getTargetType() + ":" + rt.getTargetId();
            if (!seen.add(key)) {
                continue;
            }
            candidates.add(new Candidate(TASK_TYPE_REVIEW, rt.getTargetType(),
                    rt.getTargetId(), titleOf(ownerSubject, spaceId, rt.getTargetType(),
                    rt.getTargetId()), rt.getReason(), rt.getDueAt(), rt.getPriority()));
        }

        Map<Long, ExamDiagnosisItem> latestKpDiagnosis = latestKpDiagnosis(
                ownerSubject, spaceId);
        List<Mastery> masteryRows = new ArrayList<>(
                masteryMapper.selectBySpaceOwnerUser(spaceId, ownerSubject, ownerSubject));
        masteryRows.sort(Comparator
                .comparingDouble(Mastery::getMasteryScore)
                .thenComparingDouble(Mastery::getConfidence)
                .thenComparingLong(Mastery::getId));
        for (Mastery m : masteryRows) {
            String key = TARGET_KNOWLEDGE_POINT + ":" + m.getKnowledgePointId();
            if (!seen.add(key)) {
                continue; // review task already covers this point
            }
            int graded = m.getPracticeEvidenceCount() + m.getExamEvidenceCount();
            String taskType = graded == 0 ? TASK_TYPE_LEARN : TASK_TYPE_PRACTICE;
            candidates.add(new Candidate(taskType, TARGET_KNOWLEDGE_POINT,
                    m.getKnowledgePointId(), kpTitle(ownerSubject, spaceId, m.getKnowledgePointId()),
                    reasonFor(m, latestKpDiagnosis.get(m.getKnowledgePointId())),
                    null, priorityOf(m)));
        }

        for (Map.Entry<Long, ExamDiagnosisItem> e : latestKpDiagnosis.entrySet()) {
            ExamDiagnosisItem item = e.getValue();
            if (item.getAccuracy() == null || item.getAccuracy() >= 0.6) {
                continue;
            }
            Long examId = examIdOfDiagnosisItem(ownerSubject, spaceId, item.getExamDiagnosisId());
            if (examId == null) {
                continue;
            }
            String key = TARGET_EXAM + ":" + examId;
            if (!seen.add(key)) {
                continue;
            }
            String title = "Exam " + examId + " retry (weak: " + item.getLabel() + ")";
            String reason = String.format(java.util.Locale.ROOT,
                    "Diagnosis accuracy %.2f for %s",
                    item.getAccuracy(), item.getLabel());
            candidates.add(new Candidate(TASK_TYPE_EXAM, TARGET_EXAM,
                    examId, title, reason, null, "HIGH"));
        }
        return candidates;
    }

    private String reasonFor(Mastery m, ExamDiagnosisItem diagItem) {
        if (diagItem != null) {
            return String.format(Locale.ROOT,
                    "Exam diagnosis: accuracy %.2f (score %d/%d)",
                    diagItem.getAccuracy(), diagItem.getScore(), diagItem.getMaxScore());
        }
        return String.format(Locale.ROOT, "Mastery score %.2f, confidence %.2f",
                m.getMasteryScore(), m.getConfidence());
    }

    private String priorityOf(Mastery m) {
        if (m.getMasteryScore() < PRIORITY_HIGH_BELOW) {
            return "HIGH";
        }
        return m.getMasteryScore() < PRIORITY_MEDIUM_BELOW ? "MEDIUM" : "LOW";
    }

    /** KP items of the user's latest diagnosis in the space, keyed by kp id. */
    private Map<Long, ExamDiagnosisItem> latestKpDiagnosis(String ownerSubject, Long spaceId) {
        Map<Long, ExamDiagnosisItem> byKp = new HashMap<>();
        for (ExamDiagnosisItem item : examDiagnosisItemMapper
                .selectLatestKpItemsByUserSpace(ownerSubject, spaceId)) {
            if (item.getDimensionId() != null) {
                byKp.putIfAbsent(item.getDimensionId(), item);
            }
        }
        return byKp;
    }

    private String titleOf(String ownerSubject, Long spaceId, String targetType, Long targetId) {
        if (TARGET_QUESTION.equals(targetType)) {
            Question q = questionMapper.selectByIdSpaceOwner(targetId, spaceId, ownerSubject);
            if (q != null && q.getStem() != null) {
                return truncate(q.getStem(), 250);
            }
            return "Question " + targetId;
        }
        return kpTitle(ownerSubject, spaceId, targetId);
    }

    private String kpTitle(String ownerSubject, Long spaceId, Long kpId) {
        KnowledgePoint kp = knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject);
        return kp == null ? "KnowledgePoint " + kpId : kp.getTitle();
    }

    private Long examIdOfDiagnosisItem(String ownerSubject, Long spaceId, Long examDiagnosisId) {
        return examDiagnosisItemMapper.selectExamIdByDiagnosisId(
                examDiagnosisId, spaceId, ownerSubject);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void maybeCompletePlan(String ownerSubject, Long spaceId, Long planId,
                                   LocalDateTime now) {
        if (studyTaskMapper.countOpenByPlanId(planId) == 0) {
            studyPlanMapper.updateStatusById(planId, STATUS_ACTIVE, STATUS_COMPLETED, now);
        }
    }

    /** §7.6 bump priority of open study tasks whose target KP just got a mastery recompute. */
    @Transactional
    public void bumpRelatedStudyTaskPriorities(String ownerSubject, Long spaceId, Long kpId) {
        StudyPlanView current = getCurrent(ownerSubject, spaceId);
        if (current == null || !STATUS_ACTIVE.equals(current.status())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (StudyTaskView task : current.tasks()) {
            if (TARGET_KNOWLEDGE_POINT.equals(task.targetType())
                    && kpId.equals(task.targetId())
                    && (TASK_STATUS_TODO.equals(task.status())
                        || TASK_STATUS_IN_PROGRESS.equals(task.status()))) {
                Mastery mastery = masteryMapper.selectByUserSpaceKp(ownerSubject, spaceId, kpId);
                if (mastery != null && mastery.getMasteryScore() != null
                        && mastery.getMasteryScore() < PRIORITY_HIGH_BELOW) {
                    if (!"HIGH".equals(task.priority())) {
                        studyTaskMapper.updatePriorityByIdAndSpace(
                                task.id(), spaceId, ownerSubject, "HIGH", now);
                    }
                }
            }
        }
    }

    // ==================== §7.4 user control ====================

    @Transactional
    public StudyTaskView skip(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = requireOpenTask(ownerSubject, spaceId, taskId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.skipByIdAndSpace(
                taskId, spaceId, ownerSubject, now, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setStatus(TASK_STATUS_SKIPPED);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        maybeCompletePlan(ownerSubject, spaceId, task.getStudyPlanId(), now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyTaskView unskip(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = studyTaskMapper.selectByIdSpaceOwnerUser(
                taskId, spaceId, ownerSubject, ownerSubject);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyTask not found");
        }
        if (!TASK_STATUS_SKIPPED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only SKIPPED tasks can be unskipped: " + task.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.unskipByIdAndSpace(
                taskId, spaceId, ownerSubject, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setStatus(TASK_STATUS_TODO);
        task.setCompletedAt(null);
        task.setUpdatedAt(now);
        studyPlanMapper.updateStatusById(task.getStudyPlanId(), STATUS_COMPLETED, STATUS_ACTIVE, now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyTaskView markInProgress(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = requireOpenTask(ownerSubject, spaceId, taskId);
        if (TASK_STATUS_IN_PROGRESS.equals(task.getStatus())) {
            return StudyTaskView.from(task);
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.markInProgressByIdAndSpace(
                taskId, spaceId, ownerSubject, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setStatus(TASK_STATUS_IN_PROGRESS);
        task.setUpdatedAt(now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyTaskView adjustDueAt(String ownerSubject, Long spaceId, Long taskId,
                                     java.time.LocalDateTime dueAt) {
        StudyTask task = requireOpenTask(ownerSubject, spaceId, taskId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.updateDueAtByIdAndSpace(
                taskId, spaceId, ownerSubject, dueAt, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setDueAt(dueAt);
        task.setUpdatedAt(now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyTaskView adjustPriority(String ownerSubject, Long spaceId, Long taskId,
                                        String priority) {
        StudyTask task = requireTask(ownerSubject, spaceId, taskId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.updatePriorityByIdAndSpace(
                taskId, spaceId, ownerSubject, priority, now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setPriority(priority);
        task.setUpdatedAt(now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyPlanView reorder(String ownerSubject, Long spaceId, Long planId,
                                 List<Long> orderedTaskIds) {
        StudyPlan plan = studyPlanMapper.selectLatestByUserSpace(
                ownerSubject, spaceId, ownerSubject);
        if (plan == null || !planId.equals(plan.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyPlan not found");
        }
        List<StudyTask> existing = studyTaskMapper.selectByPlanId(planId);
        if (existing.size() != orderedTaskIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "orderedTaskIds must contain all task ids of the plan");
        }
        java.util.Set<Long> expected = new java.util.HashSet<>();
        for (StudyTask t : existing) {
            expected.add(t.getId());
        }
        for (Long id : orderedTaskIds) {
            if (!expected.contains(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "task id does not belong to this plan: " + id);
            }
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int order = 0;
        for (Long taskId : orderedTaskIds) {
            int u = studyTaskMapper.reorderTask(taskId, planId, order++, now);
            if (u == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "reorder failed for task: " + taskId);
            }
        }
        return StudyPlanView.from(plan, studyTaskMapper.selectByPlanId(planId));
    }

    @Transactional
    public StudyTaskView editTitleReason(String ownerSubject, Long spaceId, Long taskId,
                                         String title, String reason) {
        StudyTask task = requireTask(ownerSubject, spaceId, taskId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = studyTaskMapper.updateTitleReasonByIdAndSpace(
                taskId, spaceId, ownerSubject,
                title != null ? title : task.getTitle(),
                reason != null ? reason : task.getReason(),
                now);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "study task state changed concurrently");
        }
        task.setTitle(title != null ? title : task.getTitle());
        task.setReason(reason != null ? reason : task.getReason());
        task.setUpdatedAt(now);
        return StudyTaskView.from(task);
    }

    @Transactional
    public StudyPlanView regenerate(String ownerSubject, Long spaceId, Long planId) {
        StudyPlan plan = studyPlanMapper.selectLatestByUserSpace(
                ownerSubject, spaceId, ownerSubject);
        if (plan == null || !planId.equals(plan.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyPlan not found");
        }
        if (STATUS_COMPLETED.equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot regenerate a COMPLETED plan");
        }
        List<StudyTask> existing = studyTaskMapper.selectByPlanId(planId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (StudyTask task : existing) {
            if (TASK_STATUS_DONE.equals(task.getStatus())
                    || TASK_STATUS_SKIPPED.equals(task.getStatus())) {
                continue;
            }
            studyTaskMapper.updateTitleReasonByIdAndSpace(
                    task.getId(), spaceId, ownerSubject,
                    task.getTitle() + " (replaced)",
                    task.getReason(),
                    now);
            studyTaskMapper.skipByIdAndSpace(
                    task.getId(), spaceId, ownerSubject, now, now);
        }
        List<Candidate> candidates = collectCandidates(ownerSubject, spaceId);
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no plan candidates for regeneration");
        }
        int maxNew = Math.min(DEFAULT_DAILY_LIMIT, candidates.size());
        for (int i = 0; i < maxNew; i++) {
            Candidate c = candidates.get(i);
            StudyTask task = new StudyTask();
            task.setStudyPlanId(planId);
            task.setUserSubject(ownerSubject);
            task.setSpaceId(spaceId);
            task.setTaskType(c.taskType);
            task.setTargetType(c.targetType);
            task.setTargetId(c.targetId);
            task.setTitle(c.title);
            task.setReason(c.reason);
            task.setDueAt(c.dueAt);
            task.setPriority(c.priority);
            task.setStatus(TASK_STATUS_TODO);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            studyTaskMapper.insert(task);
        }
        studyPlanMapper.updateStatusById(planId, STATUS_COMPLETED, STATUS_ACTIVE, now);
        return StudyPlanView.from(plan, studyTaskMapper.selectByPlanId(planId));
    }

    // ==================== task helpers ====================

    private StudyTask requireTask(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = studyTaskMapper.selectByIdSpaceOwnerUser(
                taskId, spaceId, ownerSubject, ownerSubject);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "StudyTask not found");
        }
        return task;
    }

    private StudyTask requireOpenTask(String ownerSubject, Long spaceId, Long taskId) {
        StudyTask task = requireTask(ownerSubject, spaceId, taskId);
        if (TASK_STATUS_DONE.equals(task.getStatus()) || TASK_STATUS_SKIPPED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "task is already closed: " + task.getStatus());
        }
        return task;
    }
}

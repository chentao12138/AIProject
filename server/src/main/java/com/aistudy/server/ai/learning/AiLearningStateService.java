package com.aistudy.server.ai.learning;

import com.aistudy.server.ai.config.AiProperties;
import com.aistudy.server.exam.entity.ExamDiagnosisItem;
import com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.mastery.entity.Mastery;
import com.aistudy.server.mastery.mapper.MasteryMapper;
import com.aistudy.server.studyplan.entity.StudyPlan;
import com.aistudy.server.studyplan.entity.StudyTask;
import com.aistudy.server.studyplan.mapper.StudyPlanMapper;
import com.aistudy.server.studyplan.mapper.StudyTaskMapper;
import com.aistudy.server.wrong.entity.WrongQuestion;
import com.aistudy.server.wrong.mapper.WrongQuestionMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI-006 — bounded learning-state assembly from existing domain read models.
 * All reads are space+user scoped via existing owner-scoped mappers.
 */
@Service
public class AiLearningStateService {

    private static final Logger log = LoggerFactory.getLogger(AiLearningStateService.class);

    private final MasteryMapper masteryMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final WrongQuestionMapper wrongQuestionMapper;
    private final ExamDiagnosisItemMapper examDiagnosisItemMapper;
    private final StudyPlanMapper studyPlanMapper;
    private final StudyTaskMapper studyTaskMapper;
    private final AiProperties aiProperties;

    public AiLearningStateService(MasteryMapper masteryMapper,
                                  KnowledgePointMapper knowledgePointMapper,
                                  WrongQuestionMapper wrongQuestionMapper,
                                  ExamDiagnosisItemMapper examDiagnosisItemMapper,
                                  StudyPlanMapper studyPlanMapper,
                                  StudyTaskMapper studyTaskMapper,
                                  AiProperties aiProperties) {
        this.masteryMapper = masteryMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.wrongQuestionMapper = wrongQuestionMapper;
        this.examDiagnosisItemMapper = examDiagnosisItemMapper;
        this.studyPlanMapper = studyPlanMapper;
        this.studyTaskMapper = studyTaskMapper;
        this.aiProperties = aiProperties;
    }

    public AiLearningState assemble(String ownerSubject, Long spaceId) {
        int limit = Math.max(1, aiProperties.getContext().getMaxLearningStateItems());
        List<AiLearningState.WeakKnowledgePoint> weak = new ArrayList<>();
        List<AiLearningState.WrongSignal> wrong = new ArrayList<>();
        List<AiLearningState.DiagnosisWeakness> diagnosis = new ArrayList<>();
        List<AiLearningState.NextAction> next = new ArrayList<>();
        boolean hasPlan = false;

        try {
            List<Mastery> masteries = masteryMapper.selectBySpaceOwnerUser(
                    spaceId, ownerSubject, ownerSubject);
            Map<Long, String> kpTitles = loadKpTitles(ownerSubject, spaceId);
            if (masteries != null) {
                for (Mastery m : masteries) {
                    if (weak.size() >= limit) {
                        break;
                    }
                    Double score = m.getMasteryScore();
                    if (score == null || score >= 0.7) {
                        continue;
                    }
                    weak.add(new AiLearningState.WeakKnowledgePoint(
                            m.getKnowledgePointId(),
                            kpTitles.getOrDefault(m.getKnowledgePointId(), ""),
                            score,
                            m.getConfidence()));
                }
            }

            List<WrongQuestion> wrongRows = wrongQuestionMapper.selectBySpaceOwnerUser(
                    spaceId, ownerSubject, ownerSubject);
            if (wrongRows != null) {
                for (WrongQuestion wq : wrongRows) {
                    if (wrong.size() >= limit) {
                        break;
                    }
                    wrong.add(new AiLearningState.WrongSignal(
                            wq.getQuestionId(),
                            wq.getWrongCount(),
                            wq.getStatus()));
                }
            }

            List<ExamDiagnosisItem> items =
                    examDiagnosisItemMapper.selectLatestKpItemsByUserSpace(ownerSubject, spaceId);
            if (items != null) {
                for (ExamDiagnosisItem item : items) {
                    if (diagnosis.size() >= limit) {
                        break;
                    }
                    diagnosis.add(new AiLearningState.DiagnosisWeakness(
                            item.getDimensionId(),
                            item.getLabel(),
                            item.getSeverity(),
                            item.getRecommendation()));
                }
            }

            StudyPlan plan = studyPlanMapper.selectByUserSpaceStatus(
                    ownerSubject, spaceId, "ACTIVE", ownerSubject);
            if (plan == null) {
                plan = studyPlanMapper.selectLatestByUserSpace(ownerSubject, spaceId, ownerSubject);
            }
            if (plan != null) {
                hasPlan = "ACTIVE".equals(plan.getStatus());
                List<StudyTask> tasks = studyTaskMapper.selectByPlanId(plan.getId());
                if (tasks != null) {
                    for (StudyTask task : tasks) {
                        if (next.size() >= limit) {
                            break;
                        }
                        if (!"PENDING".equals(task.getStatus()) && !"IN_PROGRESS".equals(task.getStatus())) {
                            continue;
                        }
                        next.add(new AiLearningState.NextAction(
                                task.getId(),
                                task.getTaskType(),
                                task.getTargetType(),
                                task.getTargetId(),
                                task.getTitle(),
                                task.getStatus()));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("learning state assembly failed: {}", e.getClass().getSimpleName());
            return AiLearningState.empty();
        }

        return new AiLearningState(weak, wrong, diagnosis, next, hasPlan);
    }

    private Map<Long, String> loadKpTitles(String ownerSubject, Long spaceId) {
        Map<Long, String> titles = new HashMap<>();
        try {
            List<KnowledgePoint> points = knowledgePointMapper.selectBySpaceOwner(spaceId, ownerSubject);
            if (points != null) {
                for (KnowledgePoint point : points) {
                    titles.put(point.getId(), point.getTitle());
                }
            }
        } catch (RuntimeException ignored) {
            // titles are optional enrichment
        }
        return titles;
    }
}

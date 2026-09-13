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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AI-006 — learning-state assembly bounds and fail-soft.
 */
class AiLearningStateServiceTest {

    private MasteryMapper masteryMapper;
    private KnowledgePointMapper knowledgePointMapper;
    private WrongQuestionMapper wrongQuestionMapper;
    private ExamDiagnosisItemMapper examDiagnosisItemMapper;
    private StudyPlanMapper studyPlanMapper;
    private StudyTaskMapper studyTaskMapper;
    private AiProperties properties;
    private AiLearningStateService service;

    @BeforeEach
    void setUp() {
        masteryMapper = Mockito.mock(MasteryMapper.class);
        knowledgePointMapper = Mockito.mock(KnowledgePointMapper.class);
        wrongQuestionMapper = Mockito.mock(WrongQuestionMapper.class);
        examDiagnosisItemMapper = Mockito.mock(ExamDiagnosisItemMapper.class);
        studyPlanMapper = Mockito.mock(StudyPlanMapper.class);
        studyTaskMapper = Mockito.mock(StudyTaskMapper.class);
        properties = new AiProperties();
        service = new AiLearningStateService(
                masteryMapper, knowledgePointMapper, wrongQuestionMapper,
                examDiagnosisItemMapper, studyPlanMapper, studyTaskMapper, properties);
    }

    @Test
    void emptyDomainDataYieldsEmptyState() {
        when(masteryMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(wrongQuestionMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString()))
                .thenReturn(List.of());
        when(examDiagnosisItemMapper.selectLatestKpItemsByUserSpace(anyString(), anyLong()))
                .thenReturn(List.of());
        when(studyPlanMapper.selectByUserSpaceStatus(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(null);
        when(studyPlanMapper.selectLatestByUserSpace(anyString(), anyLong(), anyString()))
                .thenReturn(null);
        when(knowledgePointMapper.selectBySpaceOwner(anyLong(), anyString())).thenReturn(List.of());

        AiLearningState state = service.assemble("u1", 1L);
        assertTrue(state.weakKnowledgePoints().isEmpty());
        assertTrue(state.wrongSignals().isEmpty());
        assertTrue(state.diagnosisWeaknesses().isEmpty());
        assertTrue(state.nextActions().isEmpty());
        assertFalse(state.hasActivePlan());
    }

    @Test
    void weakMasteryBoundedAndFilteredByScore() {
        List<Mastery> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Mastery m = new Mastery();
            m.setKnowledgePointId((long) i);
            m.setMasteryScore(0.1);
            m.setConfidence(0.5);
            rows.add(m);
        }
        Mastery strong = new Mastery();
        strong.setKnowledgePointId(999L);
        strong.setMasteryScore(0.9);
        rows.add(strong);
        when(masteryMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString())).thenReturn(rows);
        when(wrongQuestionMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString())).thenReturn(List.of());
        when(examDiagnosisItemMapper.selectLatestKpItemsByUserSpace(anyString(), anyLong())).thenReturn(List.of());
        when(studyPlanMapper.selectByUserSpaceStatus(anyString(), anyLong(), anyString(), anyString())).thenReturn(null);
        when(studyPlanMapper.selectLatestByUserSpace(anyString(), anyLong(), anyString())).thenReturn(null);
        when(knowledgePointMapper.selectBySpaceOwner(anyLong(), anyString())).thenReturn(List.of());

        AiLearningState state = service.assemble("u1", 1L);
        assertEquals(8, state.weakKnowledgePoints().size());
        assertTrue(state.weakKnowledgePoints().stream().noneMatch(w -> w.knowledgePointId() == 999L),
                "strong mastery must be excluded");
    }

    @Test
    void mapperFailureFailsSoftToEmptyState() {
        when(masteryMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        AiLearningState state = service.assemble("u1", 1L);
        assertTrue(state.weakKnowledgePoints().isEmpty());
        assertFalse(state.hasActivePlan());
    }

    @Test
    void nextActionsIncludePendingAndSkipCompleted() {
        when(masteryMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString())).thenReturn(List.of());
        when(wrongQuestionMapper.selectBySpaceOwnerUser(anyLong(), anyString(), anyString())).thenReturn(List.of());
        when(examDiagnosisItemMapper.selectLatestKpItemsByUserSpace(anyString(), anyLong())).thenReturn(List.of());
        StudyPlan plan = new StudyPlan();
        plan.setId(10L);
        plan.setStatus("ACTIVE");
        when(studyPlanMapper.selectByUserSpaceStatus(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(plan);
        StudyTask pending = new StudyTask();
        pending.setId(1L);
        pending.setTaskType("REVIEW_KP");
        pending.setTargetType("KNOWLEDGE_POINT");
        pending.setTargetId(5L);
        pending.setTitle("复习范式");
        pending.setStatus("PENDING");
        StudyTask done = new StudyTask();
        done.setId(2L);
        done.setStatus("COMPLETED");
        when(studyTaskMapper.selectByPlanId(10L)).thenReturn(List.of(pending, done));

        AiLearningState state = service.assemble("u1", 1L);
        assertTrue(state.hasActivePlan());
        assertEquals(1, state.nextActions().size());
        assertEquals(1L, state.nextActions().get(0).taskId());
        assertEquals("复习范式", state.nextActions().get(0).title());
    }
}

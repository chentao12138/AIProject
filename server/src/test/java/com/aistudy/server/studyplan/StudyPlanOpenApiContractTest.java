package com.aistudy.server.studyplan;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUSINESS-016 — OpenAPI contract test for the StudyPlan API
 * ({@code test} profile + {@code @MockitoBean} mappers).
 *
 * <p>Verifies: singular study-plan paths (generate / get / complete);
 * typed request + typed plan/task responses; the generate request
 * carries NO mastery/score fields (no client mastery mutation);
 * bearerAuth declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudyPlanOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;

    @MockitoBean
    private SourceMapper sourceMapper;

    @MockitoBean
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    @MockitoBean
    private KnowledgePointMapper knowledgePointMapper;

    @MockitoBean
    private SourceAssetMapper sourceAssetMapper;

    @MockitoBean
    private IngestionJobMapper ingestionJobMapper;

    @MockitoBean
    private SourcePageMapper sourcePageMapper;

    @MockitoBean
    private ContentBlockMapper contentBlockMapper;

    @MockitoBean
    private KnowledgePointSourceMapper knowledgePointSourceMapper;

    @MockitoBean
    private QuestionMapper questionMapper;

    @MockitoBean
    private QuestionOptionMapper questionOptionMapper;

    @MockitoBean
    private QuestionKnowledgePointMapper questionKnowledgePointMapper;

    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeSessionMapper practiceSessionMapper;

    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper practiceSessionQuestionMapper;

    @MockitoBean
    private com.aistudy.server.practice.mapper.PracticeAnswerMapper practiceAnswerMapper;

    @MockitoBean
    private com.aistudy.server.wrong.mapper.WrongQuestionMapper wrongQuestionMapper;

    @MockitoBean
    private com.aistudy.server.wrong.mapper.ReviewTaskMapper reviewTaskMapper;

    @MockitoBean
    private com.aistudy.server.wrong.mapper.ReviewRecordMapper reviewRecordMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamMapper examMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamPaperMapper examPaperMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamQuestionMapper examQuestionMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamAttemptMapper examAttemptMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamAnswerMapper examAnswerMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamResultMapper examResultMapper;

    @MockitoBean
    private com.aistudy.server.mastery.mapper.MasteryMapper masteryMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamDiagnosisMapper examDiagnosisMapper;

    @MockitoBean
    private com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper examDiagnosisItemMapper;

    @MockitoBean
    private com.aistudy.server.studyplan.mapper.StudyPlanMapper studyPlanMapper;

    @MockitoBean
    private com.aistudy.server.studyplan.mapper.StudyTaskMapper studyTaskMapper;
    // BUSINESS-017: keep the full-context test profile bootable without a
    // database — the real UserAccountMapper runs under flyway-it tests only.
    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountRoleMapper userAccountRoleMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.RefreshSessionMapper refreshSessionMapper;

    @MockitoBean
    private com.aistudy.server.search.mapper.SearchMapper searchMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;

    private static final String GENERATE = "/api/v1/spaces/{spaceId}/study-plan/generate";
    private static final String PLAN = "/api/v1/spaces/{spaceId}/study-plan";
    private static final String COMPLETE =
            "/api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/complete";

    /** (1) all three study-plan paths exposed (singular current-plan API). */
    @Test
    void studyPlanPathsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + GENERATE + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + PLAN + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + COMPLETE + "'].post").exists());
    }

    /** (2) generate request is the typed DTO with bounded limit. */
    @Test
    void generateRequestTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + GENERATE + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/GenerateStudyPlanRequest"))
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.startDate.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.endDate.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.dailyItemLimit.type")
                        .value("integer"));
    }

    /** (3) generate 201 → typed plan view with typed tasks. */
    @Test
    void generateReturnsTypedPlan() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + GENERATE + "'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/StudyPlanView"))
                .andExpect(jsonPath("$.components.schemas.StudyPlanView.properties.tasks.items.$ref")
                        .value("#/components/schemas/StudyTaskView"))
                .andExpect(jsonPath("$.components.schemas.StudyTaskView.properties.taskType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.StudyTaskView.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.StudyTaskView.properties.dueAt.type")
                        .value("string"));
    }

    /** (4) GET plan → typed plan view; complete → typed task view. */
    @Test
    void getAndCompleteTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PLAN + "'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/StudyPlanView"))
                .andExpect(jsonPath("$.paths['" + COMPLETE + "'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/StudyTaskView"));
    }

    /** (5) no client mastery mutation: generate request has NO mastery/score fields. */
    @Test
    void noClientMasteryMutation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.masteryScore")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.GenerateStudyPlanRequest.properties.confidence")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.StudyTaskView.properties.masteryScore")
                        .doesNotExist());
    }

    /** (6) bearerAuth declared on all study-plan paths. */
    @Test
    void studyPlanPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + GENERATE + "'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + PLAN + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + COMPLETE + "'].post.security[0].bearerAuth").exists());
    }
}

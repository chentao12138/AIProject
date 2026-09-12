package com.aistudy.server.exam;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.practice.mapper.PracticeAnswerMapper;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
import com.aistudy.server.wrong.mapper.ReviewRecordMapper;
import com.aistudy.server.wrong.mapper.ReviewTaskMapper;
import com.aistudy.server.wrong.mapper.WrongQuestionMapper;
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
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamAnswerMapper;
import com.aistudy.server.exam.mapper.ExamResultMapper;

/**
 * BUSINESS-012 — OpenAPI contract test for the Exam definition API.
 * Extended by BUSINESS-013 (exam attempt paths).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamOpenApiContractTest {

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
    private PracticeSessionMapper practiceSessionMapper;

    @MockitoBean
    private PracticeSessionQuestionMapper practiceSessionQuestionMapper;

    @MockitoBean
    private PracticeAnswerMapper practiceAnswerMapper;

    @MockitoBean
    private WrongQuestionMapper wrongQuestionMapper;

    @MockitoBean
    private ReviewTaskMapper reviewTaskMapper;

    @MockitoBean
    private ReviewRecordMapper reviewRecordMapper;

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

    private static final String EXAMS = "/api/v1/spaces/{spaceId}/exams";

    /** (1) exam definition paths exposed. */
    @Test
    void examPathsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + EXAMS + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + EXAMS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + EXAMS + "/{examId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + EXAMS + "/{examId}/publish'].post").exists());
    }

    /** (2) create POST has the typed request with scored questions. */
    @Test
    void createHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + EXAMS + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CreateExamRequest"))
                .andExpect(jsonPath("$.components.schemas.CreateExamRequest.properties.questions.items.$ref")
                        .value("#/components/schemas/ExamQuestionInput"))
                .andExpect(jsonPath("$.components.schemas.ExamQuestionInput.properties.score.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.CreateExamRequest.properties.durationMinutes.type")
                        .value("integer"));
    }

    /** (3) response typed; composition views have NO answer fields. */
    @Test
    void responseTypedAndSafe() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + EXAMS + "'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamResponse"))
                .andExpect(jsonPath("$.components.schemas.ExamResponse.properties.questions.items.$ref")
                        .value("#/components/schemas/ExamQuestionView"))
                .andExpect(jsonPath("$.components.schemas.ExamQuestionView.properties.options.items.$ref")
                        .value("#/components/schemas/OptionView"))
                .andExpect(jsonPath("$.components.schemas.OptionView.properties.isCorrect")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ExamQuestionView.properties.answerData")
                        .doesNotExist());
    }

    /** (4) publish declared. */
    @Test
    void publishDeclared() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + EXAMS + "/{examId}/publish'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamResponse"));
    }

    // ==================== BUSINESS-013 exam attempt ====================

    private static final String ATTEMPTS = "/api/v1/spaces/{spaceId}/exam-attempts";

    /** (5) attempt paths exposed (create under exam, others under exam-attempts). */
    @Test
    void attemptPathsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + EXAMS + "/{examId}/sessions'].post").exists())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/start'].post").exists())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/answers'].post").exists())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/submit'].post").exists())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/result'].get").exists());
    }

    /** (6) answer POST: typed request; response leak-free (no isCorrect/score). */
    @Test
    void answerPostLeakFreeContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/answers'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamAnswerRequest"))
                .andExpect(jsonPath("$.components.schemas.ExamAnswerRequest.properties.answer.$ref")
                        .value("#/components/schemas/AnswerPayloadView"))
                // the payload schema itself carries exactly one field per
                // question type, and the pre-submit answer response must never
                // leak correctness / score / correctAnswer
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.selectedOptionKeys.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.booleanAnswer.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.textAnswer.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/answers'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamAnswerView"))
                .andExpect(jsonPath("$.components.schemas.ExamAnswerView.properties.isCorrect")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ExamAnswerView.properties.score")
                        .doesNotExist());
    }

    /** (7) submit + result: typed result view revealing correctness. */
    @Test
    void submitAndResultTypedContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ATTEMPTS + "/{attemptId}/submit'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamResultView"))
                .andExpect(jsonPath("$.components.schemas.ExamResultView.properties.items.items.$ref")
                        .value("#/components/schemas/ExamResultItemView"))
                .andExpect(jsonPath("$.components.schemas.ExamResultItemView.properties.isCorrect.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.ExamResultItemView.properties.correctAnswer.type")
                        .value("string"));
    }

    // ==================== BUSINESS-015 exam diagnosis ====================

    private static final String DIAGNOSIS =
            "/api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/diagnosis";

    /** (8) diagnosis path exposed as a GET. */
    @Test
    void diagnosisPathExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + DIAGNOSIS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + DIAGNOSIS + "'].post").doesNotExist());
    }

    /** (9) diagnosis response is the typed view with typed items. */
    @Test
    void diagnosisTypedContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + DIAGNOSIS + "'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ExamDiagnosisView"))
                .andExpect(jsonPath("$.components.schemas.ExamDiagnosisView.properties.items.items.$ref")
                        .value("#/components/schemas/ItemView"))
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.dimensionType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.score.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.maxScore.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.accuracy.type")
                        .value("number"))
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.evidenceCount.type")
                        .value("integer"))
                // no answer leakage in the typed contract
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.answerData")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ItemView.properties.isCorrect")
                        .doesNotExist());
    }

    /** (10) bearerAuth declared on the diagnosis path. */
    @Test
    void diagnosisDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + DIAGNOSIS + "'].get.security[0].bearerAuth").exists());
    }
}

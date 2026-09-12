package com.aistudy.server.question;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
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
import com.aistudy.server.practice.mapper.PracticeAnswerMapper;
import com.aistudy.server.wrong.mapper.WrongQuestionMapper;
import com.aistudy.server.wrong.mapper.ReviewTaskMapper;
import com.aistudy.server.wrong.mapper.ReviewRecordMapper;
import com.aistudy.server.exam.mapper.ExamMapper;
import com.aistudy.server.exam.mapper.ExamPaperMapper;
import com.aistudy.server.exam.mapper.ExamQuestionMapper;
import com.aistudy.server.exam.mapper.ExamAttemptMapper;
import com.aistudy.server.exam.mapper.ExamAnswerMapper;
import com.aistudy.server.exam.mapper.ExamResultMapper;

/**
 * BUSINESS-008 — OpenAPI contract test for the Question bank API
 * ({@code test} profile + {@code @MockitoBean} mappers).
 *
 * <p>This class is extended by BUSINESS-009/010/011 (practice paths)
 * in later phases. Verifies: question paths exposed; create POST has
 * a typed {@code CreateQuestionRequest} body; responses are typed
 * {@code QuestionAuthoringResponse} arrays/objects with the authoring
 * answer fields; publish POST exists; bearerAuth declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionPracticeOpenApiContractTest {

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

    private static final String QUESTIONS = "/api/v1/spaces/{spaceId}/questions";
    private static final String SESSIONS = "/api/v1/spaces/{spaceId}/practice-sessions";

    /** (1) all question paths exposed. */
    @Test
    void questionPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "/{questionId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "/{questionId}/publish'].post").exists());
    }

    /** (2) create POST body is the typed request with required fields. */
    @Test
    void createPostHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CreateQuestionRequest"))
                .andExpect(jsonPath("$.components.schemas.CreateQuestionRequest.required[0]").value("questionType"))
                .andExpect(jsonPath("$.components.schemas.CreateQuestionRequest.required[1]").value("stem"))
                .andExpect(jsonPath("$.components.schemas.CreateQuestionRequest.properties.options.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.CreateQuestionRequest.properties.correctOptionKey.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateQuestionRequest.properties.correctBoolean.type")
                        .value("boolean"));
    }

    /** (3) create 201 → typed authoring response incl. answer view. */
    @Test
    void createReturnsTypedAuthoringResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/QuestionAuthoringResponse"))
                .andExpect(jsonPath("$.components.schemas.QuestionAuthoringResponse.properties.answer.$ref")
                        .value("#/components/schemas/QuestionAnswerView"))
                .andExpect(jsonPath("$.components.schemas.QuestionAnswerView.properties.correctOptionKey.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.QuestionAnswerView.properties.correctBoolean.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.QuestionAuthoringResponse.properties.options.items.$ref")
                        .value("#/components/schemas/QuestionOptionResponse"))
                .andExpect(jsonPath("$.components.schemas.QuestionOptionResponse.properties.isCorrect.type")
                        .value("boolean"));
    }

    /** (4) list GET → array of the typed authoring response. */
    @Test
    void listReturnsArrayOfAuthoringResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/QuestionAuthoringResponse"));
    }

    /** (5) list filters declared as query params. */
    @Test
    void listDeclaresQueryFilters() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].get.parameters[*].name", org.hamcrest.Matchers.hasItems(
                        "status", "questionType", "knowledgePointId")));
    }

    /** (6) publish POST declared. */
    @Test
    void publishPostDeclared() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "/{questionId}/publish'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/QuestionAuthoringResponse"));
    }

    /** (7) security requirement declared on question paths. */
    @Test
    void questionPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + QUESTIONS + "'].get.security[0].bearerAuth").exists());
    }

    // ==================== BUSINESS-009 practice session ====================

    /** (8) practice session paths exposed. */
    @Test
    void practiceSessionPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/start'].post").exists());
    }

    /** (9) create POST body is the typed request with the two selection modes. */
    @Test
    void practiceCreateHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CreatePracticeSessionRequest"))
                .andExpect(jsonPath("$.components.schemas.CreatePracticeSessionRequest.properties.questionIds.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.CreatePracticeSessionRequest.properties.knowledgePointId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.CreatePracticeSessionRequest.properties.count.type")
                        .value("integer"));
    }

    /** (10) detail response is typed and its question view has NO answer fields. */
    @Test
    void practiceDetailHasSafeQuestionView() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PracticeSessionDetail"))
                .andExpect(jsonPath("$.components.schemas.PracticeSessionDetail.properties.questions.items.$ref")
                        .value("#/components/schemas/PracticeQuestionView"))
                .andExpect(jsonPath("$.components.schemas.PracticeQuestionView.properties.stem.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.PracticeQuestionView.properties.options.items.$ref")
                        .value("#/components/schemas/OptionView"))
                .andExpect(jsonPath("$.components.schemas.OptionView.properties.optionKey.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.OptionView.properties.isCorrect")
                        .doesNotExist());
    }

    /** (11) start POST returns the typed summary. */
    @Test
    void practiceStartReturnsTypedSummary() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/start'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PracticeSessionSummary"));
    }

    // ==================== BUSINESS-010 answers / finish ====================

    /** (12) answer + finish paths exposed. */
    @Test
    void practiceAnswerAndFinishPathsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/answers'].post").exists())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/finish'].post").exists());
    }

    /** (13) answer POST has typed body + typed feedback response. */
    @Test
    void practiceAnswerHasTypedContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/answers'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/PracticeAnswerRequest"))
                .andExpect(jsonPath("$.components.schemas.PracticeAnswerRequest.properties.answer.$ref")
                        .value("#/components/schemas/AnswerPayloadView"))
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.selectedOptionKeys.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.booleanAnswer.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.AnswerPayloadView.properties.textAnswer.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/answers'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PracticeAnswerView"))
                .andExpect(jsonPath("$.components.schemas.PracticeAnswerView.properties.isCorrect.type")
                        .value("boolean"));
    }

    /** (14) finish POST returns the typed submit summary. */
    @Test
    void practiceFinishReturnsTypedSubmitSummary() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + SESSIONS + "/{sessionId}/finish'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PracticeSubmitView"))
                .andExpect(jsonPath("$.components.schemas.PracticeSubmitView.properties.correctCount.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.PracticeSubmitView.properties.maxScore.type")
                        .value("integer"));
    }

    // ==================== BUSINESS-011 wrong / review ====================

    private static final String WRONG = "/api/v1/spaces/{spaceId}/wrong-questions";
    private static final String REVIEW_TASKS = "/api/v1/spaces/{spaceId}/review-tasks";

    /** (15) wrong-question + review-task paths exposed. */
    @Test
    void wrongAndReviewPathsExposed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + WRONG + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + REVIEW_TASKS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + REVIEW_TASKS + "/{taskId}/complete'].post").exists());
    }

    /** (16) review completion has typed request + response. */
    @Test
    void reviewCompletionTypedContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + REVIEW_TASKS + "/{taskId}/complete'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CompleteReviewTaskRequest"))
                .andExpect(jsonPath("$.components.schemas.CompleteReviewTaskRequest.properties.result.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['" + REVIEW_TASKS + "/{taskId}/complete'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/CompleteReviewTaskView"))
                .andExpect(jsonPath("$.components.schemas.CompleteReviewTaskView.properties.wrongQuestionStatus.type")
                        .value("string"));
    }

    /** (17) wrong-question list returns typed items. */
    @Test
    void wrongQuestionListTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + WRONG + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + WRONG + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/WrongQuestionView"))
                .andExpect(jsonPath("$.components.schemas.WrongQuestionView.properties.wrongCount.type")
                        .value("integer"));
    }
}

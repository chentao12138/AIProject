package com.aistudy.server.ingestion;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.source.mapper.SourceMapper;
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
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
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
 * BUSINESS-005 — OpenAPI contract test for the IngestionJob API
 * ({@code test} profile + {@code @MockitoBean} mappers so the context
 * boots without MySQL).
 *
 * <p>Verifies: the four job paths are exposed; create POST has a
 * typed {@code CreateIngestionJobRequest} body (assetId required) and
 * returns {@code IngestionJobResponse} via {@code $ref}; list is
 * {@code array + items.$ref}; retry POST returns the same typed
 * schema; the response schema exposes safe error fields
 * (errorCode/errorMessage) but NO stack trace field; bearerAuth is
 * declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionJobOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    /** SPIKE-004 pattern: keep the test profile context bootable. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** BUSINESS-001: keep the test profile context bootable. */
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;

    /** BUSINESS-002: keep the test profile context bootable. */
    @MockitoBean
    private SourceMapper sourceMapper;

    /** BUSINESS-003: keep the test profile context bootable. */
    @MockitoBean
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    /** BUSINESS-003: keep the test profile context bootable. */
    @MockitoBean
    private KnowledgePointMapper knowledgePointMapper;

    /** BUSINESS-004: keep the test profile context bootable. */
    @MockitoBean
    private SourceAssetMapper sourceAssetMapper;

    /** BUSINESS-005: keep the test profile context bootable. */
    @MockitoBean
    private IngestionJobMapper ingestionJobMapper;

    /** BUSINESS-006: keep the test profile context bootable. */
    @MockitoBean
    private SourcePageMapper sourcePageMapper;

    /** BUSINESS-006: keep the test profile context bootable. */
    @MockitoBean
    private ContentBlockMapper contentBlockMapper;

    /** BUSINESS-007: keep the test profile context bootable. */
    @MockitoBean
    private KnowledgePointSourceMapper knowledgePointSourceMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionMapper questionMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionOptionMapper questionOptionMapper;
    @MockitoBean
    private com.aistudy.server.question.mapper.QuestionKnowledgePointMapper questionKnowledgePointMapper;
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

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountRoleMapper userAccountRoleMapper;

    @MockitoBean
    private com.aistudy.server.auth.mapper.RefreshSessionMapper refreshSessionMapper;

    @MockitoBean
    private com.aistudy.server.search.mapper.SearchMapper searchMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageReferenceMapper aiMessageReferenceMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSettingsMapper aiProviderSettingsMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSecretMapper aiProviderSecretMapper;


    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;


    private static final String CREATE_LIST =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs";
    private static final String GET_ONE =
            "/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}";
    private static final String RETRY =
            "/api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}/retry";

    /** (1) the four IngestionJob paths must be exposed. */

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void ingestionJobPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + GET_ONE + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + RETRY + "'].post").exists());
    }

    /** (2) create POST body is the typed CreateIngestionJobRequest with required assetId. */
    @Test
    void createPostHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/CreateIngestionJobRequest"))
                .andExpect(jsonPath("$.components.schemas.CreateIngestionJobRequest.required[0]")
                        .value("assetId"))
                .andExpect(jsonPath("$.components.schemas.CreateIngestionJobRequest.properties.assetId.type")
                        .value("integer"));
    }

    /** (3) create POST 201 → IngestionJobResponse $ref. */
    @Test
    void createPostReturns201WithIngestionJobResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/IngestionJobResponse"));
    }

    /** (4) list GET → array + items.$ref. */
    @Test
    void listGetReturnsArrayOfIngestionJobResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/IngestionJobResponse"));
    }

    /** (5) detail GET → IngestionJobResponse $ref. */
    @Test
    void detailGetReturnsIngestionJobResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + GET_ONE + "'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/IngestionJobResponse"));
    }

    /** (6) retry POST 200 → IngestionJobResponse $ref. */
    @Test
    void retryPostReturnsIngestionJobResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + RETRY + "'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/IngestionJobResponse"));
    }

    /** (7) response schema carries safe error fields, NO stack trace. */
    @Test
    void responseSchemaHasSafeErrorFieldsOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.IngestionJobResponse.properties.errorCode.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.IngestionJobResponse.properties.errorMessage.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.IngestionJobResponse.properties.stackTrace")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.IngestionJobResponse.properties.errorDetails")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.IngestionJobResponse.properties.storageKey")
                        .doesNotExist());
    }

    /** (8) all four paths declare bearerAuth. */
    @Test
    void allPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].post.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + CREATE_LIST + "'].get.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + GET_ONE + "'].get.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + RETRY + "'].post.security[0].bearerAuth")
                        .exists());
    }
}

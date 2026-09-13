package com.aistudy.server.source;

import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
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
 * BUSINESS-004 — OpenAPI contract test for the SourceAsset upload API
 * ({@code test} profile + {@code @MockitoBean} mappers so the context
 * boots without MySQL).
 *
 * <p>Verifies: the three asset paths are exposed; the POST consumes
 * {@code multipart/form-data} with a {@code file} part of
 * {@code type=string, format=binary}; 201 returns
 * {@code SourceAssetResponse} via {@code $ref}; list is
 * {@code array + items.$ref}; the response schema carries NO
 * {@code storageKey} / {@code originalRelativePath}; the request
 * schema carries NO server-controlled fields; bearerAuth is declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourceAssetOpenApiContractTest {

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

    /** BUSINESS-007: keep the test profile context bootable. */
    @MockitoBean
    private KnowledgePointSourceMapper knowledgePointSourceMapper;

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


    private static final String ASSETS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets";
    private static final String ASSET_ONE =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/assets/{assetId}";

    /** (1) the three SourceAsset paths must be exposed. */

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void sourceAssetPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + ASSET_ONE + "'].get").exists());
    }

    /** (2) upload POST consumes multipart/form-data with a binary "file" part. */
    @Test
    void uploadPostIsMultipartWithBinaryFilePart() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data']")
                        .exists())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.file.type")
                        .value("string"))
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.file.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.storageKey")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.sha256")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.sizeBytes")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.assetRole")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.requestBody.content['multipart/form-data'].schema.properties.ownerSubject")
                        .doesNotExist());
    }

    /** (3) upload 201 → SourceAssetResponse $ref + application/json. */
    @Test
    void uploadPostReturns201WithSourceAssetResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/SourceAssetResponse"));
    }

    /** (4) list GET → array + items.$ref. */
    @Test
    void listGetReturnsArrayOfSourceAssetResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/SourceAssetResponse"));
    }

    /** (5) detail GET → SourceAssetResponse $ref. */
    @Test
    void detailGetReturnsSourceAssetResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ASSET_ONE + "'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/SourceAssetResponse"));
    }

    /** (6) SourceAssetResponse typed schema without storageKey / physical path. */
    @Test
    void sourceAssetResponseSchemaIsTypedAndHidesStorageKey() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.type").value("object"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.id.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.spaceId.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.sourceId.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.assetRole.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.originalName.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.mimeType.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.sizeBytes.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.sha256.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.createdAt.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.storageKey").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.SourceAssetResponse.properties.originalRelativePath").doesNotExist());
    }

    /** (7) all three endpoints declare bearerAuth. */
    @Test
    void sourceAssetEndpointsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + ASSETS + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + ASSET_ONE + "'].get.security[0].bearerAuth").exists());
    }
}

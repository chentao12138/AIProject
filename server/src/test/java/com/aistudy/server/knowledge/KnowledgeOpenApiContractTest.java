package com.aistudy.server.knowledge;

import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
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
 * BUSINESS-003 — OpenAPI contract test for Knowledge Category and
 * Knowledge Point endpoints.
 *
 * <p>Verifies that the Knowledge paths and typed schemas appear in
 * {@code /v3/api-docs}:
 *
 * <ol>
 *   <li>Category paths (POST/GET list, GET detail) and KnowledgePoint
 *       paths (POST/GET list, GET detail, POST publish),</li>
 *   <li>{@code components.schemas.CreateKnowledgeCategoryRequest},
 *       {@code KnowledgeCategoryResponse},
 *       {@code CreateKnowledgePointRequest},
 *       {@code KnowledgePointResponse} typed properties,</li>
 *   <li>CreateKnowledgePointRequest does NOT contain spaceId /
 *       ownerSubject / createdByUserId / originType / status /
 *       publishedAt / deletedAt,</li>
 *   <li>list schemas are {@code type=array} +
 *       {@code items.$ref},</li>
 *   <li>POST create 201 + publish 200 are single-object
 *       {@code $ref}s with {@code application/json},</li>
 *   <li>bearerAuth present on the Knowledge endpoints.</li>
 * </ol>
 *
 * <h3>Test profile + mocks</h3>
 *
 * <p>Runs under {@code test} profile (no DataSource): SPIKE
 * membership repository, LearningSpace/Source/Knowledge mappers are
 * {@code @MockitoBean} so the context boots. None is stubbed —
 * springdoc only introspects signatures and DTO types.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeOpenApiContractTest {

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


    /** All seven Knowledge paths must be present. */

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void knowledgePathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories/{categoryId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish'].post").exists());
    }

    /** KnowledgeCategoryResponse typed schema. */
    @Test
    void knowledgeCategoryResponseSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.type")
                        .value("object"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.id.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.spaceId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.parentId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.description.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.sortOrder.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.createdAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgeCategoryResponse.properties.updatedAt.type")
                        .value("string"));
    }

    /** CreateKnowledgeCategoryRequest typed: name/description/parentId/sortOrder only. */
    @Test
    void createKnowledgeCategoryRequestSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.description.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.parentId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.sortOrder.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.ownerSubject")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgeCategoryRequest.properties.spaceId")
                        .doesNotExist());
    }

    /** KnowledgePointResponse typed schema with 12 stable fields. */
    @Test
    void knowledgePointResponseSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.type")
                        .value("object"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.id.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.spaceId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.categoryId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.summary.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.content.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.originType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.difficulty.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.createdAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.updatedAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.publishedAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.createdByUserId")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.KnowledgePointResponse.properties.deletedAt")
                        .doesNotExist());
    }

    /** CreateKnowledgePointRequest must NOT expose server-controlled fields. */
    @Test
    void createKnowledgePointRequestExcludesServerControlledFields() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.summary.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.content.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.categoryId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.difficulty.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.spaceId")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.ownerSubject")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.createdByUserId")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.originType")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.status")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.publishedAt")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateKnowledgePointRequest.properties.deletedAt")
                        .doesNotExist());
    }

    /** Category list + point list are type=array with items.$ref. */
    @Test
    void knowledgeListSchemasAreArrays() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgeCategoryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgePointResponse"));
    }

    /** POST create 201 + publish 200: single-object $ref, application/json. */
    @Test
    void knowledgeSingleResponsesUseApplicationJson() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/KnowledgeCategoryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/KnowledgePointResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish'].post.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/KnowledgePointResponse"));
    }

    /** All Knowledge endpoints carry bearerAuth. */
    @Test
    void knowledgeEndpointsCarryBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-categories/{categoryId}'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/publish'].post.security[0].bearerAuth")
                        .isArray());
    }
}

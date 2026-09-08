package com.aistudy.server.source;

import com.aistudy.server.space.mapper.LearningSpaceMapper;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.source.content.mapper.ContentBlockMapper;
import com.aistudy.server.source.page.mapper.SourcePageMapper;
import com.aistudy.server.source.mapper.SourceMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
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
 * BUSINESS-002 — OpenAPI contract test for the production Source
 * endpoints.
 *
 * <p>Verifies that the Source endpoints appear in {@code /v3/api-docs}
 * with typed schemas:
 *
 * <ol>
 *   <li>paths: {@code POST/GET /api/v1/spaces/{spaceId}/sources},
 *       {@code GET /api/v1/spaces/{spaceId}/sources/{sourceId}},</li>
 *   <li>{@code components.schemas.SourceResponse} typed properties,</li>
 *   <li>{@code components.schemas.CreateSourceRequest} typed
 *       properties (title, sourceType),</li>
 *   <li>GET list schema: {@code type=array} +
 *       {@code items.$ref = #/components/schemas/SourceResponse},</li>
 *   <li>POST 201: {@code application/json} + {@code $ref
 *       SourceResponse},</li>
 *   <li>GET detail 200: {@code application/json} + {@code $ref
 *       SourceResponse},</li>
 *   <li>bearerAuth on the Source endpoints.</li>
 * </ol>
 *
 * <h3>Test profile + mocks</h3>
 *
 * <p>Runs under {@code test} profile (no DataSource): the SPIKE
 * membership repository, the LearningSpace mapper, and the Source
 * mapper are {@code @MockitoBean} so the context boots. None is
 * stubbed — springdoc only introspects signatures and DTO types.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourceOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    /** SPIKE-004 pattern: keep the test profile context bootable. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** BUSINESS-001 pattern: keep the test profile context bootable. */
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;

    /** BUSINESS-002: keep the test profile context bootable. */
    @MockitoBean
    private SourceMapper sourceMapper;

    /**
     * BUSINESS-003: mock the Knowledge mappers so this
     * full-context test keeps running without MyBatis-Plus /
     * DataSource under this profile. Not stubbed — this test
     * never touches Knowledge persistence.
     */
    @MockitoBean
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    /**
     * BUSINESS-003: mock the KnowledgePoint mapper (see above).
     */
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

    /** The three Source paths must be present in the contract. */
    @Test
    void sourcePathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources/{sourceId}'].get").exists());
    }

    /** SourceResponse must be a typed schema with stable properties. */
    @Test
    void sourceResponseSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.type")
                        .value("object"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.id.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.spaceId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.sourceType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.createdAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.updatedAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourceResponse.properties.createdByUserId")
                        .doesNotExist());
    }

    /** CreateSourceRequest must be typed with title + sourceType only. */
    @Test
    void createSourceRequestSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.CreateSourceRequest.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateSourceRequest.properties.sourceType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateSourceRequest.properties.ownerSubject")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateSourceRequest.properties.spaceId")
                        .doesNotExist());
    }

    /** GET list: type=array + items.$ref SourceResponse. */
    @Test
    void sourceListSchemaIsArrayOfSourceResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/SourceResponse"));
    }

    /** POST 201 + GET detail 200: single SourceResponse $ref. */
    @Test
    void sourceSingleResponseUsesApplicationJson() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/SourceResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources/{sourceId}'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/SourceResponse"));
    }

    /** Source endpoints carry bearerAuth. */
    @Test
    void sourceEndpointsCarryBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}/sources/{sourceId}'].get.security[0].bearerAuth")
                        .isArray());
    }
}

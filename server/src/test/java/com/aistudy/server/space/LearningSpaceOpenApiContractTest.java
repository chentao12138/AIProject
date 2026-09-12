package com.aistudy.server.space;

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
 * BUSINESS-001 — OpenAPI contract test for the production
 * LearningSpace endpoints.
 *
 * <p>Verifies that the three business endpoints
 * ({@code POST/GET /api/v1/spaces}, {@code GET /api/v1/spaces/{spaceId}})
 * appear in {@code /v3/api-docs} with:
 *
 * <ol>
 *   <li>explicit paths (POST + GET on {@code /api/v1/spaces},
 *       GET on {@code /api/v1/spaces/{spaceId}}),</li>
 *   <li>typed response schema
 *       {@code components.schemas.LearningSpaceResponse} with the
 *       six stable properties (id, name, description, status,
 *       createdAt, updatedAt),</li>
 *   <li>typed request schema
 *       {@code components.schemas.CreateLearningSpaceRequest}
 *       (name, description),</li>
 *   <li>bearerAuth security requirement on the business endpoints,</li>
 *   <li>{@code application/json} as the response media type
 *       (NOT {@code *&#47;*}).</li>
 * </ol>
 *
 * <p>This is the contract that the deferred TypeScript client
 * generation will consume once the business API stabilizes
 * (docs/current-task.md: "在真实 Contract 出现后再落地 TypeScript
 * shared client/types").
 *
 * <h3>Test profile + mocks</h3>
 *
 * <p>Runs under the {@code test} profile (no DataSource). Both the
 * SPIKE membership repository and the production LearningSpace
 * mapper are {@code @MockitoBean} so the Spring context boots
 * without MyBatis-Plus / JDBC. Neither is stubbed — springdoc's
 * contract generation only introspects controller signatures and
 * DTO types; it never calls the mappers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LearningSpaceOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    /** SPIKE-004 pattern: keep the test profile context bootable. */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /** BUSINESS-001: keep the test profile context bootable. */
    @MockitoBean
    private LearningSpaceMapper learningSpaceMapper;
    /**
     * BUSINESS-002: mock the production Source mapper so this
     * full-context test keeps running without MyBatis-Plus /
     * DataSource under this profile. Not stubbed — this test never
     * touches Source persistence. The real SourceMapper is exercised
     * by SourceVerticalSliceIntegrationTest (flyway-it profile).
     */
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



    /**
     * The three business paths must be present in the contract:
     * POST + GET on {@code /api/v1/spaces}, GET on
     * {@code /api/v1/spaces/{spaceId}}.
     */

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void learningSpacePathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}'].get").exists());
    }

    /**
     * The response schema is a TYPED record schema, not a generic
     * {@code additionalProperties} map: six stable properties with
     * explicit types.
     */
    @Test
    void learningSpaceResponseSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.type")
                        .value("object"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.id.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.description.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.status.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.createdAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.updatedAt.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.LearningSpaceResponse.properties.ownerSubject")
                        .doesNotExist());
    }

    /**
     * The request schema is typed too: {@code name} (string) and
     * {@code description} (string), no owner field.
     */
    @Test
    void createLearningSpaceRequestSchemaIsTyped() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.components.schemas.CreateLearningSpaceRequest.properties.name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateLearningSpaceRequest.properties.description.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateLearningSpaceRequest.properties.ownerSubject")
                        .doesNotExist());
    }

    /**
     * The business endpoints carry bearerAuth — class-level
     * {@code @SecurityRequirement(name = "bearerAuth")} on
     * {@code LearningSpaceController} must surface per-operation.
     */
    @Test
    void learningSpaceEndpointsCarryBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].get.security[0].bearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/spaces/{spaceId}'].get.security[0].bearerAuth")
                        .isArray());
    }

    /**
     * The response content media type must be {@code application/json}
     * (explicit {@code produces} on the controller), not the
     * {@code *&#47;*} default.
     *
     * <p>The GET list schema is {@code type: array} with
     * {@code items.$ref = #/components/schemas/LearningSpaceResponse}
     * because the controller returns {@code List<LearningSpaceResponse>}.
     * The POST 201 schema is a single object {@code $ref} because the
     * controller returns {@code LearningSpaceResponse}. Both shapes are
     * asserted separately — the test documents the actual contract
     * instead of forcing the list into a single-object {@code $ref}.
     */
    @Test
    void learningSpaceResponsesUseApplicationJson() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // GET /api/v1/spaces → List<LearningSpaceResponse>:
                // schema is type=array with items.$ref.
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/LearningSpaceResponse"))
                // POST /api/v1/spaces → single LearningSpaceResponse:
                // schema is a direct $ref.
                .andExpect(jsonPath("$.paths['/api/v1/spaces'].post.responses['201'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/LearningSpaceResponse"));
    }
}

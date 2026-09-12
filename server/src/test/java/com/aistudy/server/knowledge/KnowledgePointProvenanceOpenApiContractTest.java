package com.aistudy.server.knowledge;

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
 * BUSINESS-007 — OpenAPI contract test for the KnowledgePointSource
 * provenance API ({@code test} profile + {@code @MockitoBean} mappers
 * so the context boots without MySQL).
 *
 * <p>Verifies: both provenance paths are exposed; POST has a typed
 * {@code LinkKnowledgePointSourcesRequest} body (contentBlockIds
 * array, required) and returns {@code array + items.$ref} of
 * {@code KnowledgePointSourceResponse}; GET returns the same typed
 * list; the response schema exposes citation identity only (no
 * normalizedText / storageKey / block content); bearerAuth is
 * declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgePointProvenanceOpenApiContractTest {

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
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;


    private static final String LINK_BASE =
            "/api/v1/spaces/{spaceId}/knowledge-points/{knowledgePointId}/sources";

    /** (1) both provenance paths are exposed. */

    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void provenancePathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post").exists())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get").exists());
    }

    /** (2) link POST body is the typed request with required contentBlockIds array. */
    @Test
    void linkPostHasTypedRequestBody() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/LinkKnowledgePointSourcesRequest"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.required[0]")
                        .value("contentBlockIds"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.properties.contentBlockIds.type")
                        .value("array"))
                .andExpect(jsonPath("$.components.schemas.LinkKnowledgePointSourcesRequest.properties.contentBlockIds.items.type")
                        .value("integer"));
    }

    /** (3) link POST 201 → array + items.$ref of KnowledgePointSourceResponse. */
    @Test
    void linkPostReturnsArrayOfKnowledgePointSourceResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.responses['201'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.responses['201'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgePointSourceResponse"));
    }

    /** (4) list GET → array + items.$ref. */
    @Test
    void listGetReturnsArrayOfKnowledgePointSourceResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/KnowledgePointSourceResponse"));
    }

    /** (5) response schema exposes citation identity only. */
    @Test
    void responseSchemaExposesCitationIdentityOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.knowledgePointId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.contentBlockId.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.relationType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.relevanceScore.type")
                        .value("number"))
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.normalizedText")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.KnowledgePointSourceResponse.properties.storageKey")
                        .doesNotExist());
    }

    /** (6) both paths declare bearerAuth. */
    @Test
    void bothPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].post.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + LINK_BASE + "'].get.security[0].bearerAuth")
                        .exists());
    }
}

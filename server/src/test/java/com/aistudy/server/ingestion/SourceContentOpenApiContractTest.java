package com.aistudy.server.ingestion;

import com.aistudy.server.ingestion.job.mapper.IngestionJobMapper;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
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

import static org.hamcrest.Matchers.hasItem;
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
 * BUSINESS-006 — OpenAPI contract test for the SourcePage /
 * ContentBlock read APIs ({@code test} profile + {@code @MockitoBean}
 * mappers so the context boots without MySQL).
 *
 * <p>Verifies: both read paths are exposed; GET pages returns
 * {@code array + items.$ref} of {@code SourcePageResponse}; GET
 * content-blocks returns {@code array + items.$ref} of
 * {@code ContentBlockResponse} and declares the optional pageId query
 * param; response schemas expose extracted content + locator and NO
 * storage internals; bearerAuth is declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourceContentOpenApiContractTest {

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

    private static final String PAGES =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/pages";
    private static final String BLOCKS =
            "/api/v1/spaces/{spaceId}/sources/{sourceId}/content-blocks";

    /** (1) both read paths are exposed. */
    @Test
    void contentReadPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + PAGES + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get").exists());
    }

    /** (2) pages GET → array + items.$ref of SourcePageResponse. */
    @Test
    void pagesGetReturnsArrayOfSourcePageResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PAGES + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + PAGES + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/SourcePageResponse"));
    }

    /** (3) blocks GET → array + items.$ref of ContentBlockResponse. */
    @Test
    void blocksGetReturnsArrayOfContentBlockResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/ContentBlockResponse"));
    }

    /**
     * (4) blocks GET declares the optional pageId query param.
     * Parameters are matched BY NAME — the OpenAPI parameters array
     * (spaceId, sourceId, pageId) has no stable ordering contract, so
     * a fixed index like parameters[0] would be a test bug
     * (RUNTIME-FIX-03-B).
     */
    @Test
    void blocksGetDeclaresPageIdQueryParam() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.parameters[*].name",
                        hasItem("pageId")))
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.parameters[?(@.name == 'pageId')].in",
                        hasItem("query")))
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.parameters[?(@.name == 'pageId')].required",
                        hasItem(false)));
    }

    /** (5) page response schema exposes extracted text + order fields, no storage internals. */
    @Test
    void pageResponseSchemaExposesExtractedContent() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.SourcePageResponse.properties.extractedText.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourcePageResponse.properties.pageOrder.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.SourcePageResponse.properties.orderStatus.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.SourcePageResponse.properties.storageKey")
                        .doesNotExist());
    }

    /** (6) block response schema exposes text + locator, no storage internals. */
    @Test
    void blockResponseSchemaExposesTextAndLocator() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ContentBlockResponse.properties.normalizedText.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ContentBlockResponse.properties.locatorJson.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ContentBlockResponse.properties.blockType.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ContentBlockResponse.properties.sortOrder.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.ContentBlockResponse.properties.storageKey")
                        .doesNotExist());
    }

    /** (7) both paths declare bearerAuth. */
    @Test
    void bothPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PAGES + "'].get.security[0].bearerAuth")
                        .exists())
                .andExpect(jsonPath("$.paths['" + BLOCKS + "'].get.security[0].bearerAuth")
                        .exists());
    }
}

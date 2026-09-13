package com.aistudy.server.mastery;

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
 * BUSINESS-014 — OpenAPI contract test for the Mastery API
 * ({@code test} profile + {@code @MockitoBean} mappers).
 *
 * <p>Verifies: both mastery paths exposed; list response is a typed
 * array of {@code MasteryResponse}; detail is a typed object; score
 * is read-only (no POST paths); bearerAuth declared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasteryOpenApiContractTest {

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
    private com.aistudy.server.ai.mapper.AiMessageReferenceMapper aiMessageReferenceMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSettingsMapper aiProviderSettingsMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSecretMapper aiProviderSecretMapper;


    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;

    private static final String MASTERY = "/api/v1/spaces/{spaceId}/mastery";
    private static final String MASTERY_DETAIL =
            "/api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery";

    /** (1) both mastery paths exposed. */
    @Test
    void masteryPathsAreExposedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].get").exists())
                .andExpect(jsonPath("$.paths['" + MASTERY_DETAIL + "'].get").exists());
    }

    /** (2) list GET → typed array of MasteryResponse. */
    @Test
    void listReturnsTypedArray() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].get.responses['200'].content['application/json'].schema.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].get.responses['200'].content['application/json'].schema.items.$ref")
                        .value("#/components/schemas/MasteryResponse"));
    }

    /** (3) detail GET → typed MasteryResponse object. */
    @Test
    void detailReturnsTypedObject() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + MASTERY_DETAIL + "'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/MasteryResponse"));
    }

    /** (4) MasteryResponse schema carries explainable evidence fields. */
    @Test
    void masteryResponseSchemaIsExplainable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.masteryScore.type")
                        .value("number"))
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.confidence.type")
                        .value("number"))
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.practiceEvidenceCount.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.examEvidenceCount.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.reviewEvidenceCount.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.MasteryResponse.properties.lastEvidenceAt.type")
                        .value("string"));
    }

    /** (5) no write paths exist — score can never be client-submitted. */
    @Test
    void masteryIsReadOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['" + MASTERY_DETAIL + "'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].put").doesNotExist())
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].patch").doesNotExist());
    }

    /** (6) bearerAuth declared on both paths. */
    @Test
    void masteryPathsDeclareBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + MASTERY + "'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['" + MASTERY_DETAIL + "'].get.security[0].bearerAuth").exists());
    }
}

package com.aistudy.server.spike;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpikeHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * MICRO-08B: mock the SPIKE-only membership repository so this
     * health-check HTTP test does not need a real
     * {@code JdbcTemplate} or {@code DataSource} bean on the classpath.
     *
     * <h3>Why this mock is required</h3>
     *
     * The {@code test} profile (see {@code application-test.yml})
     * deliberately excludes {@code DataSourceAutoConfiguration} so
     * unit-oriented tests run without a database. Since MICRO-07E-A
     * wired {@code SpikeSpaceAccess} to constructor-inject
     * {@code SpikeSpaceMembershipRepository}, and that repository
     * constructor-injects {@code JdbcTemplate}, the full application
     * context fails to boot under this profile with:
     * <pre>
     *   No qualifying bean of type
     *     'org.springframework.jdbc.core.JdbcTemplate'
     * </pre>
     *
     * Replacing the repository bean with a Mockito mock via
     * {@code @MockitoBean} prevents Spring from ever calling the
     * real repository's constructor, so the missing
     * {@code JdbcTemplate} dependency is never resolved.
     *
     * <h3>Why not stub the mock in this test</h3>
     *
     * {@code /health} is a health-check endpoint that has no
     * business calling {@code SpikeSpaceAccess} or its repository —
     * it is guarded by {@code permitAll} in
     * {@code SpikeSecurityConfig} and returns a static
     * {@code {"status":"UP","service":"AIStudyServer","phase":"SPIKE-001"}}
     * body. Therefore we add no {@code when(...)}, {@code given(...)},
     * or {@code verify(...)} calls. The mock exists purely to keep
     * the Spring context bootable under the {@code test} profile.
     *
     * <h3>Why not change the profile</h3>
     *
     * This test was written before MICRO-07D-A added the repository
     * and was already correct under the {@code test} profile.
     * Switching it to a database-backed profile (e.g. {@code
     * flyway-it}) would force this health smoke test to stand up a
     * real MySQL, which is the wrong test for that. The
     * repository-to-database path is owned by
     * {@code SpikeSpaceMembershipIntegrationTest} and
     * {@code SpikeSpaceAuthorizationEndToEndIntegrationTest}.
     */
    @MockitoBean
    private SpikeSpaceMembershipRepository membershipRepository;

    /**
     * BUSINESS-001: mock the production LearningSpace mapper so this
     * health-check test keeps running without MyBatis-Plus /
     * DataSource under the {@code test} profile. Not stubbed —
     * {@code /health} never touches space persistence.
     */
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
    private com.aistudy.server.ai.mapper.AiMessageReferenceMapper aiMessageReferenceMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSettingsMapper aiProviderSettingsMapper;

    @MockitoBean
    private com.aistudy.server.ai.settings.AiProviderSecretMapper aiProviderSecretMapper;


    @MockitoBean
    private com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper;

    @MockitoBean
    private com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper;




    @MockitoBean
    private com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper;

    @Test
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("AIStudyServer"))
                .andExpect(jsonPath("$.phase").value("SPIKE-001"));
    }
}

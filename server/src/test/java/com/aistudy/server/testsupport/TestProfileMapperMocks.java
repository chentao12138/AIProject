package com.aistudy.server.testsupport;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared mapper mocks for {@code test}-profile full-context tests.
 *
 * <p>MyBatis-Plus auto-configuration is excluded under {@code test}, so every
 * {@code @Mapper} would otherwise be missing and services/controllers that
 * constructor-inject them fail context boot. Each new production mapper must
 * be registered here (or the owning test must add a local {@code @MockitoBean}).
 */
@TestConfiguration
public class TestProfileMapperMocks {

    @Bean
    public com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository spikeSpaceMembershipRepository() {
        return Mockito.mock(com.aistudy.server.spike.auth.SpikeSpaceMembershipRepository.class);
    }

    @Bean
    public com.aistudy.server.space.mapper.LearningSpaceMapper learningSpaceMapper() {
        return Mockito.mock(com.aistudy.server.space.mapper.LearningSpaceMapper.class);
    }

    @Bean
    public com.aistudy.server.source.mapper.SourceMapper sourceMapper() {
        return Mockito.mock(com.aistudy.server.source.mapper.SourceMapper.class);
    }

    @Bean
    public com.aistudy.server.source.asset.mapper.SourceAssetMapper sourceAssetMapper() {
        return Mockito.mock(com.aistudy.server.source.asset.mapper.SourceAssetMapper.class);
    }

    @Bean
    public com.aistudy.server.source.page.mapper.SourcePageMapper sourcePageMapper() {
        return Mockito.mock(com.aistudy.server.source.page.mapper.SourcePageMapper.class);
    }

    @Bean
    public com.aistudy.server.source.content.mapper.ContentBlockMapper contentBlockMapper() {
        return Mockito.mock(com.aistudy.server.source.content.mapper.ContentBlockMapper.class);
    }

    @Bean
    public com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper knowledgeCategoryMapper() {
        return Mockito.mock(com.aistudy.server.knowledge.category.mapper.KnowledgeCategoryMapper.class);
    }

    @Bean
    public com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper knowledgePointMapper() {
        return Mockito.mock(com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper.class);
    }

    @Bean
    public com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper knowledgePointSourceMapper() {
        return Mockito.mock(com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper.class);
    }

    @Bean
    public com.aistudy.server.ingestion.job.mapper.IngestionJobMapper ingestionJobMapper() {
        return Mockito.mock(com.aistudy.server.ingestion.job.mapper.IngestionJobMapper.class);
    }

    @Bean
    public com.aistudy.server.question.mapper.QuestionMapper questionMapper() {
        return Mockito.mock(com.aistudy.server.question.mapper.QuestionMapper.class);
    }

    @Bean
    public com.aistudy.server.question.mapper.QuestionOptionMapper questionOptionMapper() {
        return Mockito.mock(com.aistudy.server.question.mapper.QuestionOptionMapper.class);
    }

    @Bean
    public com.aistudy.server.question.mapper.QuestionKnowledgePointMapper questionKnowledgePointMapper() {
        return Mockito.mock(com.aistudy.server.question.mapper.QuestionKnowledgePointMapper.class);
    }

    @Bean
    public com.aistudy.server.practice.mapper.PracticeSessionMapper practiceSessionMapper() {
        return Mockito.mock(com.aistudy.server.practice.mapper.PracticeSessionMapper.class);
    }

    @Bean
    public com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper practiceSessionQuestionMapper() {
        return Mockito.mock(com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper.class);
    }

    @Bean
    public com.aistudy.server.practice.mapper.PracticeAnswerMapper practiceAnswerMapper() {
        return Mockito.mock(com.aistudy.server.practice.mapper.PracticeAnswerMapper.class);
    }

    @Bean
    public com.aistudy.server.wrong.mapper.WrongQuestionMapper wrongQuestionMapper() {
        return Mockito.mock(com.aistudy.server.wrong.mapper.WrongQuestionMapper.class);
    }

    @Bean
    public com.aistudy.server.wrong.mapper.ReviewTaskMapper reviewTaskMapper() {
        return Mockito.mock(com.aistudy.server.wrong.mapper.ReviewTaskMapper.class);
    }

    @Bean
    public com.aistudy.server.wrong.mapper.ReviewRecordMapper reviewRecordMapper() {
        return Mockito.mock(com.aistudy.server.wrong.mapper.ReviewRecordMapper.class);
    }

    @Bean
    public com.aistudy.server.wrong.mapper.ReviewStateMapper reviewStateMapper() {
        return Mockito.mock(com.aistudy.server.wrong.mapper.ReviewStateMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamMapper examMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamPaperMapper examPaperMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamPaperMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamQuestionMapper examQuestionMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamQuestionMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamAttemptMapper examAttemptMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamAttemptMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamAnswerMapper examAnswerMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamAnswerMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamResultMapper examResultMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamResultMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamDiagnosisMapper examDiagnosisMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamDiagnosisMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper examDiagnosisItemMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamDiagnosisItemMapper.class);
    }

    @Bean
    public com.aistudy.server.mastery.mapper.MasteryMapper masteryMapper() {
        return Mockito.mock(com.aistudy.server.mastery.mapper.MasteryMapper.class);
    }

    @Bean
    public com.aistudy.server.studyplan.mapper.StudyPlanMapper studyPlanMapper() {
        return Mockito.mock(com.aistudy.server.studyplan.mapper.StudyPlanMapper.class);
    }

    @Bean
    public com.aistudy.server.studyplan.mapper.StudyTaskMapper studyTaskMapper() {
        return Mockito.mock(com.aistudy.server.studyplan.mapper.StudyTaskMapper.class);
    }

    @Bean
    public com.aistudy.server.search.mapper.SearchMapper searchMapper() {
        return Mockito.mock(com.aistudy.server.search.mapper.SearchMapper.class);
    }

    @Bean
    public com.aistudy.server.auth.mapper.UserAccountMapper userAccountMapper() {
        return Mockito.mock(com.aistudy.server.auth.mapper.UserAccountMapper.class);
    }

    @Bean
    public com.aistudy.server.auth.mapper.UserAccountRoleMapper userAccountRoleMapper() {
        return Mockito.mock(com.aistudy.server.auth.mapper.UserAccountRoleMapper.class);
    }

    @Bean
    public com.aistudy.server.auth.mapper.RefreshSessionMapper refreshSessionMapper() {
        return Mockito.mock(com.aistudy.server.auth.mapper.RefreshSessionMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.mapper.AiConversationMapper aiConversationMapper() {
        return Mockito.mock(com.aistudy.server.ai.mapper.AiConversationMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.mapper.AiMessageMapper aiMessageMapper() {
        return Mockito.mock(com.aistudy.server.ai.mapper.AiMessageMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.mapper.AiMessageReferenceMapper aiMessageReferenceMapper() {
        return Mockito.mock(com.aistudy.server.ai.mapper.AiMessageReferenceMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.settings.AiProviderSettingsMapper aiProviderSettingsMapper() {
        return Mockito.mock(com.aistudy.server.ai.settings.AiProviderSettingsMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.settings.AiProviderSecretMapper aiProviderSecretMapper() {
        return Mockito.mock(com.aistudy.server.ai.settings.AiProviderSecretMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.mapper.AIGenerationJobMapper aiGenerationJobMapper() {
        return Mockito.mock(com.aistudy.server.ai.mapper.AIGenerationJobMapper.class);
    }

    @Bean
    public com.aistudy.server.ai.mapper.AiUsageRecordMapper aiUsageRecordMapper() {
        return Mockito.mock(com.aistudy.server.ai.mapper.AiUsageRecordMapper.class);
    }

    @Bean
    public com.aistudy.server.config.mapper.SystemConfigMapper systemConfigMapper() {
        return Mockito.mock(com.aistudy.server.config.mapper.SystemConfigMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamBlueprintMapper examBlueprintMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamBlueprintMapper.class);
    }

    @Bean
    public com.aistudy.server.exam.mapper.ExamStatisticsMapper examStatisticsMapper() {
        return Mockito.mock(com.aistudy.server.exam.mapper.ExamStatisticsMapper.class);
    }

    @Bean
    public com.aistudy.server.ingestion.issue.mapper.IngestionIssueMapper ingestionIssueMapper() {
        return Mockito.mock(com.aistudy.server.ingestion.issue.mapper.IngestionIssueMapper.class);
    }

    @Bean
    public com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper extractionRevisionMapper() {
        return Mockito.mock(com.aistudy.server.ingestion.revision.mapper.ExtractionRevisionMapper.class);
    }

    @Bean
    public com.aistudy.server.knowledge.point.mapper.KnowledgePointRelationMapper knowledgePointRelationMapper() {
        return Mockito.mock(com.aistudy.server.knowledge.point.mapper.KnowledgePointRelationMapper.class);
    }

    @Bean
    public com.aistudy.server.mastery.mapper.MasteryCalibrationConfigMapper masteryCalibrationConfigMapper() {
        return Mockito.mock(com.aistudy.server.mastery.mapper.MasteryCalibrationConfigMapper.class);
    }

    @Bean
    public com.aistudy.server.note.mapper.NoteMapper noteMapper() {
        return Mockito.mock(com.aistudy.server.note.mapper.NoteMapper.class);
    }

    @Bean
    public com.aistudy.server.note.mapper.NoteKnowledgePointMapper noteKnowledgePointMapper() {
        return Mockito.mock(com.aistudy.server.note.mapper.NoteKnowledgePointMapper.class);
    }

    @Bean
    public com.aistudy.server.note.mapper.NoteSourceMapper noteSourceMapper() {
        return Mockito.mock(com.aistudy.server.note.mapper.NoteSourceMapper.class);
    }

    @Bean
    public com.aistudy.server.question.mapper.QuestionSourceMapper questionSourceMapper() {
        return Mockito.mock(com.aistudy.server.question.mapper.QuestionSourceMapper.class);
    }

    @Bean
    public com.aistudy.server.source.folder.mapper.FolderImportSnapshotMapper folderImportSnapshotMapper() {
        return Mockito.mock(com.aistudy.server.source.folder.mapper.FolderImportSnapshotMapper.class);
    }

    @Bean
    public com.aistudy.server.source.folder.mapper.FolderImportEntryMapper folderImportEntryMapper() {
        return Mockito.mock(com.aistudy.server.source.folder.mapper.FolderImportEntryMapper.class);
    }

    @Bean
    public com.aistudy.server.source.outline.mapper.SourceOutlineNodeMapper sourceOutlineNodeMapper() {
        return Mockito.mock(com.aistudy.server.source.outline.mapper.SourceOutlineNodeMapper.class);
    }
}

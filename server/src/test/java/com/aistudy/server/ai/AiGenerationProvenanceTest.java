package com.aistudy.server.ai;

import com.aistudy.server.ai.knowledge.AiKnowledgeExtractionService;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.question.AiQuestionGenerationService;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.knowledge.source.service.KnowledgePointSourceService;
import com.aistudy.server.question.dto.CreateQuestionRequest;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.service.QuestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the provenance, validate-before-write and usage-ledger rules of the
 * two AI batch generators (audit F-19 / F-20 / F-23).
 *
 * <p>Why plain unit tests: the rule under test is the ORDER of operations —
 * nothing may reach the database unless the whole provider response is usable,
 * and every attempt must appear in the ledger. Both are observable from the
 * collaborating calls alone, so pinning them here means they are checked on
 * every build instead of only when a MySQL schema happens to be reachable.
 *
 * <p>What it does NOT cover: rollback of a transaction that fails halfway, and
 * the rules the authoring module applies inside
 * {@link QuestionService#createDerived}, which have their own tests there.
 */
class AiGenerationProvenanceTest {

    private static final String SUBJECT = "owner-subject";
    private static final Long SPACE_ID = 7L;
    private static final Long SOURCE_ID = 42L;

    private final AiProvider provider = mock(AiProvider.class);
    private final KnowledgePointMapper knowledgePointMapper =
            mock(KnowledgePointMapper.class);
    private final KnowledgePointSourceService knowledgePointSourceService =
            mock(KnowledgePointSourceService.class);
    private final KnowledgePointService knowledgePointService =
            mock(KnowledgePointService.class);
    private final QuestionService questionService = mock(QuestionService.class);
    private final AiUsageRecordService usageRecordService =
            mock(AiUsageRecordService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiKnowledgeExtractionService extraction;
    private AiQuestionGenerationService generation;

    @BeforeEach
    void setUp() {
        extraction = new AiKnowledgeExtractionService(
                provider, knowledgePointMapper, knowledgePointSourceService,
                usageRecordService, objectMapper);
        generation = new AiQuestionGenerationService(
                provider, knowledgePointService, questionService,
                usageRecordService, objectMapper);
        when(knowledgePointMapper.insert(any(KnowledgePoint.class))).thenAnswer(call -> {
            call.getArgument(0, KnowledgePoint.class).setId(100L);
            return 1;
        });
        when(questionService.createDerived(anyString(), anyLong(),
                any(CreateQuestionRequest.class), anyString())).thenAnswer(call -> {
            Question question = new Question();
            question.setId(200L);
            question.setSpaceId(call.getArgument(1, Long.class));
            question.setStatus("DRAFT");
            question.setOriginType(call.getArgument(3, String.class));
            question.setQuestionType("SINGLE_CHOICE");
            return question;
        });
    }

    private void answerWith(String content) {
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse(content, "stepfun", "model-x", null));
    }

    private void existingPoint() {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setId(9L);
        kp.setTitle("正常形式");
        kp.setSummary("3NF 的描述");
        when(knowledgePointService.getMine(SUBJECT, SPACE_ID, 9L)).thenReturn(kp);
    }

    // ==================== F-19 knowledge extraction ====================

    /** Every extracted point gets a source-level provenance link. */
    @Test
    void extractionWritesSourceProvenanceForEveryPoint() {
        answerWith("[{\"title\":\"正常形式\",\"summary\":\"3NF 的描述\"},"
                + "{\"title\":\"函数依赖\",\"summary\":\"X 决定 Y\"}]");

        List<KnowledgePoint> created =
                extraction.extract(SUBJECT, SPACE_ID, SOURCE_ID, "正文");

        assertEquals(2, created.size());
        verify(knowledgePointSourceService, times(2))
                .linkDerivedSource(SUBJECT, SPACE_ID, 100L, SOURCE_ID);
        assertEquals("DRAFT", created.get(0).getStatus(),
                "AI output is never published");
        // F-23: a successful generator call must appear in the usage ledger.
        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_KNOWLEDGE_EXTRACTION),
                any(AiChatResponse.class), anyInt(), isNull());
    }

    /** An unusable payload writes nothing at all. */
    @Test
    void extractionWritesNothingWhenPayloadIsNotJson() {
        answerWith("this is not json");

        assertThrows(IllegalStateException.class,
                () -> extraction.extract(SUBJECT, SPACE_ID, SOURCE_ID, "正文"));

        verify(knowledgePointMapper, never()).insert(any(KnowledgePoint.class));
        verify(knowledgePointSourceService, never())
                .linkDerivedSource(anyString(), anyLong(), anyLong(), anyLong());
        // The tokens were still spent, so the attempt is booked as a failure
        // rather than dropped: skipping it would make an outage look like no use.
        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_KNOWLEDGE_EXTRACTION),
                any(AiChatResponse.class), anyInt(), eq("AI_RESPONSE_INVALID"));
    }

    /**
     * One incomplete entry rejects the whole batch: the old code inserted rows
     * while parsing and skipped bad ones, so a partially usable response could
     * leave a set of points nobody would ever review as a unit.
     */
    @Test
    void extractionRefusesWholeBatchWhenOneEntryIsIncomplete() {
        answerWith("[{\"title\":\"可用\",\"summary\":\"有内容\"},"
                + "{\"title\":\"\",\"summary\":\"\"}]");

        assertThrows(IllegalStateException.class,
                () -> extraction.extract(SUBJECT, SPACE_ID, SOURCE_ID, "正文"));

        verify(knowledgePointMapper, never()).insert(any(KnowledgePoint.class));
    }

    // ==================== F-20 question generation ====================

    /** The question is written by the authoring module, marked AI derived. */
    @Test
    void generationDelegatesTheWriteToQuestionService() {
        existingPoint();
        answerWith("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"以下哪项是 3NF\","
                + "\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}],"
                + "\"correctOptionKey\":\"A\",\"explanation\":\"\",\"difficulty\":\"MEDIUM\"}");

        Question question = generation.generate(SUBJECT, SPACE_ID, 9L);

        assertEquals("DRAFT", question.getStatus());
        ArgumentCaptor<CreateQuestionRequest> request =
                ArgumentCaptor.forClass(CreateQuestionRequest.class);
        verify(questionService).createDerived(eq(SUBJECT), eq(SPACE_ID),
                request.capture(), eq(QuestionService.ORIGIN_TYPE_AI_DERIVED));
        assertEquals(List.of(9L), request.getValue().knowledgePointIds(),
                "the generated question must stay linked to its knowledge point");
        assertEquals(2, request.getValue().options().size());
        assertEquals("A", request.getValue().correctOptionKey());
    }

    /** An answer key that is not among the returned options is never written. */
    @Test
    void generationRefusesAnswerThatIsNotAmongOptions() {
        existingPoint();
        answerWith("{\"stem\":\"题\",\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}],\"correctOptionKey\":\"C\"}");

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 9L));

        verify(questionService, never()).createDerived(anyString(), anyLong(),
                any(CreateQuestionRequest.class), anyString());
    }

    /** Fewer than two options cannot be a choice question. */
    @Test
    void generationRefusesSingleOptionQuestion() {
        existingPoint();
        answerWith("{\"stem\":\"题\",\"options\":[{\"key\":\"A\",\"content\":\"甲\"}],"
                + "\"correctOptionKey\":\"A\"}");

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 9L));

        verify(questionService, never()).createDerived(anyString(), anyLong(),
                any(CreateQuestionRequest.class), anyString());
    }

    /** A knowledge point outside the caller's space is refused before any call. */
    @Test
    void generationRefusesForeignKnowledgePoint() {
        when(knowledgePointService.getMine(anyString(), anyLong(), anyLong()))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 999L));

        verify(provider, never()).chat(any(AiChatRequest.class), anyString());
        verify(usageRecordService, never()).recordProviderCall(anyString(), anyLong(),
                anyString(), any(), anyInt(), any());
    }
}

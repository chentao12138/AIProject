package com.aistudy.server.ai;

import com.aistudy.server.ai.knowledge.AiKnowledgeExtractionService;
import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.question.AiQuestionGenerationService;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.knowledge.source.entity.KnowledgePointSource;
import com.aistudy.server.knowledge.source.mapper.KnowledgePointSourceMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the provenance and validate-before-write rules of the two AI batch
 * generators (audit F-19 / F-20).
 *
 * <p>Why this is a plain unit test: the rule under test is the ORDER of
 * operations — nothing may reach the database unless the whole provider
 * response is usable. That is observable from the mapper interactions alone,
 * and pinning it without a database means it is checked on every build rather
 * than only when a MySQL schema happens to be reachable.
 *
 * <p>What it does NOT cover: rollback of a transaction that fails halfway,
 * which needs a real database, and {@code question_source}, which cannot be
 * written from a generation job at all because that table only references a
 * content block and a job only knows the source.
 */
class AiGenerationProvenanceTest {

    private static final String SUBJECT = "owner-subject";
    private static final Long SPACE_ID = 7L;
    private static final Long SOURCE_ID = 42L;

    private final AiProvider provider = mock(AiProvider.class);
    private final KnowledgePointMapper knowledgePointMapper =
            mock(KnowledgePointMapper.class);
    private final KnowledgePointSourceMapper knowledgePointSourceMapper =
            mock(KnowledgePointSourceMapper.class);
    private final QuestionMapper questionMapper = mock(QuestionMapper.class);
    private final QuestionOptionMapper questionOptionMapper =
            mock(QuestionOptionMapper.class);
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper =
            mock(QuestionKnowledgePointMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiKnowledgeExtractionService extraction;
    private AiQuestionGenerationService generation;

    @BeforeEach
    void setUp() {
        extraction = new AiKnowledgeExtractionService(
                provider, knowledgePointMapper, knowledgePointSourceMapper, objectMapper);
        generation = new AiQuestionGenerationService(
                provider, knowledgePointMapper, questionMapper,
                questionOptionMapper, questionKnowledgePointMapper, objectMapper);
        when(knowledgePointMapper.insert(any(KnowledgePoint.class))).thenAnswer(call -> {
            call.getArgument(0, KnowledgePoint.class).setId(100L);
            return 1;
        });
        when(questionMapper.insert(any(Question.class))).thenAnswer(call -> {
            call.getArgument(0, Question.class).setId(200L);
            return 1;
        });
    }

    private void answerWith(String content) {
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse(content, "stepfun", "model-x", null));
    }

    // ==================== F-19 knowledge extraction ====================

    /** Each extracted point gets a source-level provenance row. */
    @Test
    void extractionWritesSourceProvenanceForEveryPoint() {
        answerWith("[{\"title\":\"正常形式\",\"summary\":\"3NF 的描述\"},"
                + "{\"title\":\"函数依赖\",\"summary\":\"X 决定 Y\"}]");

        List<KnowledgePoint> created =
                extraction.extract(SUBJECT, SPACE_ID, SOURCE_ID, "正文");

        assertEquals(2, created.size());
        ArgumentCaptor<KnowledgePointSource> links =
                ArgumentCaptor.forClass(KnowledgePointSource.class);
        verify(knowledgePointSourceMapper, org.mockito.Mockito.times(2))
                .insert(links.capture());
        KnowledgePointSource first = links.getAllValues().get(0);
        assertEquals(SOURCE_ID, first.getSourceId(),
                "an AI_DERIVED point must be traceable to the source it came from");
        assertEquals(100L, first.getKnowledgePointId());
        assertEquals(SPACE_ID, first.getSpaceId());
        assertEquals("AI_DERIVED", first.getRelationType());
        assertEquals("DRAFT", created.get(0).getStatus(),
                "AI output is never published");
    }

    /** An unusable payload writes nothing at all. */
    @Test
    void extractionWritesNothingWhenPayloadIsNotJson() {
        answerWith("this is not json");

        assertThrows(IllegalStateException.class,
                () -> extraction.extract(SUBJECT, SPACE_ID, SOURCE_ID, "正文"));

        verify(knowledgePointMapper, never()).insert(any(KnowledgePoint.class));
        verify(knowledgePointSourceMapper, never()).insert(any(KnowledgePointSource.class));
    }

    /**
     * One incomplete entry rejects the whole batch: the old code inserted rows
     * while parsing and only skipped bad ones, so a partially usable response
     * could leave a set of points that no one would ever review as a unit.
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

    private KnowledgePoint existingPoint() {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setId(9L);
        kp.setTitle("正常形式");
        kp.setSummary("3NF 的描述");
        when(knowledgePointMapper.selectByIdSpaceOwner(anyLong(), anyLong(), anyString()))
                .thenReturn(kp);
        return kp;
    }

    /** The generated question is linked to the knowledge point it was made for. */
    @Test
    void generationLinksQuestionToKnowledgePoint() {
        existingPoint();
        answerWith("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"以下哪项是 3NF\","
                + "\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}],"
                + "\"correctOptionKey\":\"A\",\"explanation\":\"\",\"difficulty\":\"MEDIUM\"}");

        Question question = generation.generate(SUBJECT, SPACE_ID, 9L);

        assertEquals("DRAFT", question.getStatus());
        ArgumentCaptor<QuestionKnowledgePoint> link =
                ArgumentCaptor.forClass(QuestionKnowledgePoint.class);
        verify(questionKnowledgePointMapper).insert(link.capture());
        assertEquals(200L, link.getValue().getQuestionId());
        assertEquals(9L, link.getValue().getKnowledgePointId());
        assertEquals(SPACE_ID, link.getValue().getSpaceId());
        verify(questionOptionMapper, org.mockito.Mockito.times(2))
                .insert(any(com.aistudy.server.question.entity.QuestionOption.class));
    }

    /** An answer key that is not among the returned options is never stored. */
    @Test
    void generationRefusesAnswerThatIsNotAmongOptions() {
        existingPoint();
        answerWith("{\"stem\":\"题\",\"options\":[{\"key\":\"A\",\"content\":\"甲\"},"
                + "{\"key\":\"B\",\"content\":\"乙\"}],\"correctOptionKey\":\"C\"}");

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 9L));

        verify(questionMapper, never()).insert(any(Question.class));
        verify(questionOptionMapper, never())
                .insert(any(com.aistudy.server.question.entity.QuestionOption.class));
    }

    /** Fewer than two options cannot be a choice question. */
    @Test
    void generationRefusesSingleOptionQuestion() {
        existingPoint();
        answerWith("{\"stem\":\"题\",\"options\":[{\"key\":\"A\",\"content\":\"甲\"}],"
                + "\"correctOptionKey\":\"A\"}");

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 9L));

        verify(questionMapper, never()).insert(any(Question.class));
    }

    /** A knowledge point outside the caller's space is refused before any call. */
    @Test
    void generationRefusesForeignKnowledgePoint() {
        when(knowledgePointMapper.selectByIdSpaceOwner(anyLong(), anyLong(), anyString()))
                .thenReturn(null);
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse("{}", "stepfun", "model-x", null));

        assertThrows(IllegalStateException.class,
                () -> generation.generate(SUBJECT, SPACE_ID, 999L));

        verify(provider, never()).chat(any(AiChatRequest.class), anyString());
        verify(questionMapper, never()).insert(any(Question.class));
    }
}

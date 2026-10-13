package com.aistudy.server.question;

import com.aistudy.server.ai.provider.AiChatRequest;
import com.aistudy.server.ai.provider.AiChatResponse;
import com.aistudy.server.ai.provider.AiProvider;
import com.aistudy.server.ai.service.AiUsageRecordService;
import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.practice.entity.PracticeSession;
import com.aistudy.server.practice.entity.PracticeSessionQuestion;
import com.aistudy.server.practice.mapper.PracticeSessionMapper;
import com.aistudy.server.practice.mapper.PracticeSessionQuestionMapper;
import com.aistudy.server.practice.service.PracticeSessionService;
import com.aistudy.server.question.controller.VariantController;
import com.aistudy.server.question.dto.AiVariantDto;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.question.service.AiVariantService;
import com.aistudy.server.space.service.LearningSpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract guards for the "variant of a wrong question" feature
 * (audit F-16 / F-17 / F-23).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@code save=true} answers with a real 201 and a readable
 *       {@code SavedVariantResponse} body. It used to raise 201 through
 *       {@code ResponseStatusException}, which put the new questionId behind the
 *       error channel where no typed client could read it.</li>
 *   <li>{@code save=false} stays 200 with the transient variant.</li>
 *   <li>A variant inherits the original answer, so an option set that no longer
 *       contains the correct key is refused with 502 and nothing is persisted —
 *       otherwise the saved question cannot be graded correctly.</li>
 *   <li>Every provider attempt is booked in the usage ledger, including the
 *       failed one.</li>
 * </ul>
 *
 * <p>The controller is invoked directly rather than through MockMvc: the
 * behaviour under test is the returned status and body, and the ownership
 * guards above it are covered by the practice integration tests.
 */
class AiVariantContractTest {

    private static final String SUBJECT = "owner-subject";
    private static final Long SPACE_ID = 7L;
    private static final Long SESSION_ID = 21L;
    private static final Long QUESTION_ID = 31L;

    private final AiProvider provider = mock(AiProvider.class);
    private final QuestionMapper questionMapper = mock(QuestionMapper.class);
    private final QuestionOptionMapper questionOptionMapper = mock(QuestionOptionMapper.class);
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper =
            mock(QuestionKnowledgePointMapper.class);
    private final KnowledgePointMapper knowledgePointMapper = mock(KnowledgePointMapper.class);
    private final LearningSpaceService learningSpaceService = mock(LearningSpaceService.class);
    private final AiUsageRecordService usageRecordService = mock(AiUsageRecordService.class);
    private final AiVariantService variantService = new AiVariantService(
            provider, questionMapper, questionOptionMapper, questionKnowledgePointMapper,
            knowledgePointMapper, learningSpaceService, usageRecordService, new ObjectMapper());

    private final PracticeSessionMapper practiceSessionMapper = mock(PracticeSessionMapper.class);
    private final PracticeSessionQuestionMapper practiceSessionQuestionMapper =
            mock(PracticeSessionQuestionMapper.class);

    private VariantController controller;

    @BeforeEach
    void setUp() {
        controller = new VariantController(variantService, practiceSessionMapper,
                practiceSessionQuestionMapper, learningSpaceService);

        PracticeSession session = new PracticeSession();
        session.setId(SESSION_ID);
        session.setSpaceId(SPACE_ID);
        session.setUserSubject(SUBJECT);
        session.setStatus(PracticeSessionService.STATUS_SUBMITTED);
        when(practiceSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(learningSpaceService.getMine(SUBJECT, SPACE_ID)).thenReturn(new com.aistudy.server.space.entity.LearningSpace());

        PracticeSessionQuestion slot = new PracticeSessionQuestion();
        slot.setId(41L);
        slot.setQuestionId(QUESTION_ID);
        when(practiceSessionQuestionMapper.selectBySessionId(SPACE_ID, SESSION_ID))
                .thenReturn(List.of(slot));

        when(questionMapper.selectByIdSpaceOwner(QUESTION_ID, SPACE_ID, SUBJECT))
                .thenReturn(originalQuestion("{\"correctOptionKey\":\"C\"}"));
        when(questionOptionMapper.selectByQuestionId(SPACE_ID, QUESTION_ID)).thenReturn(List.of());
        when(questionKnowledgePointMapper.selectByQuestionId(SPACE_ID, QUESTION_ID))
                .thenReturn(List.of());
    }

    private org.springframework.security.core.Authentication auth() {
        org.springframework.security.core.Authentication authentication =
                mock(org.springframework.security.core.Authentication.class);
        when(authentication.getName()).thenReturn(SUBJECT);
        return authentication;
    }

    private Question originalQuestion(String answerDataJson) {
        Question question = new Question();
        question.setId(QUESTION_ID);
        question.setSpaceId(SPACE_ID);
        question.setQuestionType("SINGLE_CHOICE");
        question.setStem("原题");
        question.setDifficulty("MEDIUM");
        question.setAnswerDataJson(answerDataJson);
        return question;
    }

    private void providerSays(String json) {
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenReturn(new AiChatResponse(json, "stepfun", "model-x", null));
    }

    /**
     * The inherited answer names option C; the model returned only A and B, so
     * the variant is refused and nothing is saved.
     */
    @Test
    void refusesVariantWhoseOptionsDropTheCorrectAnswer() {
        providerSays("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"新题干\","
                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                + "{\"optionKey\":\"B\",\"content\":\"乙\"}]}");

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> variantService.generateVariant(SUBJECT, SPACE_ID, QUESTION_ID,
                        new AiVariantDto.VariantRequest(null, null)));

        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
        verify(questionMapper, never()).insert(any(Question.class));
        // The call still cost tokens, so it must appear in the ledger.
        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_VARIANT), any(AiChatResponse.class),
                anyInt(), isNull());
    }

    /**
     * Option keys that keep the correct answer addressable are accepted, and the
     * saved variant is answered with a typed 201 body instead of an exception.
     */
    @Test
    void savedVariantReturnsTyped201WithTheNewQuestionId() {
        when(questionMapper.selectByIdSpaceOwner(QUESTION_ID, SPACE_ID, SUBJECT))
                .thenReturn(originalQuestion("{\"correctOptionKey\":\"A\"}"));
        providerSays("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"新题干\","
                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                + "{\"optionKey\":\"B\",\"content\":\"乙\"}],"
                + "\"explanation\":\"因为甲\"}");
        when(questionMapper.insert(any(Question.class))).thenAnswer(call -> {
            call.getArgument(0, Question.class).setId(77L);
            return 1;
        });

        AiVariantDto.VariantResponse variant = variantService.generateVariant(
                SUBJECT, SPACE_ID, QUESTION_ID, new AiVariantDto.VariantRequest(null, null));
        assertEquals("{\"correctOptionKey\":\"A\"}", variant.answerDataJson(),
                "the validated original answer is carried over unchanged");

        Object result = controller.variantFromPractice(SESSION_ID, QUESTION_ID, true,
                new AiVariantDto.VariantRequest(null, List.of()),
                auth());

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        AiVariantDto.SavedVariantResponse body =
                assertInstanceOf(AiVariantDto.SavedVariantResponse.class, response.getBody());
        assertEquals(77L, body.questionId());
        assertEquals("DRAFT", body.status(), "AI output never publishes itself");
        assertEquals("AI_DERIVED", body.originType());
    }

    /** The preview path keeps its 200 + transient body. */
    @Test
    void previewStillAnswers200WithTheVariant() {
        when(questionMapper.selectByIdSpaceOwner(QUESTION_ID, SPACE_ID, SUBJECT))
                .thenReturn(originalQuestion("{\"correctOptionKey\":\"A\"}"));
        providerSays("{\"questionType\":\"SINGLE_CHOICE\",\"stem\":\"新题干\","
                + "\"options\":[{\"optionKey\":\"A\",\"content\":\"甲\"},"
                + "{\"optionKey\":\"B\",\"content\":\"乙\"}]}");

        Object result = controller.variantFromPractice(SESSION_ID, QUESTION_ID, false,
                new AiVariantDto.VariantRequest(null, null),
                auth());

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(AiVariantDto.VariantResponse.class, response.getBody());
        verify(questionMapper, never()).insert(any(Question.class));
    }

    /** A provider failure is refused and booked, and never persisted. */
    @Test
    void providerFailureIsBookedAsFailedCall() {
        when(provider.chat(any(AiChatRequest.class), anyString()))
                .thenThrow(new com.aistudy.server.ai.provider.AiProviderException(
                        com.aistudy.server.ai.provider.AiErrorCode.AI_PROVIDER_UNAVAILABLE, "boom"));
        when(questionMapper.selectByIdSpaceOwner(QUESTION_ID, SPACE_ID, SUBJECT))
                .thenReturn(originalQuestion("{\"correctOptionKey\":\"A\"}"));

        assertThrows(ResponseStatusException.class,
                () -> variantService.generateVariant(SUBJECT, SPACE_ID, QUESTION_ID,
                        new AiVariantDto.VariantRequest(null, null)));

        verify(usageRecordService).recordProviderCall(eq(SUBJECT), eq(SPACE_ID),
                eq(AiUsageRecordService.PURPOSE_VARIANT), isNull(), anyInt(),
                eq("AI_PROVIDER_CALL_FAILED"));
        verify(questionMapper, never()).insert(any(Question.class));
    }
}

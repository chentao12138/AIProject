package com.aistudy.server.question.service;

import com.aistudy.server.knowledge.point.mapper.KnowledgePointMapper;
import com.aistudy.server.question.dto.CreateQuestionRequest;
import com.aistudy.server.question.dto.CreateQuestionRequest.QuestionOptionInput;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.entity.QuestionKnowledgePoint;
import com.aistudy.server.question.entity.QuestionOption;
import com.aistudy.server.question.eval.AnswerDataCodec;
import com.aistudy.server.question.mapper.QuestionKnowledgePointMapper;
import com.aistudy.server.question.mapper.QuestionMapper;
import com.aistudy.server.question.mapper.QuestionOptionMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-008 — application service for the Question bank.
 *
 * <p>The ONLY caller of the Question mappers. Every read is
 * owner-scoped SQL (JOIN learning_space + deleted_at IS NULL).
 *
 * <h3>Server-controlled fields</h3>
 *
 * <p>On create the service fixes: {@code originType = USER_CURATED},
 * {@code status = DRAFT}, {@code createdByUserId = ownerSubject},
 * {@code publishedAt = null}, {@code deletedAt = null}, and writes
 * {@code answer_data_json} from the typed request fields (never from
 * raw client JSON).
 *
 * <h3>Per-type validation (400)</h3>
 *
 * <ul>
 *   <li>SINGLE_CHOICE: 2..16 options, unique option keys, exactly one
 *       correctOptionKey that must exist among the options.</li>
 *   <li>MULTIPLE_CHOICE: 2..16 options, unique keys, &gt;=1
 *       correctOptionKeys, all present among the options, no
 *       duplicates.</li>
 *   <li>TRUE_FALSE: no options allowed, correctBoolean required.</li>
 *   <li>SHORT_ANSWER: no options allowed, referenceAnswer optional.</li>
 * </ul>
 *
 * <h3>Same-space invariants (404)</h3>
 *
 * <p>Space must be owned by the caller; every knowledgePointId must
 * resolve through the owner-scoped KnowledgePoint query (same space,
 * not deleted). Any violation → {@code null} → controller 404.
 *
 * <h3>Publish lifecycle</h3>
 *
 * <p>{@code DRAFT → PUBLISHED}, idempotent: re-publishing an already
 * published question is a NO-OP (no UPDATE, no timestamp refresh),
 * same semantic as BUSINESS-003.
 */
@Service
public class QuestionService {

    public static final String TYPE_SINGLE_CHOICE = "SINGLE_CHOICE";
    public static final String TYPE_MULTIPLE_CHOICE = "MULTIPLE_CHOICE";
    public static final String TYPE_TRUE_FALSE = "TRUE_FALSE";
    public static final String TYPE_SHORT_ANSWER = "SHORT_ANSWER";

    public static final String ORIGIN_TYPE_USER_CURATED = "USER_CURATED";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private static final int MAX_OPTIONS = 16;

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionKnowledgePointMapper questionKnowledgePointMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final LearningSpaceService learningSpaceService;

    public QuestionService(QuestionMapper questionMapper,
                           QuestionOptionMapper questionOptionMapper,
                           QuestionKnowledgePointMapper questionKnowledgePointMapper,
                           KnowledgePointMapper knowledgePointMapper,
                           LearningSpaceService learningSpaceService) {
        this.questionMapper = questionMapper;
        this.questionOptionMapper = questionOptionMapper;
        this.questionKnowledgePointMapper = questionKnowledgePointMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * Creates a USER_CURATED DRAFT question in the caller's own space.
     *
     * @return persisted question, or {@code null} when the space is not
     *         owned or a knowledgePointId is invalid (404)
     * @throws ResponseStatusException 400 on type/options violations
     */
    @Transactional
    public Question create(String ownerSubject, Long spaceId, CreateQuestionRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        String type = request.questionType();
        validateStructure(type, request);

        // Same-space knowledge points (all-or-nothing; null → 404).
        List<Long> kpIds = request.knowledgePointIds();
        if (kpIds != null && !kpIds.isEmpty()) {
            Set<Long> seen = new HashSet<>();
            for (Long kpId : kpIds) {
                if (!seen.add(kpId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "knowledgePointIds must not contain duplicates");
                }
                if (knowledgePointMapper.selectByIdSpaceOwner(kpId, spaceId, ownerSubject) == null) {
                    return null;
                }
            }
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        Question question = new Question();
        question.setSpaceId(spaceId);
        question.setQuestionType(type);
        question.setStem(request.stem());
        question.setExplanation(request.explanation());
        question.setDifficulty(request.difficulty());
        question.setOriginType(ORIGIN_TYPE_USER_CURATED);
        question.setStatus(STATUS_DRAFT);
        question.setCreatedByUserId(ownerSubject);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);
        question.setAnswerDataJson(buildAnswerDataJson(type, request));
        questionMapper.insert(question);

        insertOptions(question.getId(), spaceId, request.options());
        insertKnowledgePointLinks(question.getId(), spaceId, kpIds);
        return question;
    }

    /**
     * Lists non-deleted questions of the caller's own space with
     * optional filters (status / questionType / knowledgePointId).
     *
     * @return questions, or {@code null} when the space is not owned
     */
    public List<Question> listMine(String ownerSubject, Long spaceId,
                                   String status, String questionType, Long knowledgePointId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return questionMapper.selectBySpaceOwnerFilters(
                spaceId, ownerSubject, status, questionType, knowledgePointId);
    }

    /**
     * Returns ONE non-deleted question of the caller's own space
     * (owner-scoped SQL; {@code null} → 404).
     */
    public Question getMine(String ownerSubject, Long spaceId, Long questionId) {
        return questionMapper.selectByIdSpaceOwner(questionId, spaceId, ownerSubject);
    }

    /**
     * Publishes a DRAFT question (DRAFT → PUBLISHED). True idempotency:
     * already-published → returned unchanged, no UPDATE, no timestamp
     * refresh (same contract as BUSINESS-003 publish).
     */
    @Transactional
    public Question publish(String ownerSubject, Long spaceId, Long questionId) {
        Question existing = getMine(ownerSubject, spaceId, questionId);
        if (existing == null) {
            return null;
        }
        if (STATUS_PUBLISHED.equals(existing.getStatus())) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = questionMapper.publishByIdAndSpace(questionId, spaceId, STATUS_PUBLISHED, now, now);
        if (updated == 0) {
            return null;
        }
        existing.setStatus(STATUS_PUBLISHED);
        existing.setPublishedAt(now);
        existing.setUpdatedAt(now);
        return existing;
    }

    // ==================== internals ====================

    /** Type-specific validation. 400 on structural violations. */
    private void validateStructure(String type, CreateQuestionRequest request) {
        List<QuestionOptionInput> options = request.options();
        boolean hasOptions = options != null && !options.isEmpty();

        switch (type) {
            case TYPE_SINGLE_CHOICE, TYPE_MULTIPLE_CHOICE -> {
                if (!hasOptions) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            type + " requires at least 2 options");
                }
                if (options.size() > MAX_OPTIONS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "at most " + MAX_OPTIONS + " options allowed");
                }
                Set<String> keys = new HashSet<>();
                for (QuestionOptionInput o : options) {
                    if (!keys.add(o.optionKey())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "option keys must be unique within a question: " + o.optionKey());
                    }
                }
                if (TYPE_SINGLE_CHOICE.equals(type)) {
                    if (request.correctOptionKey() == null
                            || !keys.contains(request.correctOptionKey())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "SINGLE_CHOICE requires exactly one correctOptionKey that exists among the options");
                    }
                } else {
                    if (request.correctOptionKeys() == null || request.correctOptionKeys().isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "MULTIPLE_CHOICE requires at least one correctOptionKeys entry");
                    }
                    Set<String> correctKeys = new HashSet<>();
                    for (String key : request.correctOptionKeys()) {
                        if (!keys.contains(key)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "correctOptionKeys entry not among the options: " + key);
                        }
                        if (!correctKeys.add(key)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "correctOptionKeys must not contain duplicates: " + key);
                        }
                    }
                }
            }
            case TYPE_TRUE_FALSE -> {
                if (hasOptions) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE must not carry options");
                }
                if (request.correctBoolean() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "TRUE_FALSE requires correctBoolean");
                }
            }
            case TYPE_SHORT_ANSWER -> {
                if (hasOptions) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "SHORT_ANSWER must not carry options");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported questionType: " + type);
        }
    }

    private String buildAnswerDataJson(String type, CreateQuestionRequest request) {
        return switch (type) {
            case TYPE_SINGLE_CHOICE -> AnswerDataCodec.buildAnswerDataJson(
                    request.correctOptionKey(), null, null, null);
            case TYPE_MULTIPLE_CHOICE -> AnswerDataCodec.buildAnswerDataJson(
                    null, request.correctOptionKeys(), null, null);
            case TYPE_TRUE_FALSE -> AnswerDataCodec.buildAnswerDataJson(
                    null, null, request.correctBoolean(), null);
            case TYPE_SHORT_ANSWER -> AnswerDataCodec.buildAnswerDataJson(
                    null, null, null, request.referenceAnswer());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported questionType: " + type);
        };
    }

    private void insertOptions(Long questionId, Long spaceId, List<QuestionOptionInput> options) {
        if (options == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int index = 0;
        for (QuestionOptionInput input : options) {
            QuestionOption option = new QuestionOption();
            option.setSpaceId(spaceId);
            option.setQuestionId(questionId);
            option.setOptionKey(input.optionKey());
            option.setContent(input.content());
            option.setSortOrder(input.sortOrder() != null ? input.sortOrder() : index);
            option.setCreatedAt(now);
            questionOptionMapper.insert(option);
            index++;
        }
    }

    private void insertKnowledgePointLinks(Long questionId, Long spaceId, List<Long> kpIds) {
        if (kpIds == null || kpIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long kpId : kpIds) {
            QuestionKnowledgePoint link = new QuestionKnowledgePoint();
            link.setSpaceId(spaceId);
            link.setQuestionId(questionId);
            link.setKnowledgePointId(kpId);
            link.setCreatedAt(now);
            questionKnowledgePointMapper.insert(link);
        }
    }

    /** Authoring helpers shared by controller and tests. */
    public List<QuestionOption> optionsOf(Question question) {
        return questionOptionMapper.selectByQuestionId(question.getSpaceId(), question.getId());
    }

    public List<QuestionKnowledgePoint> knowledgePointsOf(Question question) {
        return questionKnowledgePointMapper.selectByQuestionId(question.getSpaceId(), question.getId());
    }
}

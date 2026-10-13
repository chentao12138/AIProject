package com.aistudy.server.note.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.knowledge.point.entity.KnowledgePoint;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.note.dto.NoteCreateRequest;
import com.aistudy.server.note.dto.NotePageResponse;
import com.aistudy.server.note.dto.NoteUpdateRequest;
import com.aistudy.server.note.dto.NoteView;
import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.mapper.NoteKnowledgePointMapper;
import com.aistudy.server.note.mapper.NoteMapper;
import com.aistudy.server.note.mapper.NoteSourceMapper;
import com.aistudy.server.question.entity.Question;
import com.aistudy.server.question.service.QuestionService;
import com.aistudy.server.search.util.SearchLikeEscape;
import com.aistudy.server.source.content.entity.ContentBlock;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.source.entity.Source;
import com.aistudy.server.source.service.SourceService;
import com.aistudy.server.source.page.entity.SourcePage;
import com.aistudy.server.source.page.service.SourcePageService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Note domain service. Owner identity always comes from the JWT subject;
 * request payloads never carry userId/userSubject.
 *
 * <p>A note's context pointers are validated against the space it lives in,
 * so a client cannot link a note to another user's source or knowledge point.
 */
@Service
public class NoteService {

    /** Upper bound accepted for one page; larger requests are clamped. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> CONTENT_FORMATS = Set.of("PLAIN_TEXT", "MARKDOWN");

    private final NoteMapper noteMapper;
    private final NoteSourceMapper noteSourceMapper;
    private final NoteKnowledgePointMapper noteKnowledgePointMapper;
    private final LearningSpaceService learningSpaceService;
    private final ContentBlockService contentBlockService;
    private final SourcePageService sourcePageService;
    private final SourceService sourceService;
    private final KnowledgePointService knowledgePointService;
    private final QuestionService questionService;

    public NoteService(NoteMapper noteMapper,
                       NoteSourceMapper noteSourceMapper,
                       NoteKnowledgePointMapper noteKnowledgePointMapper,
                       LearningSpaceService learningSpaceService,
                       ContentBlockService contentBlockService,
                       SourcePageService sourcePageService,
                       SourceService sourceService,
                       KnowledgePointService knowledgePointService,
                       QuestionService questionService) {
        this.noteMapper = noteMapper;
        this.noteSourceMapper = noteSourceMapper;
        this.noteKnowledgePointMapper = noteKnowledgePointMapper;
        this.learningSpaceService = learningSpaceService;
        this.contentBlockService = contentBlockService;
        this.sourcePageService = sourcePageService;
        this.sourceService = sourceService;
        this.knowledgePointService = knowledgePointService;
        this.questionService = questionService;
    }

    /**
     * One page of notes, newest change first. Returns {@code null} when the
     * caller has no such space (the controller turns that into 404).
     */
    public NotePageResponse listPage(String ownerSubject, Long spaceId, int page, int size, String keyword) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String keywordLike = (keyword == null || keyword.isBlank())
                ? null
                : "%" + SearchLikeEscape.escape(keyword.trim()) + "%";

        long totalItems = noteMapper.countBySpaceAndOwner(spaceId, ownerSubject, keywordLike);
        List<NoteView> items = totalItems == 0
                ? List.of()
                : noteMapper.selectPageBySpaceAndOwner(spaceId, ownerSubject, keywordLike,
                        safeSize, safePage * safeSize)
                        .stream().map(NoteView::from).toList();
        int totalPages = (int) ((totalItems + safeSize - 1) / safeSize);
        return new NotePageResponse(items, safePage, safeSize, totalItems, totalPages);
    }

    public Note getMine(String ownerSubject, Long spaceId, Long noteId) {
        return noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
    }

    @Transactional
    public Note create(String ownerSubject, Long spaceId, NoteCreateRequest request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        Note note = new Note();
        note.setSpaceId(spaceId);
        note.setUserSubject(ownerSubject);
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setContentFormat(normalizeFormat(request.contentFormat()));
        applyContext(ownerSubject, note, spaceId, request.sourceId(), request.sourcePageId(),
                request.contentBlockId(), request.knowledgePointId(), request.questionId());
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        noteMapper.insert(note);
        return note;
    }

    /**
     * Full replacement of the note document. A {@code null} context ID clears
     * that pointer; {@code createdAt} and {@code archivedAt} stay server-owned.
     */
    @Transactional
    public Note update(String ownerSubject, Long spaceId, Long noteId, NoteUpdateRequest request) {
        Note existing = getMine(ownerSubject, spaceId, noteId);
        if (existing == null) {
            return null;
        }
        existing.setTitle(request.title());
        existing.setContent(request.content());
        existing.setContentFormat(normalizeFormat(request.contentFormat()));
        applyContext(ownerSubject, existing, spaceId, request.sourceId(), request.sourcePageId(),
                request.contentBlockId(), request.knowledgePointId(), request.questionId());
        existing.setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public void archive(String ownerSubject, Long spaceId, Long noteId) {
        Note existing = getMine(ownerSubject, spaceId, noteId);
        if (existing == null) {
            return;
        }
        existing.setArchivedAt(LocalDateTime.now());
        noteMapper.updateById(existing);
    }

    /**
     * Hard delete. The M:N link rows have no cascade rule, so they go first;
     * the note itself disappears instead of lingering as invisible state.
     */
    @Transactional
    public boolean delete(String ownerSubject, Long spaceId, Long noteId) {
        Note existing = getMine(ownerSubject, spaceId, noteId);
        if (existing == null) {
            return false;
        }
        noteSourceMapper.deleteAllByNoteId(noteId);
        noteKnowledgePointMapper.deleteAllByNoteId(noteId);
        noteMapper.deleteById(noteId);
        return true;
    }

    private static String normalizeFormat(String contentFormat) {
        if (contentFormat == null || contentFormat.isBlank()) {
            return "PLAIN_TEXT";
        }
        String normalized = contentFormat.trim().toUpperCase();
        if (!CONTENT_FORMATS.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                    "contentFormat must be PLAIN_TEXT or MARKDOWN");
        }
        return normalized;
    }

    /**
     * Validates every context ID against {@code spaceId} and writes coherent
     * pointers: a block fixes its page and source, a page fixes its source.
     */
    private void applyContext(String ownerSubject, Note target, Long spaceId, Long sourceId, Long sourcePageId,
                              Long contentBlockId, Long knowledgePointId, Long questionId) {
        Long resolvedSourceId = sourceId;
        Long resolvedPageId = sourcePageId;

        if (contentBlockId != null) {
            ContentBlock block = contentBlockService.getMine(ownerSubject, spaceId, contentBlockId);
            if (block == null || !spaceId.equals(block.getSpaceId())) {
                throw unknownContext("contentBlockId");
            }
            resolvedPageId = block.getSourcePageId();
            resolvedSourceId = block.getSourceId();
        } else if (resolvedPageId != null) {
            SourcePage page = sourcePageService.getMine(ownerSubject, spaceId, resolvedPageId);
            if (page == null || !spaceId.equals(page.getSpaceId())) {
                throw unknownContext("sourcePageId");
            }
            if (resolvedSourceId != null && !resolvedSourceId.equals(page.getSourceId())) {
                throw mismatchedContext("sourceId", "sourcePageId");
            }
            resolvedSourceId = page.getSourceId();
        }

        if (resolvedSourceId != null) {
            Source source = sourceService.getMine(ownerSubject, spaceId, resolvedSourceId);
            if (source == null || !spaceId.equals(source.getSpaceId())) {
                throw unknownContext("sourceId");
            }
        }

        if (knowledgePointId != null) {
            KnowledgePoint point = knowledgePointService.getMine(ownerSubject, spaceId, knowledgePointId);
            if (point == null || !spaceId.equals(point.getSpaceId()) || point.getDeletedAt() != null) {
                throw unknownContext("knowledgePointId");
            }
        }

        if (questionId != null) {
            Question question = questionService.getMine(ownerSubject, spaceId, questionId);
            if (question == null || !spaceId.equals(question.getSpaceId())
                    || question.getDeletedAt() != null) {
                throw unknownContext("questionId");
            }
        }

        target.setSourceId(resolvedSourceId);
        target.setSourcePageId(resolvedPageId);
        target.setContentBlockId(contentBlockId);
        target.setKnowledgePointId(knowledgePointId);
        target.setQuestionId(questionId);
    }

    private static ApiException unknownContext(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                field + " does not reference an entity in this learning space");
    }

    private static ApiException mismatchedContext(String field, String otherField) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
                field + " does not contain the given " + otherField);
    }
}

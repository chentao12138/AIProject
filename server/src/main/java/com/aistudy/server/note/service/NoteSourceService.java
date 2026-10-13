package com.aistudy.server.note.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.entity.NoteSource;
import com.aistudy.server.note.mapper.NoteMapper;
import com.aistudy.server.note.mapper.NoteSourceMapper;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class NoteSourceService {

    private final NoteSourceMapper noteSourceMapper;
    private final NoteMapper noteMapper;
    private final LearningSpaceService learningSpaceService;
    private final ContentBlockService contentBlockService;

    public NoteSourceService(NoteSourceMapper noteSourceMapper,
                             NoteMapper noteMapper,
                             LearningSpaceService learningSpaceService,
                             ContentBlockService contentBlockService) {
        this.noteSourceMapper = noteSourceMapper;
        this.noteMapper = noteMapper;
        this.learningSpaceService = learningSpaceService;
        this.contentBlockService = contentBlockService;
    }

    public List<Long> listContentBlockIds(String ownerSubject, Long spaceId, Long noteId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Note note = noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
        if (note == null) {
            return null;
        }
        return noteSourceMapper.selectContentBlockIdsByNote(noteId, spaceId, ownerSubject);
    }

    /**
     * Links every given content block, skipping the ones already linked.
     *
     * <p>An unknown or foreign id is a 404 naming it, not a silent
     * {@code return}: the controller answers 201, so linking nothing used to be
     * indistinguishable from linking everything (contract request
     * C-Notes-link-noop).
     *
     * @throws ApiException 404 {@code SPACE_NOT_FOUND} / {@code NOTE_NOT_FOUND} /
     *         {@code CONTENT_BLOCK_NOT_FOUND}
     */
    @Transactional
    public void linkContentBlocks(String ownerSubject, Long spaceId, Long noteId, List<Long> blockIds) {
        requireSpace(ownerSubject, spaceId);
        requireNote(ownerSubject, spaceId, noteId);

        Set<Long> uniqueIds = new LinkedHashSet<>(blockIds);
        for (Long blockId : uniqueIds) {
            if (blockId == null || contentBlockService.getMine(ownerSubject, spaceId, blockId) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.CONTENT_BLOCK_NOT_FOUND,
                        "content block " + blockId + " is not in this space");
            }
        }

        List<Long> existing = noteSourceMapper.selectExistingBlockIds(noteId, List.copyOf(uniqueIds));
        Set<Long> existingSet = new LinkedHashSet<>(existing);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long blockId : uniqueIds) {
            if (existingSet.contains(blockId)) {
                continue;
            }
            NoteSource link = new NoteSource();
            link.setNoteId(noteId);
            link.setSpaceId(spaceId);
            link.setContentBlockId(blockId);
            link.setRelationType(null);
            link.setCreatedAt(now);
            noteSourceMapper.insert(link);
        }
    }

    @Transactional
    public void unlinkContentBlock(String ownerSubject, Long spaceId, Long noteId, Long blockId) {
        requireSpace(ownerSubject, spaceId);
        requireNote(ownerSubject, spaceId, noteId);
        noteSourceMapper.deleteByNoteIdAndBlockId(noteId, blockId);
    }

    private void requireSpace(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.SPACE_NOT_FOUND,
                    "learning space " + spaceId + " not found");
        }
    }

    private void requireNote(String ownerSubject, Long spaceId, Long noteId) {
        if (noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOTE_NOT_FOUND,
                    "note " + noteId + " not found in this space");
        }
    }
}

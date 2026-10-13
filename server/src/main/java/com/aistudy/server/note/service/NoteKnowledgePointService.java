package com.aistudy.server.note.service;

import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.entity.NoteKnowledgePoint;
import com.aistudy.server.note.mapper.NoteKnowledgePointMapper;
import com.aistudy.server.note.mapper.NoteMapper;
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
public class NoteKnowledgePointService {

    private final NoteKnowledgePointMapper noteKnowledgePointMapper;
    private final NoteMapper noteMapper;
    private final LearningSpaceService learningSpaceService;
    private final KnowledgePointService knowledgePointService;

    public NoteKnowledgePointService(NoteKnowledgePointMapper noteKnowledgePointMapper,
                                     NoteMapper noteMapper,
                                     LearningSpaceService learningSpaceService,
                                     KnowledgePointService knowledgePointService) {
        this.noteKnowledgePointMapper = noteKnowledgePointMapper;
        this.noteMapper = noteMapper;
        this.learningSpaceService = learningSpaceService;
        this.knowledgePointService = knowledgePointService;
    }

    public List<Long> listKnowledgePointIds(String ownerSubject, Long spaceId, Long noteId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        Note note = noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
        if (note == null) {
            return null;
        }
        return noteKnowledgePointMapper.selectKnowledgePointIdsByNote(noteId, spaceId, ownerSubject);
    }

    /**
     * Links every given knowledge point, skipping the ones already linked.
     *
     * <p>Every id is validated BEFORE the first insert, and an unknown or
     * foreign id is a 404 rather than a silent {@code return}: the controller
     * answers 201, so a partial or empty link used to be indistinguishable from
     * success for a client that had just been told the link existed.
     *
     * @throws ApiException 404 {@code SPACE_NOT_FOUND} / {@code NOTE_NOT_FOUND} /
     *         {@code KNOWLEDGE_POINT_NOT_FOUND}, naming the offending id
     */
    @Transactional
    public void linkKnowledgePoints(String ownerSubject, Long spaceId, Long noteId, List<Long> kpIds) {
        requireSpace(ownerSubject, spaceId);
        requireNote(ownerSubject, spaceId, noteId);

        Set<Long> uniqueIds = new LinkedHashSet<>(kpIds);
        for (Long kpId : uniqueIds) {
            if (kpId == null || knowledgePointService.getMine(ownerSubject, spaceId, kpId) == null) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.KNOWLEDGE_POINT_NOT_FOUND,
                        "knowledge point " + kpId + " is not in this space");
            }
        }

        List<Long> existing = noteKnowledgePointMapper.selectExistingKpIds(noteId, List.copyOf(uniqueIds));
        Set<Long> existingSet = new LinkedHashSet<>(existing);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        for (Long kpId : uniqueIds) {
            if (existingSet.contains(kpId)) {
                continue;
            }
            NoteKnowledgePoint link = new NoteKnowledgePoint();
            link.setNoteId(noteId);
            link.setSpaceId(spaceId);
            link.setKnowledgePointId(kpId);
            link.setCreatedAt(now);
            noteKnowledgePointMapper.insert(link);
        }
    }

    @Transactional
    public void unlinkKnowledgePoint(String ownerSubject, Long spaceId, Long noteId, Long kpId) {
        requireSpace(ownerSubject, spaceId);
        requireNote(ownerSubject, spaceId, noteId);
        noteKnowledgePointMapper.deleteByNoteIdAndKpId(noteId, kpId);
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

package com.aistudy.server.note.service;

import com.aistudy.server.knowledge.point.service.KnowledgePointService;
import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.entity.NoteKnowledgePoint;
import com.aistudy.server.note.mapper.NoteKnowledgePointMapper;
import com.aistudy.server.note.mapper.NoteMapper;
import com.aistudy.server.space.service.LearningSpaceService;
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

    @Transactional
    public void linkKnowledgePoints(String ownerSubject, Long spaceId, Long noteId, List<Long> kpIds) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return;
        }
        Note note = noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
        if (note == null) {
            return;
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(kpIds);
        for (Long kpId : uniqueIds) {
            if (knowledgePointService.getMine(ownerSubject, spaceId, kpId) == null) {
                return;
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
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return;
        }
        noteKnowledgePointMapper.deleteByNoteIdAndKpId(noteId, kpId);
    }
}

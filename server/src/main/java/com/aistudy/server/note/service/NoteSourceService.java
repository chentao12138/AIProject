package com.aistudy.server.note.service;

import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.entity.NoteSource;
import com.aistudy.server.note.mapper.NoteMapper;
import com.aistudy.server.note.mapper.NoteSourceMapper;
import com.aistudy.server.source.content.service.ContentBlockService;
import com.aistudy.server.space.service.LearningSpaceService;
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

    @Transactional
    public void linkContentBlocks(String ownerSubject, Long spaceId, Long noteId, List<Long> blockIds) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return;
        }
        Note note = noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
        if (note == null) {
            return;
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(blockIds);
        for (Long blockId : uniqueIds) {
            if (contentBlockService.getMine(ownerSubject, spaceId, blockId) == null) {
                return;
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
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return;
        }
        noteSourceMapper.deleteByNoteIdAndBlockId(noteId, blockId);
    }
}

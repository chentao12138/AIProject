package com.aistudy.server.note.service;

import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.mapper.NoteMapper;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Note domain service. Owner identity always comes from the JWT subject;
 * request payloads never carry userId/userSubject.
 */
@Service
public class NoteService {

    private final NoteMapper noteMapper;
    private final LearningSpaceService learningSpaceService;

    public NoteService(NoteMapper noteMapper,
                       LearningSpaceService learningSpaceService) {
        this.noteMapper = noteMapper;
        this.learningSpaceService = learningSpaceService;
    }

    public List<Note> listMine(String ownerSubject, Long spaceId) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        return noteMapper.selectBySpaceAndUser(spaceId, ownerSubject, ownerSubject);
    }

    public Note getMine(String ownerSubject, Long spaceId, Long noteId) {
        return noteMapper.selectByIdAndSpaceAndOwner(noteId, spaceId, ownerSubject);
    }

    @Transactional
    public Note create(String ownerSubject, Long spaceId, Note request) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        Note note = new Note();
        note.setSpaceId(spaceId);
        note.setUserSubject(ownerSubject);
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        note.setContentFormat(request.getContentFormat() != null ? request.getContentFormat() : "PLAIN_TEXT");
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        noteMapper.insert(note);
        return note;
    }

    @Transactional
    public Note update(String ownerSubject, Long spaceId, Long noteId, Note patch) {
        Note existing = getMine(ownerSubject, spaceId, noteId);
        if (existing == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
        if (patch.getContent() != null) existing.setContent(patch.getContent());
        if (patch.getContentFormat() != null) existing.setContentFormat(patch.getContentFormat());
        existing.setUpdatedAt(now);
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
}

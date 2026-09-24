package com.aistudy.server.note.controller;

import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.service.NoteKnowledgePointService;
import com.aistudy.server.note.service.NoteService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/notes/{noteId}/knowledge-points")
@SecurityRequirement(name = "bearerAuth")
public class NoteKnowledgePointController {

    private final NoteKnowledgePointService noteKnowledgePointService;
    private final NoteService noteService;

    public NoteKnowledgePointController(NoteKnowledgePointService noteKnowledgePointService,
                                        NoteService noteService) {
        this.noteKnowledgePointService = noteKnowledgePointService;
        this.noteService = noteService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void link(@PathVariable Long spaceId,
                     @PathVariable Long noteId,
                     @RequestBody List<Long> kpIds,
                     Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        noteKnowledgePointService.linkKnowledgePoints(authentication.getName(), spaceId, noteId, kpIds);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Long> list(@PathVariable Long spaceId,
                           @PathVariable Long noteId,
                           Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        List<Long> kpIds = noteKnowledgePointService.listKnowledgePointIds(
                authentication.getName(), spaceId, noteId);
        if (kpIds == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
        return kpIds;
    }

    @DeleteMapping(value = "/{kpId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable Long spaceId,
                       @PathVariable Long noteId,
                       @PathVariable Long kpId,
                       Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        noteKnowledgePointService.unlinkKnowledgePoint(authentication.getName(), spaceId, noteId, kpId);
    }

    private void requireOwnedNote(String ownerSubject, Long spaceId, Long noteId) {
        Note note = noteService.getMine(ownerSubject, spaceId, noteId);
        if (note == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
    }
}

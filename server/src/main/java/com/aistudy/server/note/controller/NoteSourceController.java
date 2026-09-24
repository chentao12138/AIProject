package com.aistudy.server.note.controller;

import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.service.NoteService;
import com.aistudy.server.note.service.NoteSourceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/notes/{noteId}/source-blocks")
@SecurityRequirement(name = "bearerAuth")
public class NoteSourceController {

    private final NoteSourceService noteSourceService;
    private final NoteService noteService;
    private final com.aistudy.server.provenance.service.SourceReferenceService sourceReferenceService;

    public NoteSourceController(NoteSourceService noteSourceService,
                                NoteService noteService,
                                com.aistudy.server.provenance.service.SourceReferenceService sourceReferenceService) {
        this.noteSourceService = noteSourceService;
        this.noteService = noteService;
        this.sourceReferenceService = sourceReferenceService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void link(@PathVariable Long spaceId,
                     @PathVariable Long noteId,
                     @RequestBody List<Long> blockIds,
                     Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        noteSourceService.linkContentBlocks(authentication.getName(), spaceId, noteId, blockIds);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<com.aistudy.server.provenance.dto.SourceReference> list(
            @PathVariable Long spaceId,
            @PathVariable Long noteId,
            Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        List<Long> blockIds = noteSourceService.listContentBlockIds(
                authentication.getName(), spaceId, noteId);
        if (blockIds == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
        return sourceReferenceService.byContentBlockIds(spaceId, blockIds);
    }

    @DeleteMapping(value = "/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable Long spaceId,
                       @PathVariable Long noteId,
                       @PathVariable Long blockId,
                       Authentication authentication) {
        requireOwnedNote(authentication.getName(), spaceId, noteId);
        noteSourceService.unlinkContentBlock(authentication.getName(), spaceId, noteId, blockId);
    }

    private void requireOwnedNote(String ownerSubject, Long spaceId, Long noteId) {
        Note note = noteService.getMine(ownerSubject, spaceId, noteId);
        if (note == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
    }
}

package com.aistudy.server.note.controller;

import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.service.NoteService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Note CRUD. Identity always from JWT; clients never pass userId.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/notes")
@SecurityRequirement(name = "bearerAuth")
public class NoteController {

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Note> list(@PathVariable Long spaceId,
                           Authentication authentication) {
        List<Note> notes = noteService.listMine(authentication.getName(), spaceId);
        if (notes == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return notes;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Note create(@PathVariable Long spaceId,
                       @RequestBody Note request,
                       Authentication authentication) {
        Note created = noteService.create(authentication.getName(), spaceId, request);
        if (created == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return created;
    }

    @GetMapping(value = "/{noteId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Note get(@PathVariable Long spaceId,
                    @PathVariable Long noteId,
                    Authentication authentication) {
        Note note = noteService.getMine(authentication.getName(), spaceId, noteId);
        if (note == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
        return note;
    }

    @PutMapping(value = "/{noteId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Note update(@PathVariable Long spaceId,
                       @PathVariable Long noteId,
                       @RequestBody Note patch,
                       Authentication authentication) {
        Note updated = noteService.update(authentication.getName(), spaceId, noteId, patch);
        if (updated == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found");
        }
        return updated;
    }

    @PostMapping(value = "/{noteId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long spaceId,
                        @PathVariable Long noteId,
                        Authentication authentication) {
        noteService.archive(authentication.getName(), spaceId, noteId);
    }
}

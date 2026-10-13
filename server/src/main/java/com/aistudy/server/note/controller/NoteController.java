package com.aistudy.server.note.controller;

import com.aistudy.server.note.dto.NoteCreateRequest;
import com.aistudy.server.note.dto.NotePageResponse;
import com.aistudy.server.note.dto.NoteUpdateRequest;
import com.aistudy.server.note.dto.NoteView;
import com.aistudy.server.common.problem.ApiErrorCodes;
import com.aistudy.server.common.problem.ApiException;
import com.aistudy.server.note.entity.Note;
import com.aistudy.server.note.service.NoteService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Note CRUD. Identity always from JWT; clients never pass userId.
 *
 * <p>Archived notes are hidden from the list; {@code DELETE} removes the row
 * (and its link rows) outright.
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
    public NotePageResponse list(@PathVariable Long spaceId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) Long knowledgePointId,
                                 @RequestParam(required = false) Long sourceId,
                                 @RequestParam(required = false) Long contentBlockId,
                                 @RequestParam(required = false) Long questionId,
                                 @RequestParam(required = false) Boolean archived,
                                 Authentication authentication) {
        NotePageResponse result = noteService.listPage(authentication.getName(), spaceId,
                page, size, keyword, knowledgePointId, sourceId, contentBlockId, questionId,
                archived);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return result;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView create(@PathVariable Long spaceId,
                           @Valid @RequestBody NoteCreateRequest request,
                           Authentication authentication) {
        Note created = noteService.create(authentication.getName(), spaceId, request);
        if (created == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return NoteView.from(created);
    }

    @GetMapping(value = "/{noteId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public NoteView get(@PathVariable Long spaceId,
                        @PathVariable Long noteId,
                        Authentication authentication) {
        return NoteView.from(requireNote(authentication, spaceId, noteId));
    }

    @PutMapping(value = "/{noteId}", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public NoteView update(@PathVariable Long spaceId,
                           @PathVariable Long noteId,
                           @Valid @RequestBody NoteUpdateRequest request,
                           Authentication authentication) {
        requireNote(authentication, spaceId, noteId);
        return NoteView.from(noteService.update(authentication.getName(), spaceId, noteId, request));
    }

    @DeleteMapping(value = "/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long spaceId,
                       @PathVariable Long noteId,
                       Authentication authentication) {
        requireNote(authentication, spaceId, noteId);
        noteService.delete(authentication.getName(), spaceId, noteId);
    }

    @PostMapping(value = "/{noteId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long spaceId,
                        @PathVariable Long noteId,
                        Authentication authentication) {
        requireNote(authentication, spaceId, noteId);
        noteService.archive(authentication.getName(), spaceId, noteId);
    }

    /**
     * Undo of {@code /archive}. Without it archiving a note was one-way: the row
     * left the list and no verb could bring it back.
     */
    @PostMapping(value = "/{noteId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable Long spaceId,
                        @PathVariable Long noteId,
                        Authentication authentication) {
        requireNote(authentication, spaceId, noteId);
        noteService.restore(authentication.getName(), spaceId, noteId);
    }

    private Note requireNote(Authentication authentication, Long spaceId, Long noteId) {
        Note note = noteService.getMine(authentication.getName(), spaceId, noteId);
        if (note == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCodes.NOTE_NOT_FOUND,
                    "note " + noteId + " not found in this space");
        }
        return note;
    }
}

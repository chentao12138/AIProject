package com.aistudy.server.admin.space.controller;

import com.aistudy.server.space.entity.LearningSpace;
import com.aistudy.server.space.mapper.LearningSpaceMapper;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/spaces")
@SecurityRequirement(name = "bearerAuth")
public class AdminSpaceController {

    private final LearningSpaceMapper learningSpaceMapper;

    public AdminSpaceController(LearningSpaceMapper learningSpaceMapper) {
        this.learningSpaceMapper = learningSpaceMapper;
    }

    public record AdminSpaceView(
            Long id,
            String name,
            String description,
            String ownerSubject,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long memberCount
    ) {
        public static AdminSpaceView from(LearningSpace space, Long memberCount) {
            return new AdminSpaceView(
                    space.getId(),
                    space.getName(),
                    space.getDescription(),
                    space.getOwnerSubject(),
                    space.getStatus(),
                    space.getCreatedAt(),
                    space.getUpdatedAt(),
                    memberCount);
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminSpaceView> list(
            @RequestParam(required = false) String ownerSubject,
            @RequestParam(required = false) String status) {
        List<LearningSpace> spaces = learningSpaceMapper.selectAllAdmin(ownerSubject, status);
        return spaces.stream()
                .map(space -> AdminSpaceView.from(space, learningSpaceMapper.countMembers(space.getId())))
                .toList();
    }

    @GetMapping("/{spaceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminSpaceView get(@PathVariable Long spaceId) {
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return AdminSpaceView.from(space, learningSpaceMapper.countMembers(space.getId()));
    }

    @PostMapping("/{spaceId}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminSpaceView archive(@PathVariable Long spaceId) {
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = learningSpaceMapper.archiveById(spaceId, "ARCHIVED", now, now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "LearningSpace state changed concurrently");
        }
        space.setStatus("ARCHIVED");
        space.setArchivedAt(now);
        space.setUpdatedAt(now);
        return AdminSpaceView.from(space, learningSpaceMapper.countMembers(spaceId));
    }

    @PostMapping("/{spaceId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminSpaceView restore(@PathVariable Long spaceId) {
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int updated = learningSpaceMapper.restoreById(spaceId, "ACTIVE", now);
        if (updated == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "LearningSpace state changed concurrently");
        }
        space.setStatus("ACTIVE");
        space.setArchivedAt(null);
        space.setUpdatedAt(now);
        return AdminSpaceView.from(space, learningSpaceMapper.countMembers(spaceId));
    }
}
